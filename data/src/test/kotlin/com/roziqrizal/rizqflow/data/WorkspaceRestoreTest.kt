package com.roziqrizal.rizqflow.data

import com.roziqrizal.rizqflow.data.sync.PreRestoreBackup
import com.roziqrizal.rizqflow.data.sync.PureWorkspaceReplacer
import com.roziqrizal.rizqflow.data.sync.RoomWorkspacePurityChecker
import com.roziqrizal.rizqflow.data.sync.SyncCoverage
import com.roziqrizal.rizqflow.domain.sync.LocalBackupHandle
import com.roziqrizal.rizqflow.domain.sync.SharedRoomInfo
import com.roziqrizal.rizqflow.domain.sync.SharedRoomRole
import com.roziqrizal.rizqflow.domain.sync.SharedRoomStatus
import com.roziqrizal.rizqflow.domain.sync.SyncScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kemurnian ruang kerja mencakup tabel Tahap 4, jalur "Pulihkan" (hapus semua setelah salinan otomatis), dan
 * salinan `.prerestore` (sync-desain.md bagian B "Perangkat baru", keputusan pemilik #11).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkspaceRestoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var fixture: SyncFixture

    @Before
    fun setUp() {
        fixture = SyncFixture()
    }

    @After
    fun tearDown() = fixture.close()

    private fun sql(statement: String) = fixture.db.openHelper.writableDatabase.execSQL(statement)

    private suspend fun ids(): Triple<String, String, String> {
        val room = fixture.local.rooms.activeRooms().first()
        val category = fixture.local.rooms.categories(room.id).first()
        return Triple(room.id.value, category.id.value, fixture.accountId().value)
    }

    /** Satu baris di SETIAP tabel pemakaian (FK terpenuhi), termasuk profil zakat yang diarsipkan. */
    private suspend fun seedEveryUsageTable() {
        val (room, category, account) = ids()
        val txId = fixture.income(500_000).value // money_transaction + allocation_entry
        sql("INSERT INTO zakat_profile (id, name, haul_break_policy, archived) VALUES ('zp', 'Lama', 'RESET', 1)")
        sql("INSERT INTO wealth_item (id, profile_id, kind, label, value, gold_milligrams, updated_at) VALUES ('wi', 'zp', 'CASH_SAVINGS', 'Tabungan', 100, NULL, 1)")
        sql("INSERT INTO wealth_check (id, profile_id, day, net_wealth, nisab) VALUES ('wc', 'zp', 1, 100, 50)")
        sql("INSERT INTO zakat_payment (id, profile_id, day, transaction_id) VALUES ('zpay', 'zp', 1, '$txId')")
        sql("INSERT INTO gold_price (day, per_gram, source) VALUES (1, 1000, 'MANUAL')")
        sql("INSERT INTO debt (id, direction, party, principal, currency, account_id, initial_transaction_id, start_date, due_date, note, collectible) VALUES ('d', 'LENT', 'Teman', 100, 'IDR', NULL, NULL, 1, NULL, NULL, 1)")
        sql("INSERT INTO debt_payment (id, debt_id, amount, paid_on, transaction_id) VALUES ('dp', 'd', 10, 1, NULL)")
        sql("INSERT INTO bill (id, name, amount, currency, account_id, room_id, category_id, note, frequency, start_date, next_due, total_installments, paid_count, active) VALUES ('b', 'Listrik', 100, 'IDR', '$account', '$room', '$category', NULL, NULL, 1, 1, NULL, 0, 1)")
        sql("INSERT INTO recurring_rule (id, kind, amount, currency, account_id, to_account_id, room_id, category_id, income_source, note, frequency, start_date, next_due, end_date, active) VALUES ('r', 'EXPENSE', 100, 'IDR', '$account', NULL, '$room', '$category', NULL, NULL, 'MONTHLY', 1, 1, NULL, 1)")
        sql("INSERT INTO trader_profile (room_id, capital, risk_bp) VALUES ('$room', 1000, 100)")
        sql("INSERT INTO dca_plan (room_id, amount, day_of_month, account_id, category_id) VALUES ('$room', 100, 1, '$account', '$category')")
        sql("INSERT INTO quick_favorite (id, name, amount, room_id, category_id, account_id, use_count, last_used_at) VALUES ('f', 'Kopi', 100, '$room', '$category', '$account', 0, NULL)")
        sql("INSERT INTO day_check (day) VALUES (1)")
    }

    @Test
    fun `setiap tabel di skema masuk tepat satu golongan kemurnian`() {
        val groups = listOf(
            RoomWorkspacePurityChecker.USAGE_TABLES,
            RoomWorkspacePurityChecker.ONBOARDING_TABLES,
            RoomWorkspacePurityChecker.LOCAL_STATE_TABLES,
        )
        val all = groups.flatten()

        assertEquals(all.size, all.toSet().size, "satu tabel tidak boleh ada di dua golongan")
        assertEquals(SyncCoverage.tables.keys, all.toSet(), "tabel baru wajib diputuskan golongan kemurniannya")
    }

    @Test
    fun `hitungan kemurnian membaca setiap tabel pemakaian`() = runBlocking {
        seedEveryUsageTable()

        val usage = RoomWorkspacePurityChecker(fixture.db).usage()

        assertEquals(RoomWorkspacePurityChecker.USAGE_TABLES, usage.keys)
        val empty = usage.filterValues { it == 0 }.keys
        assertTrue(empty.isEmpty(), "tabel yang terbaca kosong padahal sudah diisi: $empty")
        assertFalse(RoomWorkspacePurityChecker(fixture.db).isPure())
    }

    @Test
    fun `tabel Tahap 4 yang berdiri sendiri masing-masing membuat ruang kerja tidak murni`() = runBlocking {
        val (room, category, account) = ids()
        val cases = mapOf(
            "zakat_profile diarsipkan" to "INSERT INTO zakat_profile (id, name, haul_break_policy, archived) VALUES ('zp', 'Lama', 'RESET', 1)",
            "gold_price MANUAL" to "INSERT INTO gold_price (day, per_gram, source) VALUES (1, 1000, 'MANUAL')",
            "trader_profile" to "INSERT INTO trader_profile (room_id, capital, risk_bp) VALUES ('$room', 1000, 100)",
            "dca_plan" to "INSERT INTO dca_plan (room_id, amount, day_of_month, account_id, category_id) VALUES ('$room', 100, 1, '$account', '$category')",
            "quick_favorite" to "INSERT INTO quick_favorite (id, name, amount, room_id, category_id, account_id, use_count, last_used_at) VALUES ('f', 'Kopi', 100, '$room', '$category', '$account', 0, NULL)",
            "day_check" to "INSERT INTO day_check (day) VALUES (1)",
            "bill" to "INSERT INTO bill (id, name, amount, currency, account_id, room_id, category_id, note, frequency, start_date, next_due, total_installments, paid_count, active) VALUES ('b', 'Listrik', 100, 'IDR', '$account', '$room', '$category', NULL, NULL, 1, 1, NULL, 0, 1)",
        )
        val table = mapOf(
            "zakat_profile diarsipkan" to "zakat_profile", "gold_price MANUAL" to "gold_price", "trader_profile" to "trader_profile",
            "dca_plan" to "dca_plan", "quick_favorite" to "quick_favorite", "day_check" to "day_check", "bill" to "bill",
        )
        for ((name, insert) in cases) {
            assertTrue(RoomWorkspacePurityChecker(fixture.db).isPure(), "sebelum $name")
            sql(insert)
            assertFalse(RoomWorkspacePurityChecker(fixture.db).isPure(), "$name harus membuat tidak murni")
            sql(if (name.startsWith("gold")) "DELETE FROM gold_price" else "DELETE FROM ${table.getValue(name)}")
        }
    }

    @Test
    fun `harga emas AUTO milik perangkat tidak membuat ruang kerja tidak murni`() = runBlocking {
        sql("INSERT INTO gold_price (day, per_gram, source) VALUES (1, 1000, 'AUTO')")

        assertTrue(RoomWorkspacePurityChecker(fixture.db).isPure())
    }

    @Test
    fun `pulihkan menghapus semua data pribadi dan jejak sinkron, harga AUTO dan pengaturan perangkat tetap`() = runBlocking {
        seedEveryUsageTable()
        sql("INSERT INTO gold_price (day, per_gram, source) VALUES (2, 2000, 'AUTO')")
        sql("INSERT INTO app_setting (key, value) VALUES ('theme', 'dark')")
        val room = fixture.local.rooms.activeRooms().first()
        fixture.local.syncStore.saveSharedRoom(SharedRoomInfo(room.id, SharedRoomRole.OWNER, SharedRoomStatus.ACTIVE, 1))
        fixture.local.syncStore.setCursor(SyncScope.Personal, 77)
        val backup = temp.newFile("db.prerestore").apply { writeText("salinan") }

        PureWorkspaceReplacer(fixture.db, fixture.local.syncStore).wipeAllAndResetCursor(LocalBackupHandle(backup.path, backup.length(), 1))

        val usage = RoomWorkspacePurityChecker(fixture.db).usage()
        assertTrue(usage.values.all { it == 0 }, "masih ada data: ${usage.filterValues { it > 0 }}")
        assertTrue(fixture.local.rooms.allRooms().isEmpty())
        assertTrue(fixture.local.accounts.allAccounts().isEmpty())
        assertTrue(fixture.local.rooms.allCategories().isEmpty())
        assertTrue(fixture.rows().isEmpty(), "sync_record harus kosong")
        assertTrue(fixture.local.syncStore.sharedRooms().isEmpty())
        assertEquals(0L, fixture.local.syncStore.cursor(SyncScope.Personal))
        assertNotNull(fixture.db.zakat().findGoldPrice(2), "harga AUTO milik perangkat tidak dihapus")
        assertEquals("dark", fixture.db.settings().get("theme"))
    }

    @Test
    fun `pulihkan menolak menghapus bila berkas salinan tidak ada`() = runBlocking {
        fixture.expense(10_000)
        val missing = File(temp.root, "tidak-ada.prerestore")

        assertFailsWith<IllegalStateException> {
            PureWorkspaceReplacer(fixture.db, fixture.local.syncStore).wipeAllAndResetCursor(LocalBackupHandle(missing.path, 10, 1))
        }

        assertFalse(RoomWorkspacePurityChecker(fixture.db).isPure(), "data lokal tidak boleh tersentuh")
    }

    @Test
    fun `salinan sebelum pulihkan menyalin berkas database apa adanya setelah checkpoint`() = runBlocking {
        val dbFile = temp.newFile("akun.db").apply { writeBytes(ByteArray(5000) { (it % 251).toByte() }) }
        var checkpoints = 0

        val handle = PreRestoreBackup(databaseFile = { dbFile }, checkpoint = { checkpoints++ }, nowMillis = { 42 }, log = {}).take()

        assertNotNull(handle)
        assertEquals(1, checkpoints)
        assertEquals(dbFile.path + PreRestoreBackup.SUFFIX, handle.location)
        assertEquals(42, handle.createdAtMillis)
        assertContentEquals(dbFile.readBytes(), File(handle.location).readBytes())
        assertFalse(File(handle.location + ".tmp").exists())
    }

    @Test
    fun `salinan sebelum pulihkan gagal dengan aman bila berkas database tidak ada atau checkpoint melempar`() = runBlocking {
        val missing = File(temp.root, "tidak-ada.db")
        val existing = temp.newFile("ada.db").apply { writeText("isi") }

        assertNull(PreRestoreBackup(databaseFile = { missing }, checkpoint = {}, log = {}).take())
        assertNull(PreRestoreBackup(databaseFile = { existing }, checkpoint = { error("disk penuh") }, log = {}).take())
        assertFalse(File(existing.path + PreRestoreBackup.SUFFIX).exists())
    }
}
