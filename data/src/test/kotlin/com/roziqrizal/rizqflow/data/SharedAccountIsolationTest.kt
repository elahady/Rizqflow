package com.roziqrizal.rizqflow.data

import com.roziqrizal.rizqflow.data.db.AccountDao
import com.roziqrizal.rizqflow.data.sync.AccountSyncHandler
import com.roziqrizal.rizqflow.domain.ledger.Account
import com.roziqrizal.rizqflow.domain.ledger.CsvExporter
import com.roziqrizal.rizqflow.domain.ledger.CsvImportPreviewBuilder
import com.roziqrizal.rizqflow.domain.ledger.ImportKind
import com.roziqrizal.rizqflow.domain.ledger.ImportRow
import com.roziqrizal.rizqflow.domain.ledger.LedgerError
import com.roziqrizal.rizqflow.domain.ledger.LedgerResult
import com.roziqrizal.rizqflow.domain.ledger.ListFilter
import com.roziqrizal.rizqflow.domain.ledger.ManagementService
import com.roziqrizal.rizqflow.domain.ledger.MoneyTransaction
import com.roziqrizal.rizqflow.domain.ledger.NewExpense
import com.roziqrizal.rizqflow.domain.ledger.NewAccount
import com.roziqrizal.rizqflow.domain.ledger.TransactionLister
import com.roziqrizal.rizqflow.domain.model.AccountKind
import com.roziqrizal.rizqflow.domain.model.TransactionId
import com.roziqrizal.rizqflow.domain.model.TransactionKind
import com.roziqrizal.rizqflow.domain.model.TransactionOrigin
import com.roziqrizal.rizqflow.domain.money.Money
import com.roziqrizal.rizqflow.domain.sync.SyncRecordType
import com.roziqrizal.rizqflow.domain.sync.SyncScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.YearMonth
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Akun anggota virtual (`account.shared_from` terisi, Tahap 2 sync-desain.md bagian E) hanyalah jangkar kunci asing untuk
 * pengeluaran anggota lain di ruang bersama. Ia TIDAK boleh bocor ke daftar akun pengguna, saldo, pemilih akun, ekspor
 * dan impor CSV, maupun sync pribadi; dan jalur simpan biasa tidak boleh menghapus penandanya.
 *
 * Tes pertama memaksa SETIAP pembaca akun di [AccountDao] punya klasifikasi eksplisit: pembaca baru yang tidak
 * diklasifikasikan menggagalkan tes ini, jadi tidak ada query akun baru yang lolos tanpa keputusan "menyaring atau tidak".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedAccountIsolationTest {
    private lateinit var fx: SyncFixture

    @Before
    fun setUp() {
        fx = SyncFixture()
    }

    @After
    fun tearDown() = fx.close()

    private suspend fun virtualWithSharedExpense(author: String = "ayu", name: String = "Dompet"): Pair<Account, MoneyTransaction> {
        val room = fx.local.rooms.activeRooms().first()
        val category = fx.local.rooms.categories(room.id).first()
        val virtual = fx.local.accounts.findOrCreateVirtualMember(room.id, author, name)
        val tx = MoneyTransaction(
            id = TransactionId("shared-$author-$name"),
            kind = TransactionKind.EXPENSE,
            amount = Money.rupiah(40_000),
            accountId = virtual.id,
            roomId = room.id,
            categoryId = category.id,
            occurredOn = fx.today,
            note = "dari $author",
            origin = TransactionOrigin.SHARED,
            createdAtMillis = 1,
            updatedAtMillis = 1,
        )
        fx.local.transactions.save(tx)
        return virtual to tx
    }

    private enum class Reader { FILTERED, BY_ID_OR_EXPLICIT, WRITE }

    @Test
    fun `setiap metode AccountDao punya klasifikasi pembaca yang eksplisit`() {
        // Pembaca daftar harus menyaring shared_from IS NULL; sisanya harus punya alasan tertulis di sini.
        val classification = mapOf(
            "active" to Reader.FILTERED,
            "all" to Reader.FILTERED,
            "count" to Reader.FILTERED,
            "find" to Reader.BY_ID_OR_EXPLICIT, // dipakai sync dan kunci asing; tidak pernah menjadi daftar untuk pengguna
            "balance" to Reader.BY_ID_OR_EXPLICIT, // per id
            "allIncludingMembers" to Reader.BY_ID_OR_EXPLICIT, // sengaja tidak menyaring: hanya untuk mencari nama akun sumber baris SHARED
            "upsert" to Reader.WRITE,
            "upsertAll" to Reader.WRITE,
            "deleteAll" to Reader.WRITE,
            "delete" to Reader.WRITE,
            "inUse" to Reader.BY_ID_OR_EXPLICIT, // pemeriksaan rujukan per id sebelum hapus
        )
        val declared = AccountDao::class.java.declaredMethods.map { it.name }.toSet()
        val unclassified = declared - classification.keys
        assertTrue(unclassified.isEmpty(), "Metode AccountDao tanpa klasifikasi (menyaring shared_from atau tidak?): $unclassified")
        val missing = classification.keys - declared
        assertTrue(missing.isEmpty(), "Klasifikasi untuk metode AccountDao yang tidak ada lagi: $missing")
    }

    @Test
    fun `pembaca daftar akun repositori tidak menampilkan akun virtual`() = runBlocking<Unit> {
        val (virtual, _) = virtualWithSharedExpense()

        assertFalse(fx.local.accounts.activeAccounts().any { it.id == virtual.id }, "activeAccounts")
        assertFalse(fx.local.accounts.allAccounts().any { it.id == virtual.id }, "allAccounts")
        assertEquals(1, fx.db.accounts().count(), "hitungan akun hanya akun nyata (Dompet)")
        assertFalse(fx.db.accounts().all().any { it.id == virtual.id.value }, "AccountDao.all")
        assertFalse(fx.db.accounts().active().any { it.id == virtual.id.value }, "AccountDao.active")

        // Pencarian per id dan daftar nama sumber tetap menemukannya: sync dan daftar transaksi butuh namanya.
        assertNotNull(fx.local.accounts.find(virtual.id))
        assertTrue(fx.local.accounts.allAccountsIncludingMembers().any { it.id == virtual.id })
        assertEquals(virtual.id, fx.local.accounts.allAccountsIncludingMembers().first { it.sharedFrom != null }.id)
    }

    @Test
    fun `menyimpan ulang akun virtual lewat save mempertahankan shared_from dan tidak menandainya kotor untuk sync pribadi`() = runBlocking<Unit> {
        val (virtual, _) = virtualWithSharedExpense()
        fx.clearSync()

        fx.local.accounts.save(virtual.copy(name = "Dompet (ayu) ganti"))

        val reloaded = assertNotNull(fx.local.accounts.find(virtual.id))
        assertEquals(virtual.sharedFrom, reloaded.sharedFrom, "penanda shared_from tidak boleh hilang")
        assertEquals("Dompet (ayu) ganti", reloaded.name)
        assertFalse(fx.local.accounts.activeAccounts().any { it.id == virtual.id }, "tetap tersembunyi setelah disimpan ulang")
        assertEquals(null, fx.row(SyncRecordType.ACCOUNT, virtual.id.value), "akun virtual tidak pernah masuk sync_record pribadi")
    }

    @Test
    fun `akun virtual tidak ikut backfill dan tidak pernah keluar dari penangan sync akun pribadi`() = runBlocking<Unit> {
        val (virtual, _) = virtualWithSharedExpense()
        fx.clearSync()

        fx.local.syncBootstrap.markAllDirty()
        val handler = AccountSyncHandler(fx.db, fx.local.syncStore)
        val collected = handler.collectDirty(SyncScope.Personal).map { it.recordId }

        assertFalse(virtual.id.value in fx.ids(SyncRecordType.ACCOUNT), "backfill melewati akun virtual")
        assertFalse(virtual.id.value in collected)

        // Pertahanan berlapis: walau ada baris sync_record kotor untuk akun virtual (mis. data lama), penangan tidak mengirimnya.
        fx.db.openHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO sync_record (scope, record_type, record_id, changed_at, deleted, dirty, pushed_changed_at, author, device_id) " +
                "VALUES ('a', 'account', '${virtual.id.value}', 5, 0, 1, NULL, NULL, NULL)",
        )
        assertFalse(virtual.id.value in handler.collectDirty(SyncScope.Personal).map { it.recordId })
    }

    @Test
    fun `baris SHARED tidak menggeser saldo akun nyata`() = runBlocking<Unit> {
        val real = fx.accountId()
        val before = fx.local.accounts.balance(real)

        val (virtual, _) = virtualWithSharedExpense()

        assertEquals(before, fx.local.accounts.balance(real))
        assertEquals(Money.rupiah(-40_000), fx.local.accounts.balance(virtual.id), "saldo virtual terpisah dan tak pernah ditampilkan")
    }

    @Test
    fun `layanan kelola akun tidak menampilkan akun virtual dan tidak menghitungnya sebagai nama terpakai`() = runBlocking<Unit> {
        val (virtual, _) = virtualWithSharedExpense()
        val management = ManagementService(fx.local.accounts, fx.local.rooms, fx.newId)

        val overview = management.accountOverview()
        assertFalse((overview.active + overview.archived).any { it.account.id == virtual.id })

        // Nama tampilan akun virtual ("Dompet (ayu)") tidak mengunci nama akun pengguna.
        val added = management.addAccount(NewAccount("Dompet (ayu)", AccountKind.CASH, Money.rupiah(0)))
        assertIs<LedgerResult.Success<*>>(added)
    }

    @Test
    fun `ekspor CSV pribadi melewati baris SHARED dan impor CSV tidak mencocokkan nama akun virtual`() = runBlocking<Unit> {
        val (virtual, shared) = virtualWithSharedExpense()
        fx.expense()
        val transactions = fx.local.transactions.between(java.time.LocalDate.MIN, java.time.LocalDate.MAX)
        assertTrue(transactions.any { it.id == shared.id }, "baris SHARED memang ada di database")

        val csv = CsvExporter.export(transactions, fx.local.accounts.allAccounts(), fx.local.rooms.allRooms(), fx.local.rooms.allCategories())
        assertFalse("dari ayu" in csv, "baris SHARED tidak ikut diekspor")
        assertEquals(2, csv.lines().size, "hanya baris pribadi yang diekspor (header + 1)")

        val builder = CsvImportPreviewBuilder(fx.local.accounts.allAccounts(), fx.local.rooms.allCategories())
        val row = ImportRow(
            lineNumber = 2,
            date = fx.today,
            amount = Money.rupiah(1_000),
            kind = ImportKind.EXPENSE,
            accountName = virtual.name,
            categoryName = fx.local.rooms.allCategories().first().name,
            note = null,
        )
        val preview = builder.build(listOf(row))
        assertEquals(1, preview.skipped.size, "nama akun virtual tidak boleh menjadi akun tujuan impor")
        assertTrue(preview.ready.isEmpty())
    }

    @Test
    fun `daftar transaksi tetap menampilkan nama akun sumber baris SHARED`() = runBlocking<Unit> {
        val (virtual, shared) = virtualWithSharedExpense()
        val lister = TransactionLister(fx.local.accounts, fx.local.rooms, fx.local.transactions)

        val rows = lister.list(ListFilter(month = YearMonth.from(fx.today))).groups.flatMap { it.rows }
        val sharedRow = rows.first { it.transaction.id == shared.id }

        assertEquals(virtual.name, sharedRow.accountName)
    }

    @Test
    fun `mencatat transaksi pribadi ke akun virtual ditolak`() = runBlocking<Unit> {
        val (virtual, _) = virtualWithSharedExpense()
        val room = fx.local.rooms.activeRooms().first()
        val category = fx.local.rooms.categories(room.id).first()

        val result = fx.ledger.recordExpense(NewExpense(Money.rupiah(1_000), virtual.id, room.id, category.id, fx.today))

        assertEquals(LedgerResult.Failure(LedgerError.ACCOUNT_NOT_FOUND), result)
    }
}
