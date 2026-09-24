package com.roziqrizal.rizqflow.domain.ledger

import com.roziqrizal.rizqflow.domain.auth.runSuspend
import com.roziqrizal.rizqflow.domain.model.TransactionKind
import com.roziqrizal.rizqflow.domain.money.Money
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FavoritesTest {

    private fun rupiah(n: Long) = Money.rupiah(n)

    private fun service(f: LedgerFixture) = FavoriteService(f.store, f.store, f.store, f.ledger, f.newId) { f.now }

    private fun <T> LedgerResult<T>.error(): LedgerError = (this as LedgerResult.Failure).error

    private fun <T> LedgerResult<T>.value(): T = (this as LedgerResult.Success).value

    private fun create(f: LedgerFixture, name: String, amount: Long = 15_000, room: String = "Keluarga", category: String = "Lain-lain") =
        runSuspend { service(f).create(name, rupiah(amount), f.room(room).id, f.category(room, category).id, f.account.id) }

    @Test
    fun `membuat favorit menyimpan tujuannya dan tampil di daftar dengan nama`() {
        val f = LedgerFixture().standard()

        val result = create(f, "  Kopi ")

        assertIs<LedgerResult.Success<QuickFavorite>>(result)
        val baris = runSuspend { service(f).list() }.single()
        assertEquals("Kopi", baris.favorite.name)
        assertEquals(rupiah(15_000), baris.favorite.amount)
        assertEquals("Keluarga", baris.roomName)
        assertEquals("Lain-lain", baris.categoryName)
        assertEquals("Dompet", baris.accountName)
        assertTrue(baris.usable)
    }

    @Test
    fun `nama dan nominal favorit harus valid dan nama unik`() {
        val f = LedgerFixture().standard()
        create(f, "Kopi")

        assertEquals(LedgerError.INVALID_NAME, create(f, " ").error())
        assertEquals(LedgerError.INVALID_NAME, create(f, "x".repeat(QuickFavorite.NAME_MAX + 1)).error())
        assertEquals(LedgerError.AMOUNT_NOT_POSITIVE, create(f, "Parkir", 0).error())
        assertEquals(LedgerError.NAME_TAKEN, create(f, "kopi").error())
    }

    @Test
    fun `paling banyak enam favorit`() {
        val f = LedgerFixture().standard()
        (1..QuickFavorite.MAX_COUNT).forEach { assertIs<LedgerResult.Success<*>>(create(f, "Favorit $it")) }

        assertEquals(LedgerError.FAVORITE_LIMIT_REACHED, create(f, "Ketujuh").error())
    }

    @Test
    fun `favorit ke ruang atau kategori atau akun yang tidak valid ditolak`() {
        val f = LedgerFixture().standard()
        val s = service(f)
        val keluarga = f.room("Keluarga")

        assertEquals(LedgerError.CATEGORY_NOT_IN_ROOM, runSuspend { s.create("A", rupiah(1_000), keluarga.id, f.category("Diri", "Investasi").id, f.account.id) }.error())
        val bank = f.addAccount("Bank", archived = true)
        assertEquals(LedgerError.ACCOUNT_ARCHIVED, runSuspend { s.create("B", rupiah(1_000), keluarga.id, f.category("Keluarga", "Lain-lain").id, bank.id) }.error())
        runSuspend { f.rules.archiveRoom(keluarga.id) }
        assertEquals(LedgerError.ROOM_ARCHIVED, runSuspend { s.create("C", rupiah(1_000), keluarga.id, f.category("Keluarga", "Lain-lain").id, f.account.id) }.error())
    }

    @Test
    fun `mengubah nama dan nominal tanpa mengubah tujuan`() {
        val f = LedgerFixture().standard()
        val id = create(f, "Kopi").value().id
        create(f, "Parkir", 3_000)

        val updated = runSuspend { service(f).update(id, "Kopi susu", rupiah(18_000)) }.value()

        assertEquals("Kopi susu", updated.name)
        assertEquals(rupiah(18_000), updated.amount)
        assertEquals(f.room("Keluarga").id, updated.roomId)
        assertEquals(LedgerError.NAME_TAKEN, runSuspend { service(f).update(id, "parkir", rupiah(1_000)) }.error())
        assertIs<LedgerResult.Success<*>>(runSuspend { service(f).update(id, "Kopi susu", rupiah(20_000)) })
        assertEquals(LedgerError.FAVORITE_NOT_FOUND, runSuspend { service(f).update("tidak-ada", "A", rupiah(1)) }.error())
    }

    @Test
    fun `menghapus favorit tidak menyentuh transaksi yang pernah dicatat lewat favorit itu`() {
        val f = LedgerFixture().standard()
        val id = create(f, "Kopi").value().id
        runSuspend { service(f).use(id, LocalDate.of(2026, 9, 21)) }

        runSuspend { service(f).delete(id) }

        assertTrue(runSuspend { service(f).list() }.isEmpty())
        assertEquals(1, f.store.transactionRows.size)
    }

    @Test
    fun `memakai favorit mencatat pengeluaran hari itu dengan nama sebagai catatan`() {
        val f = LedgerFixture().standard()
        val id = create(f, "Kopi", 15_000).value().id

        val use = runSuspend { service(f).use(id, LocalDate.of(2026, 9, 21)) }.value()

        val tx = use.transaction
        assertEquals(TransactionKind.EXPENSE, tx.kind)
        assertEquals(rupiah(15_000), tx.amount)
        assertEquals("Kopi", tx.note)
        assertEquals(LocalDate.of(2026, 9, 21), tx.occurredOn)
        assertEquals(f.room("Keluarga").id, tx.roomId)
        assertEquals(rupiah(485_000), f.balance())
    }

    @Test
    fun `memakai favorit menambah hitungan dan yang sering dipakai naik ke atas`() {
        val f = LedgerFixture().standard()
        create(f, "Kopi")
        val parkir = create(f, "Parkir", 3_000).value().id

        f.now = 5_000
        runSuspend { service(f).use(parkir, LocalDate.of(2026, 9, 21)) }
        runSuspend { service(f).use(parkir, LocalDate.of(2026, 9, 21)) }

        val daftar = runSuspend { service(f).list() }
        assertEquals(listOf("Parkir", "Kopi"), daftar.map { it.favorite.name })
        assertEquals(2, daftar.first().favorite.useCount)
        assertEquals(5_000L, daftar.first().favorite.lastUsedAtMillis)
    }

    @Test
    fun `urungkan menghapus transaksi dan mengembalikan hitungan pemakaian`() {
        val f = LedgerFixture().standard()
        val id = create(f, "Kopi").value().id
        val use = runSuspend { service(f).use(id, LocalDate.of(2026, 9, 21)) }.value()

        runSuspend { service(f).undoUse(use) }

        assertTrue(f.store.transactionRows.isEmpty())
        val favorit = runSuspend { f.store.find(id) }
        assertEquals(0, favorit?.useCount)
        assertNull(favorit?.lastUsedAtMillis)
        assertEquals(rupiah(500_000), f.balance())
    }

    @Test
    fun `favorit yang tujuannya terarsip ditandai tidak bisa dipakai dan pemakaiannya ditolak`() {
        val f = LedgerFixture().standard()
        val id = create(f, "Kopi").value().id
        runSuspend { ManagementService(f.store, f.store, f.newId).archiveCategory(f.category("Keluarga", "Lain-lain").id) }

        val baris = runSuspend { service(f).list() }.single()
        assertFalse(baris.usable)
        assertEquals("Lain-lain", baris.categoryName)
        assertIs<LedgerResult.Failure>(runSuspend { service(f).use(id, LocalDate.of(2026, 9, 21)) })
        assertTrue(f.store.transactionRows.isEmpty())
        assertEquals(0, runSuspend { f.store.find(id) }?.useCount)
    }

    @Test
    fun `favorit yang tidak ada tidak bisa dipakai`() {
        val f = LedgerFixture().standard()

        assertEquals(LedgerError.FAVORITE_NOT_FOUND, runSuspend { service(f).use("tidak-ada", LocalDate.of(2026, 9, 21)) }.error())
    }
}
