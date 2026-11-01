package com.roziqrizal.rizqflow.data

import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Penjaga: kode produksi :data harus memakai `com.roziqrizal.rizqflow.data.db.withTransaction` (yang mencatat kegagalan
 * untuk penerapan sinkron), bukan `androidx.room.withTransaction` langsung. Tanpa ini, satu repositori baru yang lupa
 * mengganti import diam-diam membuat galat yang ditelan penangan sinkron tidak terdeteksi bila `applyIncoming` dipanggil
 * di dalam transaksi lain (lihat GuardedTransaction.kt).
 */
class GuardedTransactionUsageTest {
    @Test
    fun `tidak ada berkas produksi yang mengimpor withTransaction Room langsung`() {
        val root = File("src/main/kotlin")
        assertTrue(root.isDirectory, "direktori sumber tidak ditemukan dari ${File(".").absolutePath}")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "GuardedTransaction.kt" }
            .filter { file -> file.readLines().any { it.trim() == "import androidx.room.withTransaction" } }
            .map { it.name }
            .toList()
        assertTrue(offenders.isEmpty(), "Pakai com.roziqrizal.rizqflow.data.db.withTransaction di: $offenders")
    }
}
