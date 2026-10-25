package com.roziqrizal.rizqflow.data

import com.roziqrizal.rizqflow.data.sync.SyncBootstrap
import com.roziqrizal.rizqflow.domain.allocation.AllocationMode
import com.roziqrizal.rizqflow.domain.sync.ApplyDecision
import com.roziqrizal.rizqflow.domain.sync.IncomingVersion
import com.roziqrizal.rizqflow.domain.model.GoldPriceSource
import com.roziqrizal.rizqflow.domain.model.TransactionOrigin
import com.roziqrizal.rizqflow.domain.money.Money
import com.roziqrizal.rizqflow.domain.sync.SyncRecordIds
import com.roziqrizal.rizqflow.domain.sync.SyncRecordType
import com.roziqrizal.rizqflow.domain.zakat.GoldPrice
import com.roziqrizal.rizqflow.domain.zakat.ZakatProfile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Backfill sekali saat sync pertama diaktifkan ([com.roziqrizal.rizqflow.data.sync.SyncBootstrap]). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncBootstrapTest {
    private lateinit var fx: SyncFixture

    @Before
    fun setUp() {
        fx = SyncFixture(nowMillis = 7_000_000L)
    }

    @After
    fun tearDown() = fx.close()

    private fun identities() = fx.rows().map { it.type to it.id }.toSet()

    @Test
    fun `markAllDirty menandai data pra-migrasi yang belum punya baris, sama dengan yang dicatat recorder`() = runBlocking {
        val expense = fx.expense()
        fx.income()
        fx.local.zakat.saveProfile(ZakatProfile("zp-1", "Utama"))
        fx.local.rooms.setAllocationMode(AllocationMode.WATERFALL)
        val recorded = identities()

        // Seolah database ini dibuat sebelum migrasi 8: tidak ada jejak recorder sama sekali.
        fx.clearSync()
        assertTrue(fx.rows().isEmpty())

        val inserted = fx.local.syncBootstrap.markAllDirty()

        assertEquals(recorded, identities(), "backfill menjangkau persis himpunan yang dicatat recorder")
        assertEquals(recorded.size, inserted)
        assertTrue(fx.rows().all { it.dirty && !it.deleted && it.scope == "a" && it.deviceId == null })
        assertTrue(fx.ids(SyncRecordType.TRANSACTION).contains(expense.value))
    }

    @Test
    fun `markAllDirty idempoten dan tidak menimpa rekaman yang sudah punya baris`() = runBlocking {
        val id = fx.expense()
        fx.clearSync()
        fx.local.syncBootstrap.markAllDirty()
        val first = fx.rows()
        assertEquals(0, fx.local.syncBootstrap.markAllDirty(), "pemanggilan kedua tidak menambah apa-apa")
        assertEquals(first, fx.rows())

        // Rekaman yang sudah terkirim (bersih) tidak dikotori ulang oleh backfill susulan.
        fx.markAllClean()
        assertEquals(0, fx.local.syncBootstrap.markAllDirty())
        assertTrue(fx.rows().none { it.dirty })

        // Rekaman yang diubah sejak migrasi mempertahankan capnya; hanya yang belum punya baris ditambah.
        fx.clearSync()
        val stored = fx.local.transactions.find(id)!!
        fx.local.transactions.update(stored.copy(note = "diubah"))
        val edited = fx.row(SyncRecordType.TRANSACTION, id.value)!!
        fx.nowMillis = 99_999_999
        fx.local.syncBootstrap.markAllDirty()
        assertEquals(edited, fx.row(SyncRecordType.TRANSACTION, id.value))
        assertTrue(fx.rows().size > 1, "rekaman lain ikut ditandai")
    }

    @Test
    fun `changed_at transaksi memakai updated_at entitas, lainnya memakai cap minimal`() = runBlocking {
        val id = fx.expense()
        val stored = fx.local.transactions.find(id)!!
        fx.local.transactions.update(stored.copy(updatedAtMillis = 123_456L))
        fx.clearSync()

        fx.local.syncBootstrap.markAllDirty()

        assertEquals(123_456L, fx.row(SyncRecordType.TRANSACTION, id.value)!!.changedAt)
        // Tanpa cap bawaan: cap minimal, BUKAN jam sekarang (jam fixture 7_000_000).
        for (row in fx.rows().filter { it.type != SyncRecordType.TRANSACTION.wire }) {
            assertEquals(SyncBootstrap.BACKFILL_STAMP, row.changedAt, "${row.type}:${row.id} harus bercap minimal")
        }
    }

    @Test
    fun `hasil backfill kalah dari versi nyata yang masuk lalu edit berikutnya tetap naik`() = runBlocking {
        // Klien basi: data lama (mis. backup lama) hasil backfill bercap minimal.
        val account = fx.local.accounts.activeAccounts().first()
        fx.clearSync()
        fx.local.syncBootstrap.markAllDirty()
        val stale = fx.row(SyncRecordType.ACCOUNT, account.id.value)!!
        assertEquals(SyncBootstrap.BACKFILL_STAMP, stale.changedAt)
        assertTrue(stale.dirty)

        // Versi yang lebih baru (cap dari jam beberapa hari lalu pun) dari klien lain masuk lewat applyIncoming: menang.
        val newer = IncomingVersion(changedAtMillis = 1_700_000_000_000L, deleted = false, deviceId = "dev-b")
        var applied = 0
        val decision = fx.local.syncStore.applyIncoming(fx.personal, SyncRecordType.ACCOUNT, account.id.value, newer, "dev-a") { applied++ }
        assertEquals(ApplyDecision.APPLIED_OVER_LOCAL_EDIT, decision)
        assertEquals(1, applied)
        assertEquals(newer.changedAtMillis, fx.row(SyncRecordType.ACCOUNT, account.id.value)!!.changedAt)

        // Sebaliknya: versi nyata lokal (cap dari jam) tidak dikalahkan oleh salinan basi bercap minimal dari klien lain.
        fx.local.accounts.save(account.copy(name = "Edit nyata"))
        val real = fx.row(SyncRecordType.ACCOUNT, account.id.value)!!
        assertTrue(real.changedAt >= 7_000_000L, "edit nyata bercap jam: ${real.changedAt}")
        val staleIncoming = IncomingVersion(changedAtMillis = SyncBootstrap.BACKFILL_STAMP, deleted = false, deviceId = "dev-z")
        assertEquals(ApplyDecision.IGNORED, fx.local.syncStore.applyIncoming(fx.personal, SyncRecordType.ACCOUNT, account.id.value, staleIncoming, "dev-a") { error("tidak boleh diterapkan") })
    }

    @Test
    fun `edit sesudah backfill memakai cap jam dan lantai satu lebih besar tetap konsisten`() = runBlocking {
        val id = fx.expense()
        fx.clearSync()
        fx.local.syncBootstrap.markAllDirty()
        val accountId = fx.accountId().value
        assertEquals(1L, fx.row(SyncRecordType.ACCOUNT, accountId)!!.changedAt)
        fx.local.accounts.save(fx.local.accounts.activeAccounts().first().copy(name = "Setelah backfill"))
        val after = fx.row(SyncRecordType.ACCOUNT, accountId)!!
        assertTrue(after.changedAt >= fx.nowMillis, "max(jam, 1 + 1) = jam, bukan 2: ${after.changedAt}")
        assertTrue(fx.row(SyncRecordType.TRANSACTION, id.value)!!.changedAt > 1L)
    }

    @Test
    fun `yang dilewati backfill harga emas AUTO transaksi SHARED akun anggota virtual dan pengaturan perangkat`() = runBlocking {
        val room = fx.roomIds().first()
        fx.local.zakat.saveGoldPrice(GoldPrice(fx.today, Money.rupiah(1_000), GoldPriceSource.MANUAL))
        fx.local.zakat.saveGoldPrice(GoldPrice(fx.today.plusDays(1), Money.rupiah(1_000), GoldPriceSource.AUTO))
        fx.local.settings.put("reminder_enabled", "1")
        val db = fx.db.openHelper.writableDatabase
        db.execSQL("INSERT INTO account (id, name, kind, currency, opening_balance, archived, sort_order, last_reconciled_on, shared_from) VALUES ('virt-1', 'Dompet (Ani)', 'CASH', 'IDR', 0, 0, 9, NULL, 'member-9')")
        val categoryId = fx.local.rooms.categories(room.id).first().id.value
        db.execSQL(
            "INSERT INTO money_transaction (id, kind, amount, currency, account_id, to_account_id, room_id, category_id, income_source, occurred_on, note, origin, created_at, updated_at, synced_at) " +
                "VALUES ('shared-1', 'EXPENSE', 5000, 'IDR', 'virt-1', NULL, '${room.id.value}', '$categoryId', NULL, ${fx.today.toEpochDay()}, NULL, '${TransactionOrigin.SHARED.name}', 1, 1, NULL)",
        )
        fx.clearSync()

        fx.local.syncBootstrap.markAllDirty()

        assertEquals(setOf(SyncRecordIds.goldPrice(fx.today.toEpochDay())), fx.ids(SyncRecordType.GOLD_PRICE))
        assertFalse("virt-1" in fx.ids(SyncRecordType.ACCOUNT))
        assertFalse("shared-1" in fx.ids(SyncRecordType.TRANSACTION))
        assertEquals(setOf(SyncRecordIds.ALLOCATION_CONFIG), fx.ids(SyncRecordType.ALLOCATION_CONFIG))
    }

    @Test
    fun `database kosong tidak menghasilkan rekaman apa pun`() = runBlocking {
        val empty = SyncFixtureEmpty.open()
        try {
            assertEquals(0, empty.syncBootstrap.markAllDirty())
        } finally {
            empty.close()
        }
    }
}

/** Ruang kerja tanpa onboarding. */
private object SyncFixtureEmpty {
    fun open(): LocalLedger {
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>(),
            com.roziqrizal.rizqflow.data.db.RizqflowDatabase::class.java,
        ).allowMainThreadQueries().build()
        return LocalLedger(db)
    }
}
