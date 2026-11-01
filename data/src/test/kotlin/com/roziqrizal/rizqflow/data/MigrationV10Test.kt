package com.roziqrizal.rizqflow.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.roziqrizal.rizqflow.data.db.MIGRATION_1_2
import com.roziqrizal.rizqflow.data.db.MIGRATION_2_3
import com.roziqrizal.rizqflow.data.db.MIGRATION_3_4
import com.roziqrizal.rizqflow.data.db.MIGRATION_4_5
import com.roziqrizal.rizqflow.data.db.MIGRATION_5_6
import com.roziqrizal.rizqflow.data.db.MIGRATION_6_7
import com.roziqrizal.rizqflow.data.db.MIGRATION_7_8
import com.roziqrizal.rizqflow.data.db.MIGRATION_8_9
import com.roziqrizal.rizqflow.data.db.MIGRATION_10_11
import com.roziqrizal.rizqflow.data.db.MIGRATION_9_10
import com.roziqrizal.rizqflow.data.db.PendingRoomTombstoneEntity
import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.domain.auth.AccountStorage
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Migrasi 9 ke 10 (2026-11-01, sync-desain.md bagian A "pindah keluar dari ruang bersama"): kunci utama
 * `pending_room_tombstone` dari (transaction_id) menjadi (transaction_id, room_id), lewat pembangunan ulang tabel.
 * Berkas v9 dibangun dari 9.json (bukan salinan tangan), diisi di tabel-tabel yang bersinggungan dengan sinkron
 * (termasuk 3000 transaksi acak dengan `synced_at`, `sync_record`, `shared_room`) plus beberapa tombstone, lalu dibuka
 * lewat jalur produksi seperti [MigrationTest]. Yang dibuktikan: hitungan baris SEMUA tabel sama sebelum dan sesudah,
 * isi semua tabel tidak berubah (sidik jari), skema hasil migrasi identik dengan skema dari nol (10.json), integritas
 * terjaga, dan kunci baru benar-benar mengizinkan dua tombstone untuk transaksi yang sama di dua ruang.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationV10Test {
    private lateinit var context: Context
    private val dbName = "migration-v10-test"
    private val scratchName = "migration-v10-scratch"

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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
            .build()

    private fun populateV9(raw: SQLiteDatabase, bulk: Int, seed: Long) {
        raw.execSQL("INSERT INTO account (id, name, kind, currency, opening_balance, archived, sort_order, last_reconciled_on) VALUES ('a1','Dompet','CASH','IDR',1000000,0,0,NULL)")
        raw.execSQL("INSERT INTO account (id, name, kind, currency, opening_balance, archived, sort_order, last_reconciled_on, shared_from) VALUES ('va1','Dompet (Siti)','CASH','IDR',0,0,1,NULL,'r1')")
        raw.execSQL("INSERT INTO room (id, name, kind, icon_key, color_slot, sort_order, archived, giving_mode) VALUES ('r1','Keluarga','MENCUKUPI','home',1,0,0,NULL)")
        raw.execSQL("INSERT INTO room (id, name, kind, icon_key, color_slot, sort_order, archived, giving_mode) VALUES ('r2','Rumah','MENCUKUPI','home',2,1,0,NULL)")
        raw.execSQL("INSERT INTO room (id, name, kind, icon_key, color_slot, sort_order, archived, giving_mode) VALUES ('r3','Diri','MENUMBUHKAN','star',3,2,0,NULL)")
        raw.execSQL("INSERT INTO category (id, room_id, name, weight, is_system, archived, sort_order) VALUES ('c1','r1','Belanja',NULL,0,0,0)")
        raw.execSQL("INSERT INTO shared_room (room_id, role, status, joined_at) VALUES ('r1','OWNER','ACTIVE',1000)")
        raw.execSQL("INSERT INTO shared_room (room_id, role, status, joined_at) VALUES ('r2','MEMBER','ACTIVE',2000)")
        raw.execSQL("INSERT INTO sync_record VALUES ('r:r1','shared_expense','t-1',5000,0,1,NULL,NULL,NULL)")
        raw.execSQL("INSERT INTO sync_record VALUES ('a','transaction','t-2',6000,0,0,6000,NULL,'dev-1')")
        raw.execSQL("INSERT INTO sync_cursor VALUES ('a',77)")
        raw.execSQL("INSERT INTO sync_issue (scope, record_type, record_id, server_id, kind, detail, raw_row, attempts, created_at) VALUES ('a','transaction','x',3,'TAMPERED_OR_CORRUPT','d',NULL,0,9000)")
        raw.execSQL("INSERT INTO app_setting VALUES ('reminder_enabled','1')")

        // Tombstone menunggu kirim: dua di ruang r1, satu di r2, satu di r3 (ruang yang kelak dihapus untuk menguji CASCADE).
        raw.execSQL("INSERT INTO pending_room_tombstone VALUES ('tomb-1','r1',111)")
        raw.execSQL("INSERT INTO pending_room_tombstone VALUES ('tomb-2','r1',222)")
        raw.execSQL("INSERT INTO pending_room_tombstone VALUES ('tomb-3','r2',333)")
        raw.execSQL("INSERT INTO pending_room_tombstone VALUES ('tomb-4','r3',444)")

        val random = Random(seed)
        raw.beginTransaction()
        try {
            repeat(bulk) { i ->
                val values = ContentValues().apply {
                    put("id", "bulk-$i")
                    put("kind", "EXPENSE")
                    put("amount", random.nextLong(1, 50_000_000))
                    put("currency", "IDR")
                    put("account_id", "a1")
                    putNull("to_account_id")
                    put("room_id", if (random.nextBoolean()) "r1" else "r2")
                    if (i % 2 == 0) put("category_id", "c1") else putNull("category_id")
                    putNull("income_source")
                    put("occurred_on", random.nextLong(19_000, 21_000))
                    if (random.nextInt(4) == 0) putNull("note") else put("note", "catatan \"acak\" ${random.nextInt()} é世'")
                    put("origin", listOf("MANUAL", "QUICK", "IMPORT", "SHARED").random(random))
                    put("created_at", random.nextLong(1, Long.MAX_VALUE / 2))
                    put("updated_at", random.nextLong(1, Long.MAX_VALUE / 2))
                    if (random.nextInt(3) == 0) put("synced_at", random.nextLong(1, 1_000_000_000_000L)) else putNull("synced_at")
                }
                check(raw.insertOrThrow("money_transaction", null, values) > 0)
            }
            raw.setTransactionSuccessful()
        } finally {
            raw.endTransaction()
        }
    }

    @Test
    fun `migrasi 9 ke 10 menjaga semua baris dan skema sama dengan dari nol`() {
        val path = context.getDatabasePath(dbName).path
        val raw = SchemaTestSupport.buildFromSchema(context, 9, path)
        populateV9(raw, bulk = 3_000, seed = 10L)

        val rawQuery: (String) -> android.database.Cursor = { raw.rawQuery(it, null) }
        val v9Tables = SchemaTestSupport.dataTables(rawQuery)
        assertTrue("pending_room_tombstone" in v9Tables)
        val countsBefore = v9Tables.associateWith { SchemaTestSupport.count(rawQuery, it) }
        val columnsBefore = v9Tables.associateWith { SchemaTestSupport.columns(rawQuery, it) }
        val fingerprintsBefore = v9Tables.associateWith { SchemaTestSupport.fingerprint(rawQuery, it, columnsBefore.getValue(it)) }
        assertEquals(4L, countsBefore.getValue("pending_room_tombstone"))
        assertEquals(3_000L, countsBefore.getValue("money_transaction"))
        raw.close()

        val db = migrate()
        try {
            val sqlite = db.openHelper.writableDatabase
            assertEquals(11, sqlite.version)
            val query: (String) -> android.database.Cursor = { sqlite.query(it) }

            // Himpunan tabel sama (tidak ada tabel baru, tidak ada sisa tabel sementara), dan hitungan baris tiap tabel sama.
            assertEquals(v9Tables.sorted(), SchemaTestSupport.dataTables(query).sorted(), "daftar tabel berubah (tabel sementara tersisa?)")
            // sync_record sengaja berubah di sini: MIGRATION_10_11 (berjalan di ujung rantai) mengisinya, dan diuji di MigrationV11Test.
            val unchanged = v9Tables - "sync_record"
            for (table in unchanged) assertEquals(countsBefore.getValue(table), SchemaTestSupport.count(query, table), "jumlah baris $table")

            // Isi tiap tabel (semua kolom v9) tidak berubah sama sekali, termasuk tombstone dan 3000 transaksi acak.
            for (table in unchanged) {
                assertEquals(fingerprintsBefore.getValue(table), SchemaTestSupport.fingerprint(query, table, columnsBefore.getValue(table)), "isi tabel $table berubah")
            }

            sqlite.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count, "foreign_key_check harus kosong") }
            sqlite.query("PRAGMA integrity_check").use {
                assertTrue(it.moveToFirst())
                assertEquals("ok", it.getString(0))
            }

            // Skema hasil migrasi sama dengan skema dari nol (10.json), termasuk kunci utama gabungan dan indeksnya.
            val scratch = SchemaTestSupport.buildFromSchema(context, 11, context.getDatabasePath(scratchName).path)
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
    fun `kunci baru memuat dua tombstone beda ruang`() {
        val path = context.getDatabasePath(dbName).path
        val raw = SchemaTestSupport.buildFromSchema(context, 9, path)
        populateV9(raw, bulk = 5, seed = 1L)
        raw.close()

        val db = migrate()
        try {
            kotlinx.coroutines.runBlocking {
                // Dengan kunci lama baris kedua menimpa yang pertama (REPLACE); sekarang keduanya hidup.
                db.transactions().insertPendingTombstone(PendingRoomTombstoneEntity("tomb-1", "r2", 999))
                assertEquals(listOf("tomb-1", "tomb-2"), db.transactions().pendingTombstones("r1").map { it.transactionId }.sorted())
                assertEquals(listOf("tomb-1", "tomb-3"), db.transactions().pendingTombstones("r2").map { it.transactionId }.sorted())

                db.transactions().clearPendingTombstones("r1", listOf("tomb-1", "tomb-2"))
                assertEquals(0, db.transactions().pendingTombstones("r1").size)
                assertEquals(listOf("tomb-1", "tomb-3"), db.transactions().pendingTombstones("r2").map { it.transactionId }.sorted(), "tombstone ruang lain tidak tersentuh")

                db.transactions().deletePendingTombstone("tomb-1", "r2")
                assertEquals(listOf("tomb-3"), db.transactions().pendingTombstones("r2").map { it.transactionId })

                // Menulis ulang pasangan yang sama tetap menimpa (satu baris per transaksi per ruang).
                db.transactions().insertPendingTombstone(PendingRoomTombstoneEntity("tomb-3", "r2", 5_000))
                assertEquals(5_000L, db.transactions().pendingTombstones("r2").single().deletedAt)
            }
            // CASCADE ruang tetap bekerja: tombstone ruang yang dihapus ikut hilang.
            db.openHelper.writableDatabase.execSQL("DELETE FROM room WHERE id = 'r3'")
            db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM pending_room_tombstone WHERE room_id = 'r3'").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `migrasi 9 ke 10 dijalankan ulang di berkas hasil migrasi tidak mengubah apa pun`() {
        // Idempoten dalam arti praktis: membuka ulang berkas v10 tidak menjalankan migrasi lagi dan isinya utuh.
        val path = context.getDatabasePath(dbName).path
        val raw = SchemaTestSupport.buildFromSchema(context, 9, path)
        populateV9(raw, bulk = 5, seed = 2L)
        raw.close()

        val first = migrate()
        val before = first.openHelper.writableDatabase.let { s ->
            SchemaTestSupport.fingerprint({ s.query(it) }, "pending_room_tombstone", listOf("transaction_id", "room_id", "deleted_at"))
        }
        first.close()

        val second = migrate()
        try {
            val s = second.openHelper.writableDatabase
            assertEquals(11, s.version)
            assertEquals(before, SchemaTestSupport.fingerprint({ s.query(it) }, "pending_room_tombstone", listOf("transaction_id", "room_id", "deleted_at")))
        } finally {
            second.close()
        }
    }

    @Test
    fun `open v9 menyalin premigrate`() {
        val accountId = "migrasi-v10@example.test"
        val dbFile = context.getDatabasePath(AccountStorage.databaseName(accountId))
        dbFile.parentFile?.mkdirs()
        dbFile.parentFile?.listFiles()?.filter { it.name.startsWith(dbFile.name) }?.forEach { it.delete() }
        val raw = SchemaTestSupport.buildFromSchema(context, 9, dbFile.path)
        populateV9(raw, bulk = 5, seed = 3L)
        raw.close()
        val original = dbFile.readBytes()

        val ledger = LocalLedger.open(context, accountId)
        try {
            ledger.db.openHelper.writableDatabase
            val copy = File(dbFile.path + ".premigrate")
            assertTrue(copy.exists(), "pagar harus menyalin berkas v9 sebelum migrasi pembangunan ulang tabel")
            assertContentEquals(original, copy.readBytes(), "salinan identik dengan berkas sebelum migrasi")
            SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READONLY).use { assertEquals(9, it.version) }
            assertEquals(11, ledger.db.openHelper.readableDatabase.version)
            ledger.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM pending_room_tombstone").use {
                assertTrue(it.moveToFirst())
                assertEquals(4, it.getInt(0))
            }
        } finally {
            ledger.close()
            dbFile.parentFile?.listFiles()?.filter { it.name.startsWith(dbFile.name) }?.forEach { it.delete() }
        }
    }
}
