package com.roziqrizal.rizqflow.data.sync

import android.util.Log
import com.roziqrizal.rizqflow.data.LocalLedger
import com.roziqrizal.rizqflow.domain.sync.LocalBackupHandle
import com.roziqrizal.rizqflow.domain.sync.LocalSafetyBackup
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Salinan otomatis berkas database sebelum "Pulihkan" menimpa data lokal (keputusan pemilik #11), mengikuti pola
 * salinan `.premigrate` ([com.roziqrizal.rizqflow.data.PreMigrateGuard]): checkpoint WAL, salin berkas `.db` ke
 * `<nama>.db.prerestore` lewat berkas `.tmp` lalu pindahkan, cek ruang kosong lebih dulu, dan tidak pernah melempar
 * (gagal = null, jalur timpa berhenti). Tanpa enkripsi tambahan dan tanpa primitif kripto baru: salinan berada di
 * penyimpanan privat aplikasi yang sama dengan database aslinya, jadi tidak menambah paparan (alasan yang sama dengan
 * `.premigrate`).
 *
 * Hanya SATU salinan: salinan baru mengganti yang lama. Salinan dibersihkan oleh [PreRestoreCleaner] (umur minimal 30
 * hari dan hanya bila ruang kerja kini berisi data). Belum ada layar untuk memulihkannya; lihat roadmap.md Tahap 9.
 */
class PreRestoreBackup(
    private val databaseFile: () -> File,
    private val checkpoint: () -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { Log.w(TAG, it) },
    /**
     * Akhiran salinan. Bawaan [SUFFIX] (`.prerestore`, sebelum "Pulihkan"); [PreRestoreFiles.PREREVERT] dipakai
     * pengembalian ([com.roziqrizal.rizqflow.domain.sync.PreRestoreRevertFlow]) untuk salinan darurat, TERPISAH supaya tidak
     * pernah menimpa `.prerestore` yang sedang dipulihkan.
     */
    private val suffix: String = SUFFIX,
) : LocalSafetyBackup {
    override suspend fun take(): LocalBackupHandle? = withContext(Dispatchers.IO) {
        try {
            checkpoint()
            val source = databaseFile()
            if (!source.isFile || source.length() == 0L) {
                log("Berkas database tidak ditemukan; salinan sebelum pulihkan tidak dibuat")
                return@withContext null
            }
            val target = File(source.path + suffix)
            val dir = source.absoluteFile.parentFile
            if (dir != null && dir.usableSpace < source.length() + SPACE_MARGIN_BYTES) {
                log("Ruang penyimpanan tidak cukup untuk salinan sebelum pulihkan")
                return@withContext null
            }
            val tmp = File(target.path + ".tmp")
            try {
                Files.copy(source.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally {
                tmp.delete()
            }
            if (target.length() != source.length()) {
                log("Ukuran salinan sebelum pulihkan tidak sama dengan aslinya")
                return@withContext null
            }
            LocalBackupHandle(target.path, target.length(), nowMillis())
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log("Salinan sebelum pulihkan gagal (${t.javaClass.simpleName})")
            null
        }
    }

    companion object {
        const val SUFFIX = PreRestoreFiles.PRERESTORE
        private const val SPACE_MARGIN_BYTES = 4L * 1024 * 1024
        private const val TAG = "PreRestoreBackup"

        fun forLedger(ledger: LocalLedger, suffix: String = SUFFIX) =
            PreRestoreBackup(databaseFile = { ledger.databaseFile }, checkpoint = ledger::checkpoint, suffix = suffix)
    }
}
