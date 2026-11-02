package com.roziqrizal.rizqflow.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.roziqrizal.rizqflow.data.db.MIGRATION_10_11
import com.roziqrizal.rizqflow.data.db.MIGRATION_11_12
import com.roziqrizal.rizqflow.data.db.MIGRATION_12_13
import com.roziqrizal.rizqflow.data.db.MIGRATION_1_2
import com.roziqrizal.rizqflow.data.db.MIGRATION_2_3
import com.roziqrizal.rizqflow.data.db.MIGRATION_3_4
import com.roziqrizal.rizqflow.data.db.MIGRATION_4_5
import com.roziqrizal.rizqflow.data.db.MIGRATION_5_6
import com.roziqrizal.rizqflow.data.db.MIGRATION_6_7
import com.roziqrizal.rizqflow.data.db.MIGRATION_7_8
import com.roziqrizal.rizqflow.data.db.MIGRATION_8_9
import com.roziqrizal.rizqflow.data.db.MIGRATION_9_10
import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.domain.auth.AccountStorage
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Migrasi 10 ke 11 (2026-11-02, sync-desain.md butir 2.7): status kotor sinkron ruang bersama pindah dari
 * `money_transaction.synced_at` dan `pending_room_tombstone` ke `sync_record` (cakupan `r:<roomId>`, tipe `shared_expense`).
 * Tidak ada perubahan skema, hanya isi. Berkas v10 dibangun dari 10.json, diisi kasus yang membedakan perlakuan tiap baris,
 * lalu dibuka lewat jalur produksi. Yang dibuktikan: hitungan dan isi rekaman baru per kategori, isi tabel lama TIDAK
 * berubah (sidik jari, jadi tidak ada data yang hilang dan rollback aman), migrasi idempoten bila SQL-nya dijalankan ulang,
 * skema hasil migrasi sama dengan skema dari nol (11.json), integritas terjaga, dan pagar `.premigrate` menyalin berkas v10.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationV11Test {
    private lateinit var context: Context
    private val dbName = "migration-v11-test"
    private val scratchName = "migration-v11-scratch"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getDatabasePath(dbName).delete()
        context.getDatabasePath(scratchName).delete()
    }

    @After
    fun tearDown() {
        context.getDatabasePath(dbName).delete()
        context.getDatabasePath(scratchName).delete()
    }

    private fun migrate(): RizqflowDatabase =
        Room.databaseBuilder(context, RizqflowDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
            .build()

    private fun tx(raw: SQLiteDatabase, id: String, room: String, origin: String, updatedAt: Long, syncedAt: Long?, kind: String = "EXPENSE") {
        val values = ContentValues().apply {
            put("id", id)
            put("kind", kind)
            put("amount", 10_000L)
            put("currency", "IDR")
            put("account_id", "a1")
            putNull("to_account_id")
            put("room_id", room)
            if (kind == "EXPENSE") put("category_id", "c-$room") else putNull("category_id")
            putNull("income_source")
            put("occurred_on", 20_000L)
            putNull("note")
            put("origin", origin)
            put("created_at", 1L)
            put("updated_at", updatedAt)
            if (syncedAt == null) putNull("synced_at") else put("synced_at", syncedAt)
        }
        check(raw.insertOrThrow("money_transaction", null, values) > 0)
    }

    /**
     * Ruang: r1 dan r2 pernah dibagi (r2 berstatus LEFT), r3 tidak pernah dibagi.
     * Pengeluaran sendiri: t-pending (r1, belum terkirim), t-sent (r1, terkirim), t-left (r2, belum terkirim), t-private (r3).
     * Baris pasangan: t-pair (r1, SHARED). Pemasukan t-income tidak pernah masuk. Tombstone menunggu: tomb-r1 (r1), tomb-r3 (r3,
     * ruang tak pernah dibagi), tomb-alive (r1, transaksinya justru hidup di r1 sehingga tombstone sudah gugur).
     * Rekaman yang sudah ada: t-exist (r1) harus dipertahankan apa adanya.
     */
    private fun populateV10(raw: SQLiteDatabase) {
        raw.execSQL("INSERT INTO account (id, name, kind, currency, opening_balance, archived, sort_order, last_reconciled_on) VALUES ('a1','Dompet','CASH','IDR',1000000,0,0,NULL)")
        for ((id, order) in listOf("r1" to 0, "r2" to 1, "r3" to 2)) {
            raw.execSQL("INSERT INTO room (id, name, kind, icon_key, color_slot, sort_order, archived, giving_mode) VALUES ('$id','Ruang $id','MENCUKUPI','home',1,$order,0,NULL)")
        }
        for (id in listOf("r1", "r2", "r3")) {
            raw.execSQL("INSERT INTO category (id, room_id, name, weight, is_system, archived, sort_order) VALUES ('c-$id','$id','Pos',NULL,0,0,0)")
        }
        raw.execSQL("INSERT INTO shared_room (room_id, role, status, joined_at) VALUES ('r1','OWNER','ACTIVE',1000)")
        raw.execSQL("INSERT INTO shared_room (room_id, role, status, joined_at) VALUES ('r2','MEMBER','LEFT',2000)")

        tx(raw, "t-pending", "r1", "MANUAL", updatedAt = 500, syncedAt = null)
        tx(raw, "t-sent", "r1", "MANUAL", updatedAt = 600, syncedAt = 650)
        tx(raw, "t-left", "r2", "QUICK", updatedAt = 0, syncedAt = null) // updated_at 0: changed_at harus dijepit ke minimal 1
        tx(raw, "t-private", "r3", "MANUAL", updatedAt = 700, syncedAt = null)
        tx(raw, "t-pair", "r1", "SHARED", updatedAt = 800, syncedAt = 800)
        tx(raw, "t-income", "r1", "MANUAL", updatedAt = 900, syncedAt = null, kind = "INCOME")
        tx(raw, "t-alive", "r1", "MANUAL", updatedAt = 1_000, syncedAt = 1_100)
        tx(raw, "t-exist", "r1", "MANUAL", updatedAt = 1_200, syncedAt = null)

        raw.execSQL("INSERT INTO pending_room_tombstone VALUES ('tomb-r1','r1',3000)")
        raw.execSQL("INSERT INTO pending_room_tombstone VALUES ('tomb-r3','r3',3100)")
        raw.execSQL("INSERT INTO pending_room_tombstone VALUES ('t-alive','r1',3200)")
        raw.execSQL("INSERT INTO pending_room_tombstone VALUES ('tomb-r2','r2',3300)")

        // Rekaman yang sudah ada: bersih dengan cap 7000; tidak boleh ditimpa walau transaksinya belum terkirim (synced_at null).
        raw.execSQL("INSERT INTO sync_record VALUES ('r:r1','shared_expense','t-exist',7000,0,0,7000,NULL,'dev-9')")
        raw.execSQL("INSERT INTO sync_record VALUES ('a','transaction','t-private',6000,0,1,NULL,NULL,NULL)")
    }

    private data class Rec(val scope: String, val id: String, val changedAt: Long, val deleted: Boolean, val dirty: Boolean, val pushed: Long?, val device: String?)

    private fun sharedRecords(db: SupportSQLiteDatabase): List<Rec> =
        db.query("SELECT scope, record_id, changed_at, deleted, dirty, pushed_changed_at, device_id FROM sync_record WHERE scope LIKE 'r:%' ORDER BY scope, record_id").use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(Rec(c.getString(0), c.getString(1), c.getLong(2), c.getInt(3) != 0, c.getInt(4) != 0, if (c.isNull(5)) null else c.getLong(5), c.getString(6)))
                }
            }
        }

    private fun expectedRecords() = listOf(
        // r1 (ACTIVE): belum terkirim -> kotor; terkirim -> bersih; terkirim lalu tombstone t-alive digugurkan.
        Rec("r:r1", "t-alive", 1_000, false, false, 1_000, null),
        Rec("r:r1", "t-exist", 7_000, false, false, 7_000, "dev-9"), // dipertahankan apa adanya
        Rec("r:r1", "t-pending", 500, false, true, null, null),
        Rec("r:r1", "t-sent", 600, false, false, 600, null),
        Rec("r:r1", "tomb-r1", 3_000, true, true, null, null),
        // r2 (LEFT, tetap ruang yang pernah dibagi): changed_at dijepit minimal 1, tombstone ikut.
        Rec("r:r2", "t-left", 1, false, true, null, null),
        Rec("r:r2", "tomb-r2", 3_300, true, true, null, null),
        // r3 tidak pernah dibagi: tidak ada rekaman sama sekali (t-private dan tomb-r3 tidak dijangkau).
        // t-pair (SHARED) dan t-income (bukan pengeluaran) tidak punya rekaman.
    ).sortedWith(compareBy({ it.scope }, { it.id }))

    @Test
    fun `migrasi 10 ke 11 memindahkan status kotor ruang yang pernah dibagi dan tidak mengubah tabel lama`() {
        val path = context.getDatabasePath(dbName).path
        val raw = SchemaTestSupport.buildFromSchema(context, 10, path)
        populateV10(raw)

        val rawQuery: (String) -> android.database.Cursor = { raw.rawQuery(it, null) }
        val tables = SchemaTestSupport.dataTables(rawQuery)
        val columns = tables.associateWith { SchemaTestSupport.columns(rawQuery, it) }
        val countsBefore = tables.associateWith { SchemaTestSupport.count(rawQuery, it) }
        val fingerprintsBefore = tables.associateWith { SchemaTestSupport.fingerprint(rawQuery, it, columns.getValue(it)) }
        raw.close()

        val db = migrate()
        try {
            val sqlite = db.openHelper.writableDatabase
            assertEquals(13, sqlite.version)
            val query: (String) -> android.database.Cursor = { sqlite.query(it) }

            assertEquals(expectedRecords(), sharedRecords(sqlite))

            // Rekaman cakupan pribadi tidak tersentuh, dan semua tabel selain sync_record identik (data lama utuh).
            // (tables + shared_member/shared_envelope): dua tabel itu baru dari migrasi 12->13 di ujung rantai (lihat KDoc kelas ini), bukan dari migrasi 10->11.
            assertEquals((tables + listOf("shared_member", "shared_envelope")).sorted(), SchemaTestSupport.dataTables(query).sorted())
            for (table in tables - "sync_record") {
                assertEquals(countsBefore.getValue(table), SchemaTestSupport.count(query, table), "jumlah baris $table")
                assertEquals(fingerprintsBefore.getValue(table), SchemaTestSupport.fingerprint(query, table, columns.getValue(table)), "isi tabel $table berubah")
            }
            val personal = SchemaTestSupport.count(query, "sync_record") - sharedRecords(sqlite).size
            assertEquals(1L, personal, "rekaman cakupan pribadi yang ada tetap satu")

            sqlite.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count, "foreign_key_check harus kosong") }
            sqlite.query("PRAGMA integrity_check").use {
                assertTrue(it.moveToFirst())
                assertEquals("ok", it.getString(0))
            }

            // Skema sama dengan skema dari nol (13.json: versi skema saat ini, migrasi 10 ke 11 ini sendiri tidak mengubah skema).
            val scratch = SchemaTestSupport.buildFromSchema(context, 13, context.getDatabasePath(scratchName).path)
            try {
                val expected = SchemaTestSupport.structure { scratch.rawQuery(it, null) }
                val actual = SchemaTestSupport.structure(query)
                assertEquals(expected.keys, actual.keys, "objek skema berbeda")
                for (key in expected.keys) assertEquals(expected.getValue(key), actual.getValue(key), "skema $key berbeda dari skema dari nol")
            } finally {
                scratch.close()
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `menjalankan SQL migrasi 10 ke 11 sekali lagi tidak mengubah apa pun`() {
        val path = context.getDatabasePath(dbName).path
        val raw = SchemaTestSupport.buildFromSchema(context, 10, path)
        populateV10(raw)
        raw.close()

        val db = migrate()
        try {
            val sqlite = db.openHelper.writableDatabase
            val before = sharedRecords(sqlite)
            // Idempoten: INSERT OR IGNORE tidak menimpa rekaman yang kini ada (termasuk yang sudah berubah sejak migrasi).
            sqlite.execSQL("UPDATE sync_record SET dirty = 0, pushed_changed_at = changed_at WHERE scope = 'r:r1' AND record_id = 't-pending'")
            val edited = sharedRecords(sqlite)
            MIGRATION_10_11.migrate(sqlite)
            assertEquals(edited, sharedRecords(sqlite), "menjalankan ulang tidak boleh menimpa atau menambah rekaman")
            assertEquals(before.size, sharedRecords(sqlite).size)
        } finally {
            db.close()
        }
    }

    @Test
    fun `open v10 menyalin premigrate`() {
        val accountId = "migrasi-v11@example.test"
        val dbFile = context.getDatabasePath(AccountStorage.databaseName(accountId))
        dbFile.parentFile?.mkdirs()
        dbFile.parentFile?.listFiles()?.filter { it.name.startsWith(dbFile.name) }?.forEach { it.delete() }
        val raw = SchemaTestSupport.buildFromSchema(context, 10, dbFile.path)
        populateV10(raw)
        raw.close()
        val original = dbFile.readBytes()

        val ledger = LocalLedger.open(context, accountId)
        try {
            assertEquals(13, ledger.db.openHelper.writableDatabase.version)
            val copy = File(dbFile.path + ".premigrate")
            assertTrue(copy.exists(), "pagar harus menyalin berkas v10 sebelum migrasi")
            assertContentEquals(original, copy.readBytes(), "salinan identik dengan berkas sebelum migrasi")
            SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READONLY).use { assertEquals(10, it.version) }
            assertEquals(expectedRecords(), sharedRecords(ledger.db.openHelper.readableDatabase))

            // Pembaca produksi melihat hasilnya: dua menunggu kirim di r1 (t-pending hidup, tomb-r1 tombstone).
            kotlinx.coroutines.runBlocking {
                val r1 = com.roziqrizal.rizqflow.domain.model.RoomId("r1")
                assertEquals(listOf("t-pending"), ledger.transactions.pendingSync(r1).map { it.transaction.id.value })
                assertEquals(listOf("tomb-r1"), ledger.transactions.pendingTombstones(r1).map { it.transactionId.value })
            }
        } finally {
            ledger.close()
            dbFile.parentFile?.listFiles()?.filter { it.name.startsWith(dbFile.name) }?.forEach { it.delete() }
        }
    }
}
