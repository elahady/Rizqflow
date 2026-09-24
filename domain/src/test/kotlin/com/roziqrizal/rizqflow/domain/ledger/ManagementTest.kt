package com.roziqrizal.rizqflow.domain.ledger

import com.roziqrizal.rizqflow.domain.auth.runSuspend
import com.roziqrizal.rizqflow.domain.model.AccountKind
import com.roziqrizal.rizqflow.domain.money.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManagementTest {

    private fun rupiah(n: Long) = Money.rupiah(n)

    private fun service(f: LedgerFixture) = ManagementService(f.store, f.store, f.newId)

    private fun <T> LedgerResult<T>.error(): LedgerError = (this as LedgerResult.Failure).error

    private fun newAccount(name: String, opening: Long = 0, kind: AccountKind = AccountKind.BANK) = NewAccount(name, kind, rupiah(opening))

    // ------------------------------------------------------------------ akun

    @Test
    fun `daftar akun memuat saldo menurut catatan`() {
        val f = LedgerFixture().standard()
        f.income(1_000_000)

        val overview = runSuspend { service(f).accountOverview() }

        assertEquals(listOf("Dompet"), overview.active.map { it.account.name })
        assertEquals(rupiah(1_500_000), overview.active.single().balance)
    }

    @Test
    fun `menambah akun menyimpan saldo awal dan urutan berikutnya`() {
        val f = LedgerFixture().standard()

        val result = runSuspend { service(f).addAccount(newAccount("  Bank Jago  ", 6_840_000)) }

        assertIs<LedgerResult.Success<*>>(result)
        val akun = f.store.accountRows.values.last()
        assertEquals("Bank Jago", akun.name)
        assertEquals(rupiah(6_840_000), akun.openingBalance)
        assertEquals(1, akun.sortOrder)
    }

    @Test
    fun `jumlah akun tidak dibatasi untuk paket apa pun`() {
        val f = LedgerFixture().standard()
        val s = service(f)

        repeat(10) { i ->
            assertIs<LedgerResult.Success<*>>(runSuspend { s.addAccount(newAccount("Akun $i")) })
        }

        assertEquals(11, runSuspend { s.accountOverview() }.active.size)
    }

    @Test
    fun `akun terarsip bisa dipulihkan tanpa batas`() {
        val f = LedgerFixture().standard()
        val s = service(f)
        val bank = (runSuspend { s.addAccount(newAccount("Bank")) } as LedgerResult.Success).value
        runSuspend { s.archiveAccount(bank) }
        runSuspend { s.addAccount(newAccount("GoPay")) }
        runSuspend { s.addAccount(newAccount("Cadangan")) }

        assertIs<LedgerResult.Success<*>>(runSuspend { s.restoreAccount(bank) })
        assertEquals(4, runSuspend { s.accountOverview() }.active.size)
    }

    @Test
    fun `nama akun harus valid dan unik tanpa membedakan huruf`() {
        val f = LedgerFixture().standard()
        val s = service(f)

        assertEquals(LedgerError.INVALID_NAME, runSuspend { s.addAccount(newAccount("   ")) }.error())
        assertEquals(LedgerError.INVALID_NAME, runSuspend { s.addAccount(newAccount("x".repeat(Account.NAME_MAX + 1))) }.error())
        assertEquals(LedgerError.NAME_TAKEN, runSuspend { s.addAccount(newAccount("dompet")) }.error())
        assertEquals(LedgerError.AMOUNT_NOT_POSITIVE, runSuspend { s.addAccount(newAccount("Bank", -1)) }.error())
    }

    @Test
    fun `mengubah nama dan jenis akun tidak mengubah saldo dan boleh memakai nama sendiri`() {
        val f = LedgerFixture().standard()
        val s = service(f)
        f.income(1_000_000)

        assertIs<LedgerResult.Success<*>>(runSuspend { s.updateAccount(f.account.id, "Dompet", AccountKind.EWALLET) })
        assertIs<LedgerResult.Success<*>>(runSuspend { s.updateAccount(f.account.id, "Tunai", AccountKind.CASH) })

        assertEquals("Tunai", f.account.name)
        assertEquals(rupiah(1_500_000), f.balance())
    }

    @Test
    fun `akun terakhir yang aktif tidak bisa diarsipkan`() {
        val f = LedgerFixture().standard()
        val s = service(f)

        assertEquals(LedgerError.LAST_ACCOUNT, runSuspend { s.archiveAccount(f.account.id) }.error())

        val kedua = (runSuspend { s.addAccount(newAccount("Bank")) } as LedgerResult.Success).value
        assertIs<LedgerResult.Success<*>>(runSuspend { s.archiveAccount(f.account.id) })
        assertTrue(f.store.accountRows.getValue(f.account.id).archived)
        assertEquals(LedgerError.LAST_ACCOUNT, runSuspend { s.archiveAccount(kedua) }.error())
    }

    @Test
    fun `akun terarsip memisahkan diri di daftar dan tetap punya saldo`() {
        val f = LedgerFixture().standard()
        val s = service(f)
        val bank = (runSuspend { s.addAccount(newAccount("Bank", 250_000)) } as LedgerResult.Success).value
        runSuspend { s.archiveAccount(bank) }

        val overview = runSuspend { s.accountOverview() }

        assertEquals(listOf("Dompet"), overview.active.map { it.account.name })
        assertEquals(listOf("Bank"), overview.archived.map { it.account.name })
        assertEquals(rupiah(250_000), overview.archived.single().balance)
    }

    @Test
    fun `akun yang tidak ada ditolak`() {
        val f = LedgerFixture().standard()
        val s = service(f)
        val hilang = com.roziqrizal.rizqflow.domain.model.AccountId("tidak-ada")

        assertEquals(LedgerError.ACCOUNT_NOT_FOUND, runSuspend { s.updateAccount(hilang, "A", AccountKind.CASH) }.error())
        assertEquals(LedgerError.ACCOUNT_NOT_FOUND, runSuspend { s.archiveAccount(hilang) }.error())
        assertEquals(LedgerError.ACCOUNT_NOT_FOUND, runSuspend { s.restoreAccount(hilang) }.error())
    }

    // ------------------------------------------------------------------ kategori

    @Test
    fun `daftar kategori per ruang aktif dengan kategori sistem paling akhir`() {
        val f = LedgerFixture().standard()

        val hasil = runSuspend { service(f).categoryOverview() }

        assertEquals(listOf("Memberi", "Diri", "Keluarga"), hasil.map { it.room.name })
        val memberi = hasil.first { it.room.name == "Memberi" }.categories
        assertEquals("Tak terlacak", memberi.last().name)
        assertTrue(memberi.first { it.name == "Zakat mal" }.isSystem)
        assertTrue(hasil.all { it.archived.isEmpty() })
    }

    @Test
    fun `menambah kategori ke ruang menyimpannya setelah yang ada`() {
        val f = LedgerFixture().standard()
        val s = service(f)

        val result = runSuspend { s.addCategory(f.room("Keluarga").id, "  Transportasi ") }

        assertIs<LedgerResult.Success<*>>(result)
        val nama = runSuspend { s.categoryOverview() }.first { it.room.name == "Keluarga" }.categories.map { it.name }
        assertTrue("Transportasi" in nama)
        assertEquals("Tak terlacak", nama.last())
    }

    @Test
    fun `nama kategori harus valid unik per ruang dan bukan nama sistem`() {
        val f = LedgerFixture().standard()
        val s = service(f)
        val keluarga = f.room("Keluarga").id

        assertEquals(LedgerError.INVALID_NAME, runSuspend { s.addCategory(keluarga, " ") }.error())
        assertEquals(LedgerError.INVALID_NAME, runSuspend { s.addCategory(keluarga, "x".repeat(Category.NAME_MAX + 1)) }.error())
        assertEquals(LedgerError.NAME_TAKEN, runSuspend { s.addCategory(keluarga, "belanja bulanan") }.error())
        assertEquals(LedgerError.NAME_TAKEN, runSuspend { s.addCategory(keluarga, "Tak Terlacak") }.error())
        assertEquals(LedgerError.NAME_TAKEN, runSuspend { s.addCategory(f.room("Diri").id, "zakat mal") }.error())
        // Nama yang sama di ruang lain boleh.
        assertIs<LedgerResult.Success<*>>(runSuspend { s.addCategory(f.room("Diri").id, "Belanja bulanan") })
    }

    @Test
    fun `menambah kategori ke ruang terarsip atau yang tidak ada ditolak`() {
        val f = LedgerFixture().standard()
        val s = service(f)
        runSuspend { f.rules.archiveRoom(f.room("Diri").id) }

        assertEquals(LedgerError.ROOM_ARCHIVED, runSuspend { s.addCategory(f.room("Diri").id, "Baru") }.error())
        assertEquals(LedgerError.ROOM_NOT_FOUND, runSuspend { s.addCategory(com.roziqrizal.rizqflow.domain.model.RoomId("x"), "Baru") }.error())
    }

    @Test
    fun `mengganti nama kategori biasa berhasil dan kategori sistem ditolak`() {
        val f = LedgerFixture().standard()
        val s = service(f)
        val sekolah = f.category("Keluarga", "Sekolah")

        assertIs<LedgerResult.Success<*>>(runSuspend { s.renameCategory(sekolah.id, "Pendidikan") })
        assertEquals("Pendidikan", f.store.categoryRows.getValue(sekolah.id).name)
        assertEquals(LedgerError.NAME_TAKEN, runSuspend { s.renameCategory(sekolah.id, "Listrik dan air") }.error())
        assertEquals(LedgerError.CATEGORY_IS_SYSTEM, runSuspend { s.renameCategory(f.category("Keluarga", "Tak terlacak").id, "Lain") }.error())
        assertEquals(LedgerError.CATEGORY_IS_SYSTEM, runSuspend { s.archiveCategory(f.category("Memberi", "Zakat mal").id) }.error())
    }

    @Test
    fun `mengarsipkan kategori menyembunyikannya dari Catat dan bisa dipulihkan`() {
        val f = LedgerFixture().standard()
        val s = service(f)
        val sekolah = f.category("Keluarga", "Sekolah")

        assertIs<LedgerResult.Success<*>>(runSuspend { s.archiveCategory(sekolah.id) })

        val keluarga = runSuspend { s.categoryOverview() }.first { it.room.name == "Keluarga" }
        assertFalse(keluarga.categories.any { it.id == sekolah.id })
        assertEquals(listOf("Sekolah"), keluarga.archived.map { it.name })
        assertFalse(runSuspend { f.store.categories(f.room("Keluarga").id) }.any { it.id == sekolah.id })

        assertIs<LedgerResult.Success<*>>(runSuspend { s.restoreCategory(sekolah.id) })
        assertTrue(runSuspend { s.categoryOverview() }.first { it.room.name == "Keluarga" }.categories.any { it.id == sekolah.id })
    }

    @Test
    fun `kategori biasa terakhir di sebuah ruang tidak bisa diarsipkan`() {
        val f = LedgerFixture().standard()
        val s = service(f)
        val memberi = runSuspend { s.categoryOverview() }.first { it.room.name == "Memberi" }.categories.filter { !it.isSystem }
        assertEquals(2, memberi.size)

        assertIs<LedgerResult.Success<*>>(runSuspend { s.archiveCategory(memberi[0].id) })
        assertEquals(LedgerError.LAST_CATEGORY, runSuspend { s.archiveCategory(memberi[1].id) }.error())
    }

    @Test
    fun `kategori yang tidak ada ditolak`() {
        val f = LedgerFixture().standard()
        val s = service(f)
        val hilang = com.roziqrizal.rizqflow.domain.model.CategoryId("tidak-ada")

        assertEquals(LedgerError.CATEGORY_NOT_FOUND, runSuspend { s.renameCategory(hilang, "A") }.error())
        assertEquals(LedgerError.CATEGORY_NOT_FOUND, runSuspend { s.archiveCategory(hilang) }.error())
        assertEquals(LedgerError.CATEGORY_NOT_FOUND, runSuspend { s.restoreCategory(hilang) }.error())
        assertNull(runSuspend { f.store.findCategory(hilang) })
    }
}
