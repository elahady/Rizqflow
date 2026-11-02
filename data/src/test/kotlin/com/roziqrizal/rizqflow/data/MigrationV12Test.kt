package com.roziqrizal.rizqflow.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.roziqrizal.rizqflow.data.db.MIGRATION_10_11
import com.roziqrizal.rizqflow.data.db.MIGRATION_11_12
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Migrasi 11 ke 12 (2026-11-02, peran-anggota.md bagian E.2/H langkah 5): satu kolom aditif,
 * `account.shared_author`. Berkas v11 dibangun dari 11.json (bukan salinan tangan), diisi satu akun
 * nyata dan satu akun anggota virtual (dibuat sebelum kolom ini ada), lalu dibuka lewat jalur
 * produksi seperti [MigrationV10Test]. Yang dibuktikan: kolom baru ada dan `NULL` untuk baris lama,
 * isi tabel lain TIDAK berubah (sidik jari), skema hasil migrasi identik dengan skema dari nol
 * (12.json), integritas terjaga, dan pagar `.premigrate` menyalin berkas v11.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationV12Test {
    private lateinit var context: Context
    private val dbName = "migration-v12-test"
    private val scratchName = "migration-v12-scratch"

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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
            .build()

    private fun populateV11(raw: SQLiteDatabase) {
        raw.execSQL("INSERT INTO account (id, name, kind, currency, opening_balance, archived, sort_order, last_reconciled_on) VALUES ('a1','Dompet','CASH','IDR',1000000,0,0,NULL)")
        // Akun anggota virtual dibuat sebelum kolom shared_author ada (sesuai keadaan sungguhan pra-migrasi 12).
        raw.execSQL("INSERT INTO account (id, name, kind, currency, opening_balance, archived, sort_order, last_reconciled_on, shared_from) VALUES ('va1','Dompet (anggota-abc123)','CASH','IDR',0,0,1,NULL,'r1')")
        raw.execSQL("INSERT INTO room (id, name, kind, icon_key, color_slot, sort_order, archived, giving_mode) VALUES ('r1','Keluarga','MENCUKUPI','home',1,0,0,NULL)")
    }

    @Test
    fun `migrasi 11 ke 12 menambah shared_author kosong tanpa mengubah data lain`() {
        val path = context.getDatabasePath(dbName).path
        val raw = SchemaTestSupport.buildFromSchema(context, 11, path)
        populateV11(raw)

        val rawQuery: (String) -> android.database.Cursor = { raw.rawQuery(it, null) }
        val tables = SchemaTestSupport.dataTables(rawQuery)
        val countsBefore = tables.associateWith { SchemaTestSupport.count(rawQuery, it) }
        // Sidik jari dihitung dari kolom v11 saja (tanpa shared_author), supaya pantas dibandingkan setelah migrasi.
        val columnsV11 = tables.associateWith { SchemaTestSupport.columns(rawQuery, it) }
        val fingerprintsBefore = tables.associateWith { SchemaTestSupport.fingerprint(rawQuery, it, columnsV11.getValue(it)) }
        raw.close()

        val db = migrate()
        try {
            val sqlite = db.openHelper.writableDatabase
            assertEquals(12, sqlite.version)
            val query: (String) -> android.database.Cursor = { sqlite.query(it) }

            assertEquals(tables.sorted(), SchemaTestSupport.dataTables(query).sorted(), "daftar tabel berubah")
            for (table in tables) {
                assertEquals(countsBefore.getValue(table), SchemaTestSupport.count(query, table), "jumlah baris $table")
                // Kolom v11 (tanpa shared_author) tidak berubah sama sekali, termasuk akun virtual lama.
                assertEquals(fingerprintsBefore.getValue(table), SchemaTestSupport.fingerprint(query, table, columnsV11.getValue(table)), "isi kolom lama $table berubah")
            }

            sqlite.query("SELECT shared_author FROM account WHERE id = 'a1'").use {
                assertTrue(it.moveToFirst())
                assertNull(it.getString(0), "akun nyata tidak punya shared_author")
            }
            sqlite.query("SELECT shared_author FROM account WHERE id = 'va1'").use {
                assertTrue(it.moveToFirst())
                assertNull(it.getString(0), "akun virtual yang dibuat sebelum kolom ini ada tetap NULL sampai sync berikutnya")
            }

            sqlite.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count, "foreign_key_check harus kosong") }
            sqlite.query("PRAGMA integrity_check").use {
                assertTrue(it.moveToFirst())
                assertEquals("ok", it.getString(0))
            }

            val scratch = SchemaTestSupport.buildFromSchema(context, 12, context.getDatabasePath(scratchName).path)
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
    fun `menjalankan ulang ALTER TABLE migrasi 11 ke 12 di berkas hasil migrasi tidak dijalankan lagi`() {
        // Perilaku Room biasa (bukan khusus migrasi ini): membuka ulang berkas v12 tidak menjalankan migrasi lagi.
        val path = context.getDatabasePath(dbName).path
        val raw = SchemaTestSupport.buildFromSchema(context, 11, path)
        populateV11(raw)
        raw.close()

        val first = migrate()
        first.close()

        val second = migrate()
        try {
            assertEquals(12, second.openHelper.writableDatabase.version)
        } finally {
            second.close()
        }
    }

    @Test
    fun `open v11 menyalin premigrate`() {
        val accountId = "migrasi-v12@example.test"
        val dbFile = context.getDatabasePath(AccountStorage.databaseName(accountId))
        dbFile.parentFile?.mkdirs()
        dbFile.parentFile?.listFiles()?.filter { it.name.startsWith(dbFile.name) }?.forEach { it.delete() }
        val raw = SchemaTestSupport.buildFromSchema(context, 11, dbFile.path)
        populateV11(raw)
        raw.close()
        val original = dbFile.readBytes()

        val ledger = LocalLedger.open(context, accountId)
        try {
            assertEquals(12, ledger.db.openHelper.writableDatabase.version)
            val copy = File(dbFile.path + ".premigrate")
            assertTrue(copy.exists(), "pagar harus menyalin berkas v11 sebelum migrasi")
            assertContentEquals(original, copy.readBytes(), "salinan identik dengan berkas sebelum migrasi")
            SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READONLY).use { assertEquals(11, it.version) }
        } finally {
            ledger.close()
            dbFile.parentFile?.listFiles()?.filter { it.name.startsWith(dbFile.name) }?.forEach { it.delete() }
        }
    }
}
