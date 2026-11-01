package com.roziqrizal.rizqflow.data

import com.roziqrizal.rizqflow.domain.ledger.CsvImportPreviewBuilder
import com.roziqrizal.rizqflow.domain.ledger.CsvImportService
import com.roziqrizal.rizqflow.domain.ledger.TransaksiHarianCsvParser
import com.roziqrizal.rizqflow.domain.sync.SyncRecordType
import com.roziqrizal.rizqflow.domain.sync.withSyncApplying
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Biaya recorder sinkron pada impor CSV besar (utang Tahap 1: "tiap penulisan kini ditambah satu transaksi pembungkus dan
 * beberapa query; impor CSV 10 ribu baris belum diukur").
 *
 * - Tes kewajaran (selalu jalan, 1.500 baris): impor menghasilkan tepat satu rekaman kotor per transaksi, tanpa
 *   rekaman hantu, dan semuanya terbaca oleh backfill/handler.
 * - Pengukuran 10 ribu baris hanya jalan bila variabel lingkungan `RIZQFLOW_PERF=1` (lambat dan bergantung mesin, jadi
 *   tidak menjadi gerbang): membandingkan impor yang sama dengan recorder aktif dan dengan recorder dimatikan (konteks
 *   `applying`, pencatat langsung kembali), lalu menulis angkanya ke `build/perf-import.txt`. Angka ini perkiraan kasar
 *   dari SQLite Robolectric di JVM, BUKAN waktu di perangkat.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImportPerformanceTest {
    private fun csv(rows: Int, fx: SyncFixture): String {
        val rooms = runBlocking { fx.local.rooms.activeRooms() }
        val categoryNames = runBlocking { rooms.map { room -> fx.local.rooms.categories(room.id).first { !it.isSystem }.name } }
        val start = LocalDate.of(2026, 1, 1)
        return buildString {
            append("Deskripsi,Tanggal,Jumlah,Tipe,Akun,Kategori,Catatan\r\n")
            for (i in 0 until rows) {
                val date = start.plusDays((i % 300).toLong())
                val income = i % 5 == 0
                // Nominal unik per baris supaya pembangun pratinjau tidak menyatukan pemasukan dan pengeluaran jadi transfer.
                append(if (income) "Gaji" else "Belanja").append(',')
                append(date).append(',')
                append(1_000 + i).append(',')
                append(if (income) "Pemasukan" else "Pengeluaran").append(',')
                append("Dompet").append(',')
                append(if (income) "" else categoryNames[i % categoryNames.size]).append(',')
                append("baris $i").append("\r\n")
            }
        }
    }

    private data class Run(val imported: Int, val skipped: Int, val millis: Long)

    private inline fun measureMillis(block: () -> Unit): Long {
        val started = System.nanoTime()
        block()
        return (System.nanoTime() - started) / 1_000_000
    }

    private fun import(fx: SyncFixture, csv: String, recorderOn: Boolean): Run = runBlocking {
        val rows = TransaksiHarianCsvParser.parse(csv)
        val preview = CsvImportPreviewBuilder(fx.local.accounts.allAccounts(), fx.local.rooms.allCategories()).build(rows)
        val service = CsvImportService(fx.ledger)
        val started = System.nanoTime()
        val result = if (recorderOn) service.commit(preview) else withSyncApplying { service.commit(preview) }
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue(result.failures.isEmpty(), "impor tanpa kegagalan: ${result.failures.take(3)}")
        Run(result.imported, result.skipped, millis)
    }

    @Test
    fun `impor besar menghasilkan tepat satu rekaman transaksi kotor per baris dan tidak ada rekaman hantu`() {
        val fx = SyncFixture()
        try {
            fx.clearSync()
            val run = import(fx, csv(1_500, fx), recorderOn = true)

            assertEquals(1_500, run.imported)
            assertEquals(1_500, fx.rows().count { it.type == SyncRecordType.TRANSACTION.wire && it.dirty && !it.deleted })
            assertEquals(1_500, fx.rows().size, "hanya rekaman transaksi (impor tidak menyentuh ruang, akun, atau kategori)")
        } finally {
            fx.close()
        }
    }

    @Test
    fun `ukur biaya recorder pada impor 10 ribu baris`() {
        assumeTrue("Pengukuran dimatikan; set RIZQFLOW_PERF=1 untuk menjalankannya", System.getenv("RIZQFLOW_PERF") == "1")
        val rows = 10_000

        // Pemanasan JIT dan cache SQLite pada database terpisah, tidak diukur.
        SyncFixture().also { warm -> import(warm, csv(1_000, warm), recorderOn = true); warm.close() }

        val results = mutableListOf<Pair<String, Run>>()
        var syncRows = 0
        for ((label, recorderOn) in listOf("tanpa recorder (1)" to false, "dengan recorder (1)" to true, "tanpa recorder (2)" to false, "dengan recorder (2)" to true)) {
            val fx = SyncFixture()
            try {
                fx.clearSync()
                results += label to import(fx, csv(rows, fx), recorderOn)
                if (recorderOn) syncRows = fx.rows().size
            } finally {
                fx.close()
            }
        }

        // Biaya sisi baca/kirim pada sync_record besar: backfill, pengumpulan 200 baris kotor, dan pembersihan penanda.
        val extra = SyncFixture()
        val secondary = StringBuilder()
        try {
            import(extra, csv(rows, extra), recorderOn = true)
            val dirtyBefore = runBlocking { extra.local.syncStore.dirtyCount(extra.personal) }
            val collectMs = measureMillis { runBlocking { extra.local.syncStore.dirtyRecords(extra.personal, SyncRecordType.TRANSACTION, 200) } }
            val page = runBlocking { extra.local.syncStore.dirtyRecords(extra.personal, SyncRecordType.TRANSACTION, 200) }
            val pushedMs = measureMillis {
                runBlocking {
                    extra.local.syncStore.markPushed(
                        extra.personal,
                        page.map { com.roziqrizal.rizqflow.domain.sync.SentVersion(it.type, it.recordId, it.changedAtMillis) },
                    )
                }
            }
            extra.clearSync()
            val backfillMs = measureMillis { runBlocking { extra.local.syncBootstrap.markAllDirty() } }
            val backfilled = runBlocking { extra.local.syncStore.dirtyCount(extra.personal) }
            secondary.appendLine("sync_record berisi $dirtyBefore baris kotor: kumpulkan 200 baris = $collectMs ms; markPushed 200 baris = $pushedMs ms")
            secondary.appendLine("backfill markAllDirty pada $rows transaksi (sync_record kosong): $backfillMs ms, $backfilled baris kotor")
        } finally {
            extra.close()
        }

        val without = results.filter { !it.first.contains("dengan") }.map { it.second.millis }
        val with = results.filter { it.first.contains("dengan") }.map { it.second.millis }
        val report = buildString {
            appendLine("Impor $rows baris CSV (Robolectric/SQLite JVM, bukan perangkat)")
            results.forEach { (label, run) -> appendLine("$label: ${run.millis} ms untuk ${run.imported} transaksi (${run.millis * 1000 / run.imported.coerceAtLeast(1)} us/baris)") }
            appendLine("rata-rata tanpa recorder: ${without.average().toLong()} ms; dengan recorder: ${with.average().toLong()} ms")
            appendLine("selisih: ${(with.average() - without.average()).toLong()} ms = ${((with.average() - without.average()) * 1000 / rows).toLong()} us/baris; rasio ${"%.2f".format(with.average() / without.average())}x")
            appendLine("baris sync_record setelah impor: $syncRows")
            append(secondary)
        }
        File("build/perf-import.txt").also { it.parentFile.mkdirs() }.writeText(report)
        assertTrue(syncRows >= rows, "setiap transaksi punya rekaman sync_record")
    }
}
