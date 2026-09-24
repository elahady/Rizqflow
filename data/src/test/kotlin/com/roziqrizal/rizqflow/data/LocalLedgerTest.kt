package com.roziqrizal.rizqflow.data

import android.content.Context
import androidx.room.Room as AndroidRoom
import androidx.test.core.app.ApplicationProvider
import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.data.repo.toEntity
import com.roziqrizal.rizqflow.domain.allocation.AllocationRule
import com.roziqrizal.rizqflow.domain.allocation.BasisPoints
import com.roziqrizal.rizqflow.domain.entitlement.PlanEntitlements
import com.roziqrizal.rizqflow.domain.ledger.Account
import com.roziqrizal.rizqflow.domain.ledger.AllocationEntry
import com.roziqrizal.rizqflow.domain.ledger.CatatContextLoader
import com.roziqrizal.rizqflow.domain.ledger.CatatDraft
import com.roziqrizal.rizqflow.domain.ledger.FavoriteService
import com.roziqrizal.rizqflow.domain.ledger.LedgerError
import com.roziqrizal.rizqflow.domain.ledger.ManagementService
import com.roziqrizal.rizqflow.domain.ledger.NewAccount
import com.roziqrizal.rizqflow.domain.ledger.QuickFavorite
import com.roziqrizal.rizqflow.domain.ledger.Category
import com.roziqrizal.rizqflow.domain.ledger.FirstAccount
import com.roziqrizal.rizqflow.domain.ledger.IncomeReceipt
import com.roziqrizal.rizqflow.domain.ledger.LedgerResult
import com.roziqrizal.rizqflow.domain.ledger.LedgerService
import com.roziqrizal.rizqflow.domain.ledger.ListFilter
import com.roziqrizal.rizqflow.domain.ledger.MoneyTransaction
import com.roziqrizal.rizqflow.domain.ledger.NewExpense
import com.roziqrizal.rizqflow.domain.ledger.NewIncome
import com.roziqrizal.rizqflow.domain.ledger.NewRoom
import com.roziqrizal.rizqflow.domain.ledger.NewTransfer
import com.roziqrizal.rizqflow.domain.ledger.OneTimeSplit
import com.roziqrizal.rizqflow.domain.ledger.Room
import com.roziqrizal.rizqflow.domain.ledger.RoomTemplate
import com.roziqrizal.rizqflow.domain.ledger.RuleDraft
import com.roziqrizal.rizqflow.domain.ledger.RuleService
import com.roziqrizal.rizqflow.domain.ledger.TransactionLister
import com.roziqrizal.rizqflow.domain.ledger.WorkspaceSetup
import com.roziqrizal.rizqflow.domain.model.AccountId
import com.roziqrizal.rizqflow.domain.model.AccountKind
import com.roziqrizal.rizqflow.domain.model.CategoryId
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.model.RoomKind
import com.roziqrizal.rizqflow.domain.model.TransactionId
import com.roziqrizal.rizqflow.domain.model.TransactionKind
import com.roziqrizal.rizqflow.domain.money.Currency
import com.roziqrizal.rizqflow.domain.money.Money
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Menguji lapisan data terhadap Room sungguhan (SQLite di JVM lewat Robolectric): kueri, kunci
 * asing, transaksi, dan pemetaan. Layanan domain dipakai apa adanya di atas repositori Room,
 * sehingga yang diuji adalah alur nyata yang akan dipakai layar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalLedgerTest {

    private lateinit var db: RizqflowDatabase
    private lateinit var local: LocalLedger
    private lateinit var setup: WorkspaceSetup
    private lateinit var service: LedgerService
    private lateinit var rules: RuleService

    private var counter = 0
    private val newId: () -> String = { "id-${++counter}" }
    private var now = 1_000L
    private val day = LocalDate.of(2026, 9, 21)

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = AndroidRoom.inMemoryDatabaseBuilder(context, RizqflowDatabase::class.java).allowMainThreadQueries().build()
        local = LocalLedger(db)
        setup = WorkspaceSetup(local.workspace, newId)
        service = LedgerService(local.accounts, local.rooms, local.transactions, newId) { now }
        rules = RuleService(local.rooms, PlanEntitlements(), newId)
    }

    @After
    fun close() = db.close()

    private fun rupiah(n: Long) = Money.rupiah(n)

    private fun standard() = runBlocking {
        val result = setup.setUp(RoomTemplate.TIGA_HAK, FirstAccount("Dompet", AccountKind.CASH, rupiah(500_000)))
        assertIs<LedgerResult.Success<Unit>>(result)
    }

    private fun account(): Account = runBlocking { local.accounts.activeAccounts().first() }

    private fun room(name: String): Room = runBlocking { local.rooms.activeRooms().first { it.name == name } }

    private fun category(roomName: String, name: String): Category =
        runBlocking { local.rooms.categories(room(roomName).id).first { it.name == name } }

    private fun income(amount: Long, on: LocalDate = day, accountId: AccountId = account().id): IncomeReceipt = runBlocking {
        (service.recordIncome(NewIncome(rupiah(amount), accountId, "Gaji", on)) as LedgerResult.Success).value
    }

    private fun entryAmounts(id: TransactionId) = runBlocking { local.transactions.entriesOf(id) }.map { it.amount.minor }

    // ------------------------------------------------------------------ pengaturan awal

    @Test
    fun `ruang kerja kosong sebelum onboarding dan berisi sesudahnya`() = runBlocking {
        assertTrue(local.workspace.isEmpty())
        standard()
        assertTrue(!local.workspace.isEmpty())
    }

    @Test
    fun `onboarding menyimpan ruang aturan kategori dan akun dengan benar`() {
        standard()

        val rooms = runBlocking { local.rooms.activeRooms() }
        assertEquals(listOf("Memberi", "Diri", "Keluarga"), rooms.map { it.name })
        assertEquals(listOf(RoomKind.MENUNAIKAN, RoomKind.MENUMBUHKAN, RoomKind.MENCUKUPI), rooms.map { it.kind })
        assertEquals("percentage", rooms[0].givingMode)
        assertNull(rooms[1].givingMode)

        val rules = runBlocking { local.rooms.rules() }
        assertEquals(listOf(1_000, 3_000, 6_000), rules.map { it.share.value })
        assertEquals(rooms.map { it.id }, rules.map { it.roomId })

        val keluarga = runBlocking { local.rooms.categories(rooms[2].id) }
        assertEquals(listOf("Belanja bulanan", "Listrik dan air", "Sekolah", "Lain-lain", "Tak terlacak"), keluarga.map { it.name })
        assertTrue(keluarga.last().isSystem)

        assertEquals("Dompet", account().name)
        assertEquals(rupiah(500_000), runBlocking { local.accounts.balance(account().id) })
    }

    @Test
    fun `onboarding kedua ditolak dan tidak menggandakan data`() {
        standard()
        val kedua = runBlocking { setup.setUp(RoomTemplate.TIGA_HAK, FirstAccount("Lain", AccountKind.BANK, rupiah(0))) }

        assertIs<LedgerResult.Failure>(kedua)
        assertEquals(3, runBlocking { local.rooms.activeRooms() }.size)
        assertEquals(1, runBlocking { local.accounts.activeAccounts() }.size)
    }

    @Test
    fun `onboarding yang gagal di tengah membatalkan semuanya`() {
        // Kategori menunjuk ruang yang tidak ada: kunci asing menolak, seluruh transaksi database dibatalkan.
        val orphan = Category(CategoryId("c1"), RoomId("tidak-ada"), "Yatim")
        val snapshot = com.roziqrizal.rizqflow.domain.ledger.WorkspaceSnapshot(
            rooms = listOf(Room(RoomId("r1"), "Ruang", RoomKind.MENCUKUPI, "home", 1, 0)),
            categories = listOf(orphan),
            rules = emptyList(),
            accounts = listOf(Account(AccountId("a1"), "Dompet", AccountKind.CASH, rupiah(0))),
        )

        assertFailsWith<Exception> { runBlocking { local.workspace.initialize(snapshot) } }

        assertTrue(runBlocking { local.workspace.isEmpty() }, "ruang dan akun tidak boleh tersisa")
    }

    // ------------------------------------------------------------------ pemasukan

    @Test
    fun `pemasukan tersimpan dengan potret alokasi berurutan menurut prioritas ruang`() {
        standard()

        val receipt = income(1_234_567)

        assertEquals(listOf(123_457L, 370_370L, 740_740L), entryAmounts(receipt.transaction.id))
        val entries = runBlocking { local.transactions.entriesOf(receipt.transaction.id) }
        assertEquals(listOf(1_000, 3_000, 6_000), entries.map { it.share.value })
        assertEquals(rupiah(1_234_567), entries.fold(rupiah(0)) { a, e -> a + e.amount })
        assertEquals(rupiah(1_734_567), runBlocking { local.accounts.balance(account().id) })
    }

    @Test
    fun `nominal sangat kecil menyimpan baris berjumlah nol beserta persentasenya`() {
        standard()
        val receipt = income(1)
        assertEquals(listOf(0L, 0L, 1L), entryAmounts(receipt.transaction.id))
        assertEquals(3, runBlocking { local.transactions.entriesOf(receipt.transaction.id) }.size)
    }

    @Test
    fun `pemasukan yang gagal disimpan tidak meninggalkan transaksi tanpa alokasi`() {
        standard()
        val id = TransactionId("t-gagal")
        val tx = MoneyTransaction(id, TransactionKind.INCOME, rupiah(1_000), account().id, occurredOn = day, createdAtMillis = 1, updatedAtMillis = 1)
        val entry = AllocationEntry("e1", id, RoomId("tidak-ada"), BasisPoints.percent(100), rupiah(1_000), 1)

        assertFailsWith<Exception> { runBlocking { local.transactions.saveIncome(tx, listOf(entry)) } }

        assertNull(runBlocking { local.transactions.find(id) })
    }

    @Test
    fun `tanggal dan waktu kembali persis sama setelah disimpan`() {
        standard()
        now = 1_726_900_000_123L
        val tanggal = listOf(LocalDate.of(1970, 1, 1), LocalDate.of(2026, 2, 28), LocalDate.of(2100, 12, 31))
        val ids = tanggal.map { income(1_000, on = it).transaction.id }

        val kembali = ids.map { runBlocking { local.transactions.find(it) }!! }

        assertEquals(tanggal, kembali.map { it.occurredOn })
        assertTrue(kembali.all { it.createdAtMillis == 1_726_900_000_123L })
    }

    @Test
    fun `sumber catatan dan asal transaksi kembali utuh, termasuk nilai kosong`() {
        standard()
        val penuh = runBlocking {
            (service.recordIncome(NewIncome(rupiah(1_000), account().id, "Freelance", day, "proyek desain")) as LedgerResult.Success).value
        }.transaction
        val kosong = runBlocking { (service.recordIncome(NewIncome(rupiah(1_000), account().id, null, day)) as LedgerResult.Success).value }.transaction

        val a = runBlocking { local.transactions.find(penuh.id) }!!
        val b = runBlocking { local.transactions.find(kosong.id) }!!
        assertEquals("Freelance" to "proyek desain", a.incomeSource to a.note)
        assertNull(b.incomeSource)
        assertNull(b.note)
    }

    // ------------------------------------------------------------------ pengeluaran dan transfer

    @Test
    fun `pengeluaran mengurangi saldo dan tersimpan dengan ruang dan kategorinya`() {
        standard()
        val tx = runBlocking {
            (service.recordExpense(NewExpense(rupiah(25_000), account().id, room("Keluarga").id, category("Keluarga", "Sekolah").id, day, "SPP")) as LedgerResult.Success).value
        }

        val disimpan = runBlocking { local.transactions.find(tx.id) }!!
        assertEquals(room("Keluarga").id, disimpan.roomId)
        assertEquals(category("Keluarga", "Sekolah").id, disimpan.categoryId)
        assertEquals(rupiah(475_000), runBlocking { local.accounts.balance(account().id) })
    }

    @Test
    fun `pengeluaran ke kategori yang tidak ada ditolak kunci asing sebagai jaring pengaman terakhir`() {
        standard()
        val tx = MoneyTransaction(
            TransactionId("t-x"), TransactionKind.EXPENSE, rupiah(1_000), account().id,
            roomId = room("Keluarga").id, categoryId = CategoryId("tidak-ada"), occurredOn = day, createdAtMillis = 1, updatedAtMillis = 1,
        )
        assertFailsWith<Exception> { runBlocking { local.transactions.save(tx) } }
        assertNull(runBlocking { local.transactions.find(tx.id) })
    }

    @Test
    fun `transfer memindahkan saldo dan total kedua akun tetap`() {
        standard()
        val bank = Account(AccountId("bank"), "Bank", AccountKind.BANK, rupiah(200_000), sortOrder = 1)
        runBlocking { local.accounts.save(bank) }

        runBlocking { service.recordTransfer(NewTransfer(rupiah(100_000), account().id, bank.id, day)) }

        assertEquals(rupiah(400_000), runBlocking { local.accounts.balance(account().id) })
        assertEquals(rupiah(300_000), runBlocking { local.accounts.balance(bank.id) })
    }

    // ------------------------------------------------------------------ ubah dan hapus

    @Test
    fun `ubah nominal memakai persentase potret dan menjaga pengenal baris`() {
        standard()
        val receipt = income(1_000_000)
        val idsSebelum = runBlocking { local.transactions.entriesOf(receipt.transaction.id) }.map { it.id }

        val hasil = runBlocking { service.editIncomeAmount(receipt.transaction.id, rupiah(2_000_000)) }

        assertIs<LedgerResult.Success<IncomeReceipt>>(hasil)
        assertEquals(listOf(200_000L, 600_000L, 1_200_000L), entryAmounts(receipt.transaction.id))
        assertEquals(idsSebelum, runBlocking { local.transactions.entriesOf(receipt.transaction.id) }.map { it.id })
        assertEquals(rupiah(2_000_000), runBlocking { local.transactions.find(receipt.transaction.id) }!!.amount)
    }

    @Test
    fun `mengubah aturan tidak mengubah riwayat pemasukan lama`() {
        standard()
        val receipt = income(1_000_000)
        val semua = listOf("Memberi", "Diri", "Keluarga").map { room(it).id }

        runBlocking {
            rules.changeRules(listOf(AllocationRule(semua[0], BasisPoints.percent(50)), AllocationRule(semua[1], BasisPoints.percent(25)), AllocationRule(semua[2], BasisPoints.percent(25))))
        }

        assertEquals(listOf(100_000L, 300_000L, 600_000L), entryAmounts(receipt.transaction.id))
        assertEquals(listOf(500_000L, 250_000L, 250_000L), entryAmounts(income(1_000_000).transaction.id))
    }

    @Test
    fun `menghapus pemasukan ikut menghapus potret alokasinya lewat kunci asing`() {
        standard()
        val receipt = income(1_000_000)

        runBlocking { service.delete(receipt.transaction.id) }

        assertNull(runBlocking { local.transactions.find(receipt.transaction.id) })
        assertTrue(runBlocking { db.transactions().entriesOf(receipt.transaction.id.value) }.isEmpty())
        assertEquals(rupiah(500_000), runBlocking { local.accounts.balance(account().id) })
    }

    @Test
    fun `daftar antara dua hari berurutan terbaru dulu dan hanya dalam rentang`() {
        standard()
        val a = income(1_000, on = LocalDate.of(2026, 9, 10)).transaction
        val c = income(3_000, on = LocalDate.of(2026, 9, 20)).transaction
        now += 1
        val b = income(2_000, on = LocalDate.of(2026, 9, 20)).transaction
        income(4_000, on = LocalDate.of(2026, 9, 30))

        val hasil = runBlocking { local.transactions.between(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 20)) }

        assertEquals(listOf(b.id, c.id, a.id), hasil.map { it.id })
    }

    // ------------------------------------------------------------------ aturan dan ruang

    @Test
    fun `mengganti aturan hanya menyentuh ruang aktif dan mempertahankan persentase ruang terarsip`() {
        standard()
        val diri = room("Diri")
        runBlocking { db.rooms().upsert(diri.copy(archived = true).toEntity()) }

        val hasil = runBlocking {
            rules.changeRules(listOf(AllocationRule(room("Memberi").id, BasisPoints.percent(40)), AllocationRule(room("Keluarga").id, BasisPoints.percent(60))))
        }

        assertIs<LedgerResult.Success<Unit>>(hasil)
        assertEquals(listOf(4_000, 6_000), runBlocking { local.rooms.rules() }.map { it.share.value })
        val tersimpan = db.query("SELECT share_bp FROM allocation_rule WHERE room_id = ?", arrayOf<Any?>(diri.id.value)).use { c ->
            assertTrue(c.moveToFirst(), "aturan ruang terarsip masih ada")
            c.getInt(0)
        }
        assertEquals(3_000, tersimpan)
    }

    @Test
    fun `ruang terarsip tidak muncul di daftar aktif maupun aturan`() {
        standard()
        val diri = room("Diri")
        runBlocking { db.rooms().upsert(diri.copy(archived = true).toEntity()) }

        assertEquals(listOf("Memberi", "Keluarga"), runBlocking { local.rooms.activeRooms() }.map { it.name })
        assertEquals(listOf(1_000, 6_000), runBlocking { local.rooms.rules() }.map { it.share.value })
    }

    @Test
    fun `menambah ruang menyimpan ruang dan kategori awalnya, dan ruang keenam ditolak`() {
        standard()
        val baru = runBlocking { rules.addRoom(NewRoom("Orang tua", RoomKind.MENCUKUPI, "home", 4)) }
        val id = (baru as LedgerResult.Success).value

        assertEquals(listOf("Lain-lain", "Tak terlacak"), runBlocking { local.rooms.categories(id) }.map { it.name })
        assertEquals(4, runBlocking { local.rooms.activeRooms() }.size)
        runBlocking { rules.addRoom(NewRoom("Tabungan haji", RoomKind.MENUMBUHKAN, "sprout", 5)) }
        assertIs<LedgerResult.Failure>(runBlocking { rules.addRoom(NewRoom("Liburan", RoomKind.MENCUKUPI, "home", 6)) })
        assertEquals(5, runBlocking { local.rooms.activeRooms() }.size)
    }

    @Test
    fun `ruang yang gagal disimpan tidak tersisa tanpa kategorinya`() {
        standard()
        val room = Room(RoomId("r-baru"), "Baru", RoomKind.MENCUKUPI, "home", 4, 3)
        val yatim = Category(CategoryId("c-yatim"), RoomId("bukan-r-baru"), "Yatim")

        assertFailsWith<Exception> { runBlocking { local.rooms.addRoom(room, listOf(yatim)) } }

        assertNull(runBlocking { local.rooms.find(room.id) })
    }

    // ------------------------------------------------------------------ akun dan mata uang

    @Test
    fun `tanggal koreksi saldo akun tidak hilang saat akun disimpan ulang`() {
        standard()
        val dicocokkan = account().copy(lastReconciledOn = LocalDate.of(2026, 9, 1))
        runBlocking { local.accounts.save(dicocokkan) }
        runBlocking { local.accounts.save(account().copy(name = "Dompet utama")) }

        assertEquals(LocalDate.of(2026, 9, 1), account().lastReconciledOn)
        assertEquals("Dompet utama", account().name)
    }

    @Test
    fun `saldo akun yang tidak ada dilaporkan sebagai galat, bukan nol`() {
        assertFailsWith<IllegalArgumentException> { runBlocking { local.accounts.balance(AccountId("tidak-ada")) } }
    }

    @Test
    fun `mata uang dan nominal besar kembali persis`() {
        val usd = Account(AccountId("usd"), "Dolar", AccountKind.BANK, Money(1_234, Currency.USD))
        runBlocking { local.accounts.save(usd) }

        val kembali = runBlocking { local.accounts.find(usd.id) }!!
        assertEquals(Money(1_234, Currency.USD), kembali.openingBalance)
        assertEquals(Money(1_234, Currency.USD), runBlocking { local.accounts.balance(usd.id) })

        val besar = Account(AccountId("besar"), "Besar", AccountKind.BANK, Money(Long.MAX_VALUE / 2))
        runBlocking { local.accounts.save(besar) }
        assertEquals(Money(Long.MAX_VALUE / 2), runBlocking { local.accounts.find(besar.id) }!!.openingBalance)
    }

    @Test
    fun `akun terarsip tidak muncul di daftar aktif tetapi tetap bisa ditemukan`() {
        standard()
        val lama = Account(AccountId("lama"), "Lama", AccountKind.CASH, rupiah(0), archived = true, sortOrder = 5)
        runBlocking { local.accounts.save(lama) }

        assertEquals(listOf("Dompet"), runBlocking { local.accounts.activeAccounts() }.map { it.name })
        assertNotNull(runBlocking { local.accounts.find(lama.id) })
    }

    // ------------------------------------------------------------------ Catat: pilihan terakhir, jatah, dan banner

    @Test
    fun `transaksi terakhir dipilih dari yang paling baru, dan per jenis`() {
        standard()
        val pengeluaran = runBlocking {
            (service.recordExpense(NewExpense(rupiah(1_000), account().id, room("Diri").id, category("Diri", "Belajar").id, day)) as LedgerResult.Success).value
        }
        now += 10
        val pemasukan = income(50_000).transaction

        assertEquals(pemasukan.id, runBlocking { local.transactions.latest(null) }!!.id)
        assertEquals(pengeluaran.id, runBlocking { local.transactions.latest(TransactionKind.EXPENSE) }!!.id)
        assertNull(runBlocking { local.transactions.latest(TransactionKind.TRANSFER) })
    }

    @Test
    fun `transaksi terakhir pada database kosong tidak ada`() {
        standard()
        assertNull(runBlocking { local.transactions.latest(null) })
    }

    @Test
    fun `jatah dan terpakai per ruang dihitung per bulan dari database`() {
        standard()
        income(1_000_000, on = LocalDate.of(2026, 9, 5))
        income(2_000_000, on = LocalDate.of(2026, 10, 1))
        runBlocking {
            service.recordExpense(NewExpense(rupiah(250_000), account().id, room("Keluarga").id, category("Keluarga", "Sekolah").id, LocalDate.of(2026, 9, 30)))
            service.recordExpense(NewExpense(rupiah(40_000), account().id, room("Keluarga").id, category("Keluarga", "Sekolah").id, LocalDate.of(2026, 10, 2)))
        }

        val sept = runBlocking { local.transactions.roomTotals(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)) }
        assertEquals(rupiah(600_000), sept.allocated[room("Keluarga").id])
        assertEquals(rupiah(100_000), sept.allocated[room("Memberi").id])
        assertEquals(rupiah(250_000), sept.spent[room("Keluarga").id])
        assertNull(sept.spent[room("Diri").id], "ruang tanpa pengeluaran tidak punya baris")

        val okt = runBlocking { local.transactions.roomTotals(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)) }
        assertEquals(rupiah(1_200_000), okt.allocated[room("Keluarga").id])
        assertEquals(rupiah(40_000), okt.spent[room("Keluarga").id])
    }

    @Test
    fun `banner jatah dan konteks Catat bekerja di atas Room`() {
        standard()
        income(1_000_000)
        val keluarga = room("Keluarga")
        runBlocking {
            service.recordExpense(NewExpense(rupiah(500_000), account().id, keluarga.id, category("Keluarga", "Sekolah").id, day))
        }

        assertNull(runBlocking { service.budgetWarning(keluarga.id, rupiah(100_000), day) })
        assertNotNull(runBlocking { service.budgetWarning(keluarga.id, rupiah(100_001), day) })

        val konteks = runBlocking { CatatContextLoader(local.accounts, local.rooms, local.transactions).load() }
        assertEquals(listOf("Memberi", "Diri", "Keluarga"), konteks.rooms.map { it.name })
        assertEquals(keluarga.id, konteks.lastRoomId)
        assertEquals(category("Keluarga", "Sekolah").id, konteks.lastCategoryId)
        assertEquals(account().id, konteks.lastAccountId)
        assertEquals(listOf(1_000, 3_000, 6_000), konteks.rules.map { it.share.value })
    }

    @Test
    fun `pembagian sekali ini tersimpan sebagai potret di database dan aturan tetap`() {
        standard()
        val split = OneTimeSplit.from(runBlocking { local.rooms.rules() }).set(0, 50).set(1, 30)

        val receipt = runBlocking {
            (service.recordIncome(NewIncome(rupiah(1_000_000), account().id, "Gaji", day, overrideRules = split.toRules())) as LedgerResult.Success).value
        }

        assertEquals(listOf(500_000L, 300_000L, 200_000L), entryAmounts(receipt.transaction.id))
        assertEquals(listOf(1_000, 3_000, 6_000), runBlocking { local.rooms.rules() }.map { it.share.value })
    }

    // ------------------------------------------------------------------ Daftar (S08) dan ubah (S09)

    private fun expense(amount: Long, on: LocalDate = day, roomName: String = "Keluarga", categoryName: String = "Belanja bulanan", note: String? = null, accountId: AccountId = account().id) =
        runBlocking {
            (service.recordExpense(NewExpense(rupiah(amount), accountId, room(roomName).id, category(roomName, categoryName).id, on, note)) as LedgerResult.Success).value
        }

    @Test
    fun `daftar transaksi sebulan dikelompokkan dan disaring dari database`() {
        standard()
        income(8_500_000, on = LocalDate.of(2026, 9, 1))
        expense(150_000, on = LocalDate.of(2026, 9, 18), note = "Belanja pasar")
        expense(200_000, on = LocalDate.of(2026, 9, 18), categoryName = "Listrik dan air", note = "Token listrik")
        expense(9_999, on = LocalDate.of(2026, 10, 2), note = "Oktober")
        val lister = TransactionLister(local.accounts, local.rooms, local.transactions)

        val semua = runBlocking { lister.list(ListFilter(YearMonth.of(2026, 9))) }
        assertEquals(listOf(18, 1), semua.groups.map { it.date.dayOfMonth })
        assertEquals(3, semua.count)
        assertEquals(3, semua.groups.last().rows.single().allocatedRoomCount)

        val keluar = runBlocking { lister.list(ListFilter(YearMonth.of(2026, 9), kinds = setOf(TransactionKind.EXPENSE), query = "listrik")) }
        assertEquals(listOf("Token listrik"), keluar.groups.flatMap { g -> g.rows.map { it.transaction.note } })
        assertEquals("Listrik dan air", keluar.groups.single().rows.single().categoryName)
        assertEquals(1, runBlocking { lister.list(ListFilter(YearMonth.of(2026, 10))) }.count)
    }

    @Test
    fun `semua akun ruang dan kategori termasuk terarsip tersedia untuk riwayat`() {
        standard()
        val lama = Account(AccountId("lama"), "Lama", AccountKind.CASH, rupiah(0), archived = true, sortOrder = 9)
        runBlocking { local.accounts.save(lama) }
        val diri = room("Diri")
        runBlocking { db.rooms().upsert(diri.copy(archived = true).toEntity()) }

        assertEquals(listOf("Dompet", "Lama"), runBlocking { local.accounts.allAccounts() }.map { it.name })
        assertEquals(listOf("Memberi", "Diri", "Keluarga"), runBlocking { local.rooms.allRooms() }.map { it.name })
        assertTrue(runBlocking { local.rooms.allCategories() }.any { it.name == "Investasi" }, "kategori ruang terarsip ikut")
    }

    @Test
    fun `ubah pengeluaran tersimpan di database dan bisa dipulihkan`() {
        standard()
        val tx = expense(150_000, note = "Belanja pasar")
        now = 9_000L

        val before = runBlocking {
            (service.updateTransaction(tx.id, CatatDraft.from(tx).copy(digits = "175000", note = "Belanja")) as LedgerResult.Success).value
        }

        val sekarang = runBlocking { local.transactions.find(tx.id) }!!
        assertEquals(rupiah(175_000), sekarang.amount)
        assertEquals("Belanja", sekarang.note)
        assertEquals(tx.createdAtMillis, sekarang.createdAtMillis)
        assertEquals(9_000L, sekarang.updatedAtMillis)
        assertEquals(rupiah(325_000), runBlocking { local.accounts.balance(account().id) })

        runBlocking { service.restore(before) }
        assertEquals(tx, runBlocking { local.transactions.find(tx.id) })
    }

    @Test
    fun `ubah nominal pemasukan lewat detail menghitung ulang potret di database`() {
        standard()
        val income = income(1_000_000).transaction

        runBlocking { service.updateTransaction(income.id, CatatDraft.from(income).copy(digits = "2000000")) }

        assertEquals(listOf(200_000L, 600_000L, 1_200_000L), entryAmounts(income.id))
        assertEquals(rupiah(2_000_000), runBlocking { local.transactions.find(income.id) }!!.amount)
    }

    @Test
    fun `hapus lalu pulihkan pemasukan mengembalikan transaksi dan potret alokasinya`() {
        standard()
        val income = income(1_000_000).transaction
        val snapshot = runBlocking { service.snapshotOf(income.id) }!!

        runBlocking { service.delete(income.id) }
        assertNull(runBlocking { local.transactions.find(income.id) })
        assertTrue(runBlocking { db.transactions().entriesOf(income.id.value) }.isEmpty())

        runBlocking { service.restore(snapshot) }
        assertEquals(income, runBlocking { local.transactions.find(income.id) })
        assertEquals(listOf(100_000L, 300_000L, 600_000L), entryAmounts(income.id))
    }

    @Test
    fun `konteks ubah memuat akun terarsip yang dipakai transaksi dari database`() {
        standard()
        val bank = Account(AccountId("bank"), "Bank", AccountKind.BANK, rupiah(0), sortOrder = 1)
        runBlocking { local.accounts.save(bank) }
        val tx = expense(1_000, accountId = bank.id)
        runBlocking { local.accounts.save(bank.copy(archived = true)) }

        val biasa = runBlocking { CatatContextLoader(local.accounts, local.rooms, local.transactions).load() }
        val ubah = runBlocking { CatatContextLoader(local.accounts, local.rooms, local.transactions).load(tx) }

        assertTrue(biasa.accounts.none { it.id == bank.id })
        assertTrue(ubah.accounts.any { it.id == bank.id })
    }

    @Test
    fun `banner jatah saat mengubah tidak menghitung pengeluaran lama dua kali`() {
        standard()
        income(1_000_000)
        val tx = expense(500_000)
        val keluarga = room("Keluarga").id

        assertNull(runBlocking { service.budgetWarning(keluarga, rupiah(600_000), day, excluding = tx.id) })
        assertNotNull(runBlocking { service.budgetWarning(keluarga, rupiah(600_000), day) })
    }

    // ------------------------------------------------------------------ Ruang (S10) dan aturan (S12)

    @Test
    fun `mengarsipkan lalu memulihkan ruang bekerja di database dan aturan lama tidak menggelembungkan total`() {
        standard()
        val diri = room("Diri")

        runBlocking { rules.archiveRoom(diri.id) }
        val terarsip = runBlocking { rules.overview() }
        assertEquals(listOf("Memberi", "Keluarga"), terarsip.active.map { it.room.name })
        assertEquals(listOf("Diri"), terarsip.archived.map { it.name })
        assertEquals(7_000, terarsip.totalBp)

        runBlocking { rules.changeRules(listOf(AllocationRule(room("Memberi").id, BasisPoints.percent(20)), AllocationRule(room("Keluarga").id, BasisPoints.percent(80)))) }
        assertIs<LedgerResult.Success<Unit>>(runBlocking { rules.restoreRoom(diri.id) })

        val pulih = runBlocking { rules.overview() }
        assertEquals(listOf("Memberi", "Keluarga", "Diri"), pulih.active.map { it.room.name })
        assertEquals(listOf(2_000, 8_000, 0), pulih.active.map { it.shareBp })
        assertTrue(pulih.isBalanced)
        val tersimpan = db.query("SELECT COUNT(*) FROM allocation_rule WHERE room_id = ?", arrayOf<Any?>(diri.id.value)).use { c -> c.moveToFirst(); c.getInt(0) }
        assertEquals(1, tersimpan, "hanya satu baris aturan untuk ruang yang dipulihkan")
    }

    @Test
    fun `menggeser ruang menyimpan urutan baru dan aturan mengikutinya`() {
        standard()

        runBlocking { rules.moveRoom(room("Keluarga").id, -1) }
        runBlocking { rules.moveRoom(room("Keluarga").id, -1) }

        assertEquals(listOf("Keluarga", "Memberi", "Diri"), runBlocking { local.rooms.activeRooms() }.map { it.name })
        assertEquals(listOf(6_000, 1_000, 3_000), runBlocking { local.rooms.rules() }.map { it.share.value })
    }

    @Test
    fun `memakai pola Tiga hak pada akun tanpa ruang menyimpan ruang kategori dan aturan sekaligus`() {
        runBlocking { setup.setUp(RoomTemplate.KOSONG, FirstAccount("Dompet", AccountKind.CASH, rupiah(0))) }
        assertTrue(runBlocking { local.rooms.activeRooms() }.isEmpty())

        assertIs<LedgerResult.Success<Unit>>(runBlocking { rules.applyTemplate(RoomTemplate.TIGA_HAK) })

        assertEquals(listOf("Memberi", "Diri", "Keluarga"), runBlocking { local.rooms.activeRooms() }.map { it.name })
        assertEquals(listOf(1_000, 3_000, 6_000), runBlocking { local.rooms.rules() }.map { it.share.value })
        assertTrue(runBlocking { local.rooms.allCategories() }.any { it.name == "Zakat mal" && it.isSystem })
    }

    @Test
    fun `penyimpanan banyak ruang yang gagal membatalkan semuanya`() {
        val ruang = Room(RoomId("r1"), "Baru", RoomKind.MENCUKUPI, "home", 4, 0)
        val yatim = Category(CategoryId("c1"), RoomId("bukan-r1"), "Yatim")

        assertFailsWith<Exception> { runBlocking { local.rooms.addRooms(listOf(ruang), listOf(yatim), emptyList()) } }

        assertNull(runBlocking { local.rooms.find(ruang.id) })
    }

    @Test
    fun `draf aturan dari database dan simpan lewat layanan lalu urungkan`() {
        standard()
        val asal = runBlocking { RuleDraft.from(local.rooms.activeRooms(), local.rooms.rules()) }
        val ubah = asal.step(0, 5).step(2, -5)

        assertIs<LedgerResult.Success<Unit>>(runBlocking { rules.changeRules(ubah.toRules()) })
        assertEquals(listOf(1_500, 3_000, 5_500), runBlocking { local.rooms.rules() }.map { it.share.value })

        runBlocking { rules.restoreRules(asal.toRules()) }
        assertEquals(listOf(1_000, 3_000, 6_000), runBlocking { local.rooms.rules() }.map { it.share.value })
    }

    // ------------------------------------------------------------------ Favorit dan kelola akun/kategori (S13, S24)

    private fun favorites() = FavoriteService(local.favorites, local.accounts, local.rooms, service, newId) { now }

    private fun makeFavorite(name: String, amount: Long): QuickFavorite = runBlocking {
        val result = favorites().create(name, rupiah(amount), room("Keluarga").id, category("Keluarga", "Lain-lain").id, account().id)
        (result as LedgerResult.Success).value
    }

    @Test
    fun `favorit tersimpan di Room dan urut menurut pemakaian lalu nama`() {
        standard()
        makeFavorite("Kopi", 15_000)
        val parkir = makeFavorite("Parkir", 3_000)
        makeFavorite("Bensin", 30_000)

        now = 9_000
        runBlocking { favorites().use(parkir.id, day) }

        val daftar = runBlocking { local.favorites.all() }
        assertEquals(listOf("Parkir", "Bensin", "Kopi"), daftar.map { it.name })
        assertEquals(1, daftar.first().useCount)
        assertEquals(9_000L, daftar.first().lastUsedAtMillis)
        assertEquals(rupiah(3_000), daftar.first().amount)
    }

    @Test
    fun `memakai lalu mengurungkan favorit mengembalikan saldo dan hitungan di database`() {
        standard()
        val kopi = makeFavorite("Kopi", 15_000)

        val use = runBlocking { (favorites().use(kopi.id, day) as LedgerResult.Success).value }
        assertEquals(rupiah(485_000), runBlocking { local.accounts.balance(account().id) })
        assertEquals("Kopi", runBlocking { local.transactions.find(use.transaction.id) }?.note)

        runBlocking { favorites().undoUse(use) }

        assertEquals(rupiah(500_000), runBlocking { local.accounts.balance(account().id) })
        assertNull(runBlocking { local.transactions.find(use.transaction.id) })
        assertEquals(0, runBlocking { local.favorites.find(kopi.id) }?.useCount)
    }

    @Test
    fun `arsip ruang tidak menghapus favorit tetapi menandainya tidak bisa dipakai`() {
        standard()
        val kopi = makeFavorite("Kopi", 15_000)

        runBlocking { rules.archiveRoom(room("Keluarga").id) }

        val baris = runBlocking { favorites().list() }.single()
        assertEquals(kopi.id, baris.favorite.id)
        assertTrue(!baris.usable)
        runBlocking { favorites().delete(kopi.id) }
        assertNull(runBlocking { local.favorites.find(kopi.id) })
    }

    @Test
    fun `kelola kategori di Room menyimpan tambah ubah nama dan arsip`() {
        standard()
        val management = ManagementService(local.accounts, local.rooms, newId)
        val keluarga = room("Keluarga").id

        val baru = runBlocking { (management.addCategory(keluarga, "Transportasi") as LedgerResult.Success).value }
        runBlocking { management.renameCategory(baru, "Ojek") }
        runBlocking { management.archiveCategory(category("Keluarga", "Sekolah").id) }

        val aktif = runBlocking { local.rooms.categories(keluarga) }.map { it.name }
        assertTrue("Ojek" in aktif)
        assertTrue("Sekolah" !in aktif)
        val semua = runBlocking { local.rooms.allCategories() }.filter { it.roomId == keluarga }
        assertTrue(semua.any { it.name == "Sekolah" && it.archived })
    }

    @Test
    fun `kelola akun di Room menghitung saldo dan menerima lebih dari tiga akun aktif`() {
        standard()
        income(1_000_000)
        val management = ManagementService(local.accounts, local.rooms, newId)
        runBlocking { management.addAccount(NewAccount("Bank", AccountKind.BANK, rupiah(250_000))) }
        runBlocking { management.addAccount(NewAccount("GoPay", AccountKind.EWALLET, rupiah(0))) }

        val hasil = runBlocking { management.addAccount(NewAccount("Cadangan", AccountKind.CASH, rupiah(0))) }
        assertTrue(hasil is LedgerResult.Success)

        val overview = runBlocking { management.accountOverview() }
        assertEquals(listOf("Dompet", "Bank", "GoPay", "Cadangan"), overview.active.map { it.account.name })
        assertEquals(listOf(1_500_000L, 250_000L, 0L, 0L), overview.active.map { it.balance.minor })

        runBlocking { management.archiveAccount(overview.active[1].account.id) }
        assertEquals(listOf("Bank"), runBlocking { management.accountOverview() }.archived.map { it.account.name })
    }
}
