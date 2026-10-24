package com.roziqrizal.rizqflow.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.roziqrizal.rizqflow.data.db.MIGRATION_1_2
import com.roziqrizal.rizqflow.data.db.MIGRATION_2_3
import com.roziqrizal.rizqflow.data.db.MIGRATION_3_4
import com.roziqrizal.rizqflow.data.db.MIGRATION_4_5
import com.roziqrizal.rizqflow.data.db.MIGRATION_5_6
import com.roziqrizal.rizqflow.data.db.MIGRATION_6_7
import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Menguji migrasi skema Room terhadap berkas skema JSON versi 1 yang di-commit (docs/model-data.md:
 * setiap kenaikan versi wajib punya tes migrasi, tanpa migrasi destruktif).
 *
 * Tidak memakai `MigrationTestHelper` (androidx.room:room-testing): pada Room 2.8.5 di bawah
 * Robolectric, helper itu selalu membuka koneksi lewat `SupportSQLiteDriver` dan melempar
 * `IllegalArgumentException` ("driver dikonfigurasi membuka X tapi Y diminta") sebelum migrasi
 * sempat berjalan, terlepas dari `openFactory` yang diberikan — kemungkinan bug spesifik kombinasi
 * versi ini. Sebagai gantinya, tes ini membangun berkas skema versi 1 apa adanya dari `createSql`
 * di 1.json (bukan menyalin tangan), lalu membukanya lewat jalur produksi sungguhan
 * (`Room.databaseBuilder(...).addMigrations(...)`, sama seperti [LocalLedger.open]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {

    @Test
    fun `versi 1 sampai 6 menambah cap_amount tanpa menghapus data yang ada`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = context.getDatabasePath(TEST_DB)
        dbFile.delete()

        buildSchemaVersion1(context, dbFile.path)

        val db = Room.databaseBuilder(context, RizqflowDatabase::class.java, TEST_DB)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
            .build()
        try {
            db.openHelper.writableDatabase.query("SELECT share_bp, cap_amount FROM allocation_rule WHERE room_id = 'r1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(10_000, cursor.getInt(0))
                assertTrue(cursor.isNull(1))
            }
            assertEquals(7, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }

    @Test
    fun `versi 1 sampai 6 menambah tabel peran yang kosong dan bisa dipakai`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = context.getDatabasePath(TEST_DB)
        dbFile.delete()

        buildSchemaVersion1(context, dbFile.path)

        val db = Room.databaseBuilder(context, RizqflowDatabase::class.java, TEST_DB)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
            .build()
        try {
            val sqlite = db.openHelper.writableDatabase
            sqlite.query("SELECT COUNT(*) FROM trader_profile").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            sqlite.query("SELECT COUNT(*) FROM dca_plan").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }

            // Ruang lama tetap ada dan bisa diberi peran lewat DAO (tabel hasil migrasi cocok dengan entitas).
            kotlinx.coroutines.runBlocking {
                db.roles().upsertTrader(com.roziqrizal.rizqflow.data.db.TraderProfileEntity("r1", 5_000_000, 100))
                assertEquals(5_000_000, db.roles().trader("r1")!!.capital)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `versi 1 sampai 6 menambah tabel transaksi berulang yang kosong dan bisa dipakai`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = context.getDatabasePath(TEST_DB)
        dbFile.delete()

        buildSchemaVersion1(context, dbFile.path)

        val db = Room.databaseBuilder(context, RizqflowDatabase::class.java, TEST_DB)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
            .build()
        try {
            db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM recurring_rule").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            kotlinx.coroutines.runBlocking {
                db.accounts().upsert(com.roziqrizal.rizqflow.data.db.AccountEntity("a1", "Dompet", com.roziqrizal.rizqflow.domain.model.AccountKind.CASH, "IDR", 0, false, 0, null))
                db.recurring().upsert(
                    com.roziqrizal.rizqflow.data.db.RecurringRuleEntity(
                        "rr1", com.roziqrizal.rizqflow.domain.model.TransactionKind.INCOME, 5_000_000, "IDR", "a1", null, null, null, "Gaji", null,
                        com.roziqrizal.rizqflow.domain.recurring.Frequency.MONTHLY, 20_000, 20_000, null, true,
                    ),
                )
                assertEquals(5_000_000, db.recurring().find("rr1")!!.amount)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `versi 1 sampai 6 menambah tabel tagihan yang kosong dan bisa dipakai`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = context.getDatabasePath(TEST_DB)
        dbFile.delete()

        buildSchemaVersion1(context, dbFile.path)

        val db = Room.databaseBuilder(context, RizqflowDatabase::class.java, TEST_DB)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
            .build()
        try {
            db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM bill").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            kotlinx.coroutines.runBlocking {
                db.accounts().upsert(com.roziqrizal.rizqflow.data.db.AccountEntity("a1", "Dompet", com.roziqrizal.rizqflow.domain.model.AccountKind.CASH, "IDR", 0, false, 0, null))
                db.rooms().upsertCategory(com.roziqrizal.rizqflow.data.db.CategoryEntity("c1", "r1", "Listrik", null, false, false, 0))
                db.bills().upsert(
                    com.roziqrizal.rizqflow.data.db.BillEntity(
                        "b1", "Cicilan motor", 1_200_000, "IDR", "a1", "r1", "c1", null,
                        com.roziqrizal.rizqflow.domain.recurring.Frequency.MONTHLY, 20_000, 20_000, 12, 3, true,
                    ),
                )
                assertEquals(12, db.bills().find("b1")!!.totalInstallments)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `versi 1 sampai 6 menambah tabel utang piutang yang kosong dan bisa dipakai`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = context.getDatabasePath(TEST_DB)
        dbFile.delete()

        buildSchemaVersion1(context, dbFile.path)

        val db = Room.databaseBuilder(context, RizqflowDatabase::class.java, TEST_DB)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
            .build()
        try {
            val sqlite = db.openHelper.writableDatabase
            sqlite.query("SELECT COUNT(*) FROM debt").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            sqlite.query("SELECT COUNT(*) FROM debt_payment").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            kotlinx.coroutines.runBlocking {
                db.debts().upsert(
                    com.roziqrizal.rizqflow.data.db.DebtEntity(
                        "d1", com.roziqrizal.rizqflow.domain.debt.DebtDirection.LENT, "Budi", 200_000, "IDR", null, null, 20_000, null, null, true,
                    ),
                )
                db.debts().upsertPayment(com.roziqrizal.rizqflow.data.db.DebtPaymentEntity("p1", "d1", 50_000, 20_001, null))
                assertEquals(50_000, db.debts().allPayments().single().amount)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `versi 1 sampai 7 menambah synced_at yang kosong dan transaksi lama tetap bisa dibaca`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = context.getDatabasePath(TEST_DB)
        dbFile.delete()

        buildSchemaVersion1(context, dbFile.path)

        val db = Room.databaseBuilder(context, RizqflowDatabase::class.java, TEST_DB)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
            .build()
        try {
            kotlinx.coroutines.runBlocking {
                db.accounts().upsert(com.roziqrizal.rizqflow.data.db.AccountEntity("a1", "Dompet", com.roziqrizal.rizqflow.domain.model.AccountKind.CASH, "IDR", 0, false, 0, null))
                db.rooms().upsertCategory(com.roziqrizal.rizqflow.data.db.CategoryEntity("c1", "r1", "Belanja", null, false, false, 0))
                db.transactions().insert(
                    com.roziqrizal.rizqflow.data.db.TransactionEntity(
                        "t1", com.roziqrizal.rizqflow.domain.model.TransactionKind.EXPENSE, 25_000, "IDR", "a1", null, "r1", "c1", null, 20_000, null,
                        com.roziqrizal.rizqflow.domain.model.TransactionOrigin.MANUAL, 20_000, 20_000,
                    ),
                )
                val stored = db.transactions().find("t1")!!
                assertEquals(25_000, stored.amount)
                assertEquals(null, stored.syncedAt)

                db.transactions().markSynced(listOf("t1"), 30_000)
                assertEquals(30_000L, db.transactions().find("t1")!!.syncedAt)
                assertEquals(0, db.transactions().pendingSync("r1").size)
            }
        } finally {
            db.close()
        }
    }

    /** Membangun berkas versi 1 dari `createSql` tiap entitas di 1.json, lalu mengisi satu ruang dan aturannya. */
    private fun buildSchemaVersion1(context: Context, path: String) {
        val schema = JSONObject(
            context.assets.open("$SCHEMA_DIR/1.json").use { it.reader(Charsets.UTF_8).readText() },
        ).getJSONObject("database")
        val entities = schema.getJSONArray("entities")

        val raw = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path, null)
        try {
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val tableName = entity.getString("tableName")
                raw.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", tableName))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    raw.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", tableName))
                }
            }
            raw.execSQL("INSERT INTO room (id, name, kind, icon_key, color_slot, sort_order, archived, giving_mode) VALUES ('r1', 'Keluarga', 'MENCUKUPI', 'home', 1, 0, 0, NULL)")
            raw.execSQL("INSERT INTO allocation_rule (room_id, share_bp) VALUES ('r1', 10000)")
            raw.version = 1
        } finally {
            raw.close()
        }
    }

    private companion object {
        const val TEST_DB = "migration-test"
        const val SCHEMA_DIR = "com.roziqrizal.rizqflow.data.db.RizqflowDatabase"
    }
}
