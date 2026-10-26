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
import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Migrasi 7 ke 8 (Tahap 1 sync-desain.md, USULAN): tabel sinkron baru + `account.shared_from`. Berkas v7
 * dibangun dari 7.json (bukan salinan tangan), diisi di SEMUA tabel termasuk transaksi bersama `synced_at`,
 * lalu dibuka lewat jalur produksi (`Room.databaseBuilder(...).addMigrations(...)`) seperti [MigrationTest].
 *
 * `migrate()` ikut membawa [MIGRATION_8_9] (tombstone ruang bersama, Tahap 9) karena Room selalu
 * membutuhkan jalur migrasi lengkap sampai versi [RizqflowDatabase] saat ini (9) untuk membuka
 * berkas versi lama — bukan berarti tes ini sengaja menguji dua migrasi sekaligus, hanya tidak
 * ada cara memisahkannya tanpa membuka database versi antara yang tidak pernah ada di produksi.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationV8Test {
    private lateinit var context: Context
    private val dbName = "migration-v8-test"
    private val scratchName = "migration-v8-scratch"

    private val newTables = listOf("sync_record", "sync_cursor", "sync_issue", "shared_room", "pending_room_tombstone")

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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
            .build()

    /** Mengisi satu baris di tiap tabel v7 (urutan mengikuti kunci asing) dan [bulk] transaksi acak. */
    private fun populateV7(raw: SQLiteDatabase, bulk: Int, seed: Long) {
        raw.execSQL("INSERT INTO account VALUES ('a1','Dompet','CASH','IDR',1000000,0,0,NULL)")
        raw.execSQL("INSERT INTO account VALUES ('a2','BCA','BANK','IDR',5000000,0,1,20000)")
        raw.execSQL("INSERT INTO room VALUES ('r1','Keluarga','MENCUKUPI','home',1,0,0,NULL)")
        raw.execSQL("INSERT INTO room VALUES ('r2','Memberi','MENUNAIKAN','heart',2,1,0,'percentage')")
        raw.execSQL("INSERT INTO room VALUES ('r3','Diri','MENUMBUHKAN','star',3,2,1,NULL)")
        raw.execSQL("INSERT INTO allocation_rule VALUES ('r1',6000,NULL)")
        raw.execSQL("INSERT INTO allocation_rule VALUES ('r2',4000,2500000)")
        raw.execSQL("INSERT INTO category VALUES ('c1','r1','Belanja',NULL,0,0,0)")
        raw.execSQL("INSERT INTO category VALUES ('c2','r1','Tak terlacak',NULL,1,0,1)")
        raw.execSQL("INSERT INTO category VALUES ('c3','r2','Zakat mal',3,1,0,0)")
        // Pemasukan dengan potret alokasi, pengeluaran dengan synced_at terisi, transfer, dan dua pinjaman.
        raw.execSQL("INSERT INTO money_transaction VALUES ('t-inc','INCOME',10000000,'IDR','a2',NULL,NULL,NULL,'Gaji',20001,'gaji','MANUAL',20001000,20001000,NULL)")
        raw.execSQL("INSERT INTO allocation_entry VALUES ('e1','t-inc','r1',6000,6000000,20001000)")
        raw.execSQL("INSERT INTO allocation_entry VALUES ('e2','t-inc','r2',4000,4000000,20001000)")
        raw.execSQL("INSERT INTO money_transaction VALUES ('t-exp','EXPENSE',25000,'IDR','a1',NULL,'r1','c1',NULL,20002,'kopi','QUICK',20002000,20002500,20003000)")
        raw.execSQL("INSERT INTO money_transaction VALUES ('t-zakat','EXPENSE',250000,'IDR','a2',NULL,'r2','c3',NULL,20003,NULL,'MANUAL',20003000,20003000,NULL)")
        raw.execSQL("INSERT INTO money_transaction VALUES ('t-trf','TRANSFER',500000,'IDR','a2','a1',NULL,NULL,NULL,20004,NULL,'MANUAL',1,1,NULL)")
        raw.execSQL("INSERT INTO money_transaction VALUES ('t-loan','LOAN_OUT',200000,'IDR','a1',NULL,NULL,NULL,NULL,20005,NULL,'LOAN',1,1,NULL)")
        raw.execSQL("INSERT INTO money_transaction VALUES ('t-loanpay','LOAN_IN',50000,'IDR','a1',NULL,NULL,NULL,NULL,20006,NULL,'LOAN',1,1,NULL)")
        raw.execSQL("INSERT INTO quick_favorite VALUES ('f1','Kopi',25000,'r1','c1','a1',4,20002000)")
        raw.execSQL("INSERT INTO day_check VALUES (20007)")
        raw.execSQL("INSERT INTO app_setting VALUES ('allocation_mode','WATERFALL')")
        raw.execSQL("INSERT INTO app_setting VALUES ('reminder_enabled','1')")
        raw.execSQL("INSERT INTO zakat_profile VALUES ('zp1','Utama','RESET_WHEN_BELOW_NISAB',0)")
        raw.execSQL("INSERT INTO wealth_item VALUES ('w1','zp1','GOLD','Emas',85000000,100000,20008000)")
        raw.execSQL("INSERT INTO gold_price VALUES (20008,1200000,'MANUAL')")
        raw.execSQL("INSERT INTO gold_price VALUES (20009,1210000,'AUTO')")
        raw.execSQL("INSERT INTO wealth_check VALUES ('wc1','zp1',20008,90000000,85000000)")
        raw.execSQL("INSERT INTO zakat_payment VALUES ('zpay1','zp1',20010,'t-zakat')")
        raw.execSQL("INSERT INTO trader_profile VALUES ('r3',20000000,100)")
        raw.execSQL("INSERT INTO dca_plan VALUES ('r2',500000,5,'a2','c3')")
        raw.execSQL("INSERT INTO recurring_rule VALUES ('rr1','INCOME',10000000,'IDR','a2',NULL,NULL,NULL,'Gaji','gaji bulanan','MONTHLY',20001,20031,NULL,1)")
        raw.execSQL("INSERT INTO bill VALUES ('b1','Cicilan motor',1200000,'IDR','a2','r1','c1',NULL,'MONTHLY',20001,20031,12,3,1)")
        raw.execSQL("INSERT INTO debt VALUES ('d1','LENT','Budi',200000,'IDR','a1','t-loan',20005,20035,'pinjam',1)")
        raw.execSQL("INSERT INTO debt_payment VALUES ('dp1','d1',50000,20006,'t-loanpay')")

        val random = Random(seed)
        raw.beginTransaction()
        try {
            repeat(bulk) { i ->
                val values = ContentValues().apply {
                    put("id", "bulk-$i")
                    put("kind", "EXPENSE")
                    put("amount", random.nextLong(1, 50_000_000))
                    put("currency", "IDR")
                    put("account_id", if (random.nextBoolean()) "a1" else "a2")
                    putNull("to_account_id")
                    put("room_id", "r1")
                    put("category_id", "c1")
                    putNull("income_source")
                    put("occurred_on", random.nextLong(19_000, 21_000))
                    if (random.nextInt(4) == 0) putNull("note") else put("note", "catatan \"acak\" ${random.nextInt()} é世'")
                    put("origin", listOf("MANUAL", "QUICK", "IMPORT", "RECURRING", "BILL").random(random))
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
    fun `migrasi 7 ke 8 menjaga data dan skema sama dengan dari nol`() {
        val path = context.getDatabasePath(dbName).path
        val raw = SchemaTestSupport.buildFromSchema(context, 7, path)
        populateV7(raw, bulk = 5_000, seed = 8L)

        val rawQuery: (String) -> android.database.Cursor = { raw.rawQuery(it, null) }
        val v7Tables = SchemaTestSupport.dataTables(rawQuery)
        assertEquals(20, v7Tables.size, "v7 punya 20 tabel data: $v7Tables")
        val countsBefore = v7Tables.associateWith { SchemaTestSupport.count(rawQuery, it) }
        val columnsBefore = v7Tables.associateWith { SchemaTestSupport.columns(rawQuery, it) }
        val fingerprintsBefore = v7Tables.associateWith { SchemaTestSupport.fingerprint(rawQuery, it, columnsBefore.getValue(it)) }
        // Bukti bahwa tiap tabel benar-benar terisi dan data uji besar benar-benar ada.
        for ((table, count) in countsBefore) assertTrue(count > 0, "tabel $table belum terisi di data uji")
        assertTrue(countsBefore.getValue("money_transaction") >= 5_000)
        assertEquals(2L, SchemaTestSupport.count(rawQuery, "gold_price"), "dua harga emas (MANUAL dan AUTO)")
        raw.rawQuery("SELECT COUNT(*) FROM money_transaction WHERE synced_at IS NOT NULL", null).use {
            it.moveToFirst()
            assertTrue(it.getLong(0) > 1, "ada transaksi dengan synced_at terisi")
        }
        raw.close()

        val db = migrate()
        try {
            val sqlite = db.openHelper.writableDatabase
            assertEquals(9, sqlite.version)
            val query: (String) -> android.database.Cursor = { sqlite.query(it) }

            // Jumlah baris per tabel lama sama, lewat loop generik atas sqlite_master v7.
            val tablesAfter = SchemaTestSupport.dataTables(query)
            for (table in v7Tables) {
                assertEquals(countsBefore.getValue(table), SchemaTestSupport.count(query, table), "jumlah baris $table")
            }
            assertEquals((v7Tables + newTables).sorted(), tablesAfter.sorted())

            // Isi tiap tabel lama (kolom v7) tidak berubah sama sekali, termasuk ±5000 baris acak.
            for (table in v7Tables) {
                assertEquals(fingerprintsBefore.getValue(table), SchemaTestSupport.fingerprint(query, table, columnsBefore.getValue(table)), "isi tabel $table berubah")
            }

            // Integritas.
            sqlite.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count, "foreign_key_check harus kosong") }
            sqlite.query("PRAGMA integrity_check").use {
                assertTrue(it.moveToFirst())
                assertEquals("ok", it.getString(0))
            }

            // Tabel baru kosong, shared_from semua null.
            for (table in newTables) assertEquals(0, SchemaTestSupport.count(query, table), "tabel baru $table harus kosong")
            assertEquals(2, SchemaTestSupport.count(query, "account"))
            sqlite.query("SELECT COUNT(*) FROM account WHERE shared_from IS NOT NULL").use {
                it.moveToFirst()
                assertEquals(0, it.getInt(0))
            }
            // synced_at v7 dibiarkan dan tetap terbaca.
            sqlite.query("SELECT synced_at FROM money_transaction WHERE id = 't-exp'").use {
                assertTrue(it.moveToFirst())
                assertEquals(20003000L, it.getLong(0))
            }

            // Skema hasil migrasi sama dengan skema dari nol (9.json).
            val scratchPath = context.getDatabasePath(scratchName).path
            val scratch = SchemaTestSupport.buildFromSchema(context, 9, scratchPath)
            try {
                val expected = SchemaTestSupport.structure { scratch.rawQuery(it, null) }
                val actual = SchemaTestSupport.structure(query)
                assertEquals(expected.keys, actual.keys, "objek skema berbeda")
                for (key in expected.keys) assertEquals(expected.getValue(key), actual.getValue(key), "skema $key berbeda dari skema dari nol")
            } finally {
                scratch.close()
            }

            // Tabel baru benar-benar bisa dipakai lewat DAO (kolom cocok dengan entitas).
            kotlinx.coroutines.runBlocking {
                db.sync().upsertCursor(com.roziqrizal.rizqflow.data.db.SyncCursorEntity("a", 5))
                assertEquals(5L, db.sync().cursor("a"))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `migrasi 7 ke 8 tidak memakai pernyataan destruktif`() {
        // Migrasi hanya boleh CREATE dan ALTER ADD COLUMN: dicek lewat berkas sumbernya karena aturan repo melarang migrasi destruktif.
        val source = java.io.File(System.getProperty("user.dir"), "src/main/kotlin/com/roziqrizal/rizqflow/data/db/Migrations.kt").readText()
        val body = source.substringAfter("val MIGRATION_7_8")
        val statements = Regex("""execSQL\(\s*"([^"]+)"""").findAll(body).map { it.groupValues[1] }.toList()
        assertTrue(statements.isNotEmpty())
        for (statement in statements) {
            assertTrue(
                statement.startsWith("CREATE TABLE IF NOT EXISTS") || statement.startsWith("CREATE INDEX IF NOT EXISTS") ||
                    statement == "ALTER TABLE `account` ADD COLUMN `shared_from` TEXT DEFAULT NULL",
                "pernyataan tidak diizinkan di MIGRATION_7_8: $statement",
            )
        }
    }
}
