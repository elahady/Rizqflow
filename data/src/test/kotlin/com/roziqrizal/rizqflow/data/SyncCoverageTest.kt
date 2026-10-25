package com.roziqrizal.rizqflow.data

import android.content.Context
import androidx.room.Room as AndroidRoom
import androidx.test.core.app.ApplicationProvider
import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.data.sync.SyncCoverage
import com.roziqrizal.rizqflow.domain.sync.SyncRecordType
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tes cakupan (Tahap 1 sync-desain.md bagian E): membaca daftar tabel dari skema [RizqflowDatabase] dan GAGAL bila
 * ada tabel yang tidak punya cakupan recorder atau tidak masuk daftar pengecualian bertulis alasan.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncCoverageTest {

    private fun physicalTables(): List<String> {
        val db = AndroidRoom.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), RizqflowDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val sqlite = db.openHelper.writableDatabase
            return SchemaTestSupport.dataTables { sqlite.query(it) }
        } finally {
            db.close()
        }
    }

    @Test
    fun `setiap tabel di skema punya cakupan recorder atau pengecualian bertulis alasan`() {
        val tables = physicalTables()
        assertTrue(tables.size >= 24, "tabel skema harus terbaca dari database: $tables")
        val missing = tables.filter { it !in SyncCoverage.tables }
        assertTrue(missing.isEmpty(), "Tabel tanpa cakupan sinkron (tambahkan ke SyncCoverage: Recorded, Embedded, atau Excluded dengan alasan): $missing")
    }

    @Test
    fun `daftar cakupan tidak memuat tabel yang sudah tidak ada`() {
        val stale = SyncCoverage.tables.keys - physicalTables().toSet()
        assertTrue(stale.isEmpty(), "Entri SyncCoverage untuk tabel yang tidak ada di skema: $stale")
    }

    @Test
    fun `setiap jenis rekaman dicakup oleh minimal satu tabel`() {
        assertEquals(SyncRecordType.entries.toSet(), SyncCoverage.coveredTypes(), "ada SyncRecordType tanpa tabel sumber atau sebaliknya")
    }

    @Test
    fun `setiap pengecualian dan tabel tertanam menyebut alasan`() {
        for ((table, entry) in SyncCoverage.tables) {
            when (entry) {
                is SyncCoverage.Entry.Excluded -> assertTrue(entry.reason.isNotBlank(), "pengecualian $table tanpa alasan")
                is SyncCoverage.Entry.Embedded -> assertTrue(entry.reason.isNotBlank(), "tabel tertanam $table tanpa alasan")
                is SyncCoverage.Entry.Recorded -> assertTrue(entry.types.isNotEmpty(), "Recorded $table tanpa jenis")
            }
        }
    }

    @Test
    fun `tabel keadaan sinkron sendiri tidak ikut disinkronkan`() {
        for (table in listOf("sync_record", "sync_cursor", "sync_issue", "shared_room")) {
            assertTrue(SyncCoverage.tables[table] is SyncCoverage.Entry.Excluded, table)
        }
    }

    @Test
    fun `tabel sistem tidak dihitung sebagai tabel data`() {
        val tables = physicalTables()
        assertTrue(tables.none { it in SyncCoverage.systemTables || it.startsWith("sqlite_") })
    }
}
