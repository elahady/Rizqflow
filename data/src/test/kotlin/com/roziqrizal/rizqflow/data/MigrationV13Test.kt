package com.roziqrizal.rizqflow.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
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
 * Migrasi 12 ke 13 (2026-11-02, peran-anggota.md bagian E.2/H langkah 8 "Amplop"): dua tabel baru aditif,
 * `shared_member` dan `shared_envelope` — tidak ada kolom yang diubah di tabel lama. Pola sama dengan
 * [MigrationV12Test]: berkas v12 dibangun dari 12.json, isi tabel lama TIDAK berubah (sidik jari), tabel baru ADA
 * dan kosong, `foreign_key_check` kosong, skema hasil migrasi identik dengan skema dari nol (13.json), dan pagar
 * `.premigrate` menyalin berkas v12.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationV13Test {
    private lateinit var context: Context
    private val dbName = "migration-v13-test"
    private val scratchName = "migration-v13-scratch"

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

    private fun populateV12(raw: SQLiteDatabase) {
        raw.execSQL("INSERT INTO account (id, name, kind, currency, opening_balance, archived, sort_order, last_reconciled_on, shared_author) VALUES ('a1','Dompet','CASH','IDR',1000000,0,0,NULL,NULL)")
        raw.execSQL("INSERT INTO room (id, name, kind, icon_key, color_slot, sort_order, archived, giving_mode) VALUES ('r1','Keluarga','MENCUKUPI','home',1,0,0,NULL)")
    }

    @Test
    fun `migrasi 12 ke 13 menambah shared_member dan shared_envelope kosong tanpa mengubah data lain`() {
        val path = context.getDatabasePath(dbName).path
        val raw = SchemaTestSupport.buildFromSchema(context, 12, path)
        populateV12(raw)

        val rawQuery: (String) -> android.database.Cursor = { raw.rawQuery(it, null) }
        val tables = SchemaTestSupport.dataTables(rawQuery)
        val countsBefore = tables.associateWith { SchemaTestSupport.count(rawQuery, it) }
        val columnsV12 = tables.associateWith { SchemaTestSupport.columns(rawQuery, it) }
        val fingerprintsBefore = tables.associateWith { SchemaTestSupport.fingerprint(rawQuery, it, columnsV12.getValue(it)) }
        raw.close()

        val db = migrate()
        try {
            val sqlite = db.openHelper.writableDatabase
            assertEquals(13, sqlite.version)
            val query: (String) -> android.database.Cursor = { sqlite.query(it) }

            // Tabel lama (v12) tidak hilang dan isinya tidak berubah.
            val tablesAfter = SchemaTestSupport.dataTables(query)
            for (table in tables) {
                assertTrue(table in tablesAfter, "tabel $table hilang setelah migrasi")
                assertEquals(countsBefore.getValue(table), SchemaTestSupport.count(query, table), "jumlah baris $table")
                assertEquals(fingerprintsBefore.getValue(table), SchemaTestSupport.fingerprint(query, table, columnsV12.getValue(table)), "isi kolom lama $table berubah")
            }

            // Tabel baru ada dan kosong (tidak ada backfill — lihat KDoc MIGRATION_12_13).
            assertTrue("shared_member" in tablesAfter)
            assertTrue("shared_envelope" in tablesAfter)
            assertEquals(0, SchemaTestSupport.count(query, "shared_member"))
            assertEquals(0, SchemaTestSupport.count(query, "shared_envelope"))

            sqlite.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count, "foreign_key_check harus kosong") }
            sqlite.query("PRAGMA integrity_check").use {
                assertTrue(it.moveToFirst())
                assertEquals("ok", it.getString(0))
            }

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
    fun `menjalankan ulang CREATE TABLE migrasi 12 ke 13 di berkas hasil migrasi tidak dijalankan lagi`() {
        val path = context.getDatabasePath(dbName).path
        val raw = SchemaTestSupport.buildFromSchema(context, 12, path)
        populateV12(raw)
        raw.close()

        val first = migrate()
        first.close()

        val second = migrate()
        try {
            assertEquals(13, second.openHelper.writableDatabase.version)
        } finally {
            second.close()
        }
    }

    @Test
    fun `open v12 menyalin premigrate`() {
        val accountId = "migrasi-v13@example.test"
        val dbFile = context.getDatabasePath(AccountStorage.databaseName(accountId))
        dbFile.parentFile?.mkdirs()
        dbFile.parentFile?.listFiles()?.filter { it.name.startsWith(dbFile.name) }?.forEach { it.delete() }
        val raw = SchemaTestSupport.buildFromSchema(context, 12, dbFile.path)
        populateV12(raw)
        raw.close()
        val original = dbFile.readBytes()

        val ledger = LocalLedger.open(context, accountId)
        try {
            assertEquals(13, ledger.db.openHelper.writableDatabase.version)
            val copy = File(dbFile.path + ".premigrate")
            assertTrue(copy.exists(), "pagar harus menyalin berkas v12 sebelum migrasi")
            assertContentEquals(original, copy.readBytes(), "salinan identik dengan berkas sebelum migrasi")
            SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READONLY).use { assertEquals(12, it.version) }
        } finally {
            ledger.close()
            dbFile.parentFile?.listFiles()?.filter { it.name.startsWith(dbFile.name) }?.forEach { it.delete() }
        }
    }
}
