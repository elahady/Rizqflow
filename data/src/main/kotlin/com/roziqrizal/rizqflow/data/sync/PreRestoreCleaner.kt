package com.roziqrizal.rizqflow.data.sync

import android.util.Log
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Membersihkan salinan `<db>.prerestore` yang dibuat [PreRestoreBackup] sebelum "Pulihkan" menimpa data lokal. Salinan
 * itu tidak pernah dihapus sebelumnya; aturannya mengikuti pagar `.premigrate` ([com.roziqrizal.rizqflow.data.PreMigrateGuard]):
 * umur minimal ditambah syarat aman, dan tidak pernah melempar.
 *
 * Salinan dihapus HANYA bila SEMUA syarat ini terpenuhi:
 * 1. salinan ada dan berupa berkas;
 * 2. umurnya sudah [minAgeMillis] (bawaan 30 hari, USULAN Claude: belum ada layar yang memulihkan salinan, jadi ini
 *    jendela bagi pemilik untuk melaporkan data yang hilang. Lebih panjang daripada `.premigrate` yang 7 hari karena
 *    `.premigrate` melindungi dari galat migrasi yang terlihat dalam hitungan hari, sedangkan salinan ini satu-satunya
 *    jejak data lokal yang sengaja dibuang). Jam yang mundur (umur negatif) berarti tidak cukup umur;
 * 3. database hidup masih ada dan ruang kerja TIDAK murni ([isWorkspacePure] false), yaitu sudah berisi data pengguna
 *    nyata (transaksi, utang, zakat, dan seterusnya). Itu bukti bahwa pulihkan menghasilkan ruang kerja yang berguna;
 *    selama ruang kerja masih murni (kosong), salinan bisa saja satu-satunya tempat data lama dan dipertahankan,
 *    berapa pun umurnya. Akibatnya salinan bisa bertahan selamanya bagi akun yang datanya di server memang kosong,
 *    satu berkas yang ukurannya sama dengan database dan tetap di penyimpanan privat aplikasi.
 *
 * 4. salinan TIDAK sedang dipakai [isInUse] (kunci pakai `PreRestoreUseLock` milik layar "Kembalikan data sebelum
 *    pemulihan"): pembersih tidak boleh menghapus salinan yang sedang dibaca untuk dikembalikan.
 *
 * Galat apa pun (membaca kemurnian, menghapus) berarti salinan dipertahankan. Yang dihapus hanya berkas `.prerestore`
 * ([cleanUp]) serta `.prerestore.used` dan `.prerevert` dari pengembalian ([cleanUpRevertCopies]; aturan yang sama);
 * database aktif dan salinan `.premigrate` tidak pernah disentuh. Dipanggil dari ruang kerja yang baru dibuka
 * (`AccountWorkspace.cleanUpPreRestore`), di thread IO.
 */
class PreRestoreCleaner(
    private val databaseFile: () -> File,
    private val isWorkspacePure: suspend () -> Boolean,
    /** True bila salinan sedang dipakai pengembalian; dibaca tepat sebelum menghapus. */
    private val isInUse: () -> Boolean = { false },
    private val minAgeMillis: Long = DEFAULT_MIN_AGE_MILLIS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { Log.w(TAG, it) },
) {
    enum class Outcome {
        /** Tidak ada salinan. */
        NO_COPY,

        /** Salinan belum cukup umur (atau jam mundur). */
        TOO_YOUNG,

        /** Ruang kerja masih murni, database hilang, atau kemurnian tidak terbaca: salinan dipertahankan. */
        KEPT_UNSAFE,

        /** Salinan sedang dipakai pengembalian: tidak disentuh, berapa pun umurnya. */
        IN_USE,

        /** Salinan dihapus. */
        DELETED,
    }

    /** Membersihkan `.prerestore`. */
    suspend fun cleanUp(): Outcome = cleanUpOne(PreRestoreFiles.PRERESTORE)

    /** Membersihkan sisa pengembalian (`.prerestore.used` dan `.prerevert`) dengan aturan yang sama; hasil per berkas. */
    suspend fun cleanUpRevertCopies(): List<Outcome> = listOf(PreRestoreFiles.USED, PreRestoreFiles.PREREVERT).map { cleanUpOne(it) }

    private suspend fun cleanUpOne(suffix: String): Outcome = withContext(Dispatchers.IO) {
        try {
            val db = databaseFile()
            val copy = File(db.path + suffix)
            if (!copy.isFile) return@withContext Outcome.NO_COPY
            if (isInUse()) return@withContext Outcome.IN_USE

            val age = nowMillis() - copy.lastModified()
            if (age < minAgeMillis) return@withContext Outcome.TOO_YOUNG

            if (!db.isFile || db.length() == 0L) {
                log("Database aktif tidak ditemukan; salinan sebelum pulihkan dipertahankan")
                return@withContext Outcome.KEPT_UNSAFE
            }
            if (isWorkspacePure()) return@withContext Outcome.KEPT_UNSAFE
            // Diperiksa ULANG tepat sebelum menghapus: pengembalian bisa dimulai selama pemeriksaan kemurnian di atas.
            if (isInUse()) return@withContext Outcome.IN_USE

            if (!copy.delete()) {
                log("Salinan sebelum pulihkan gagal dihapus")
                return@withContext Outcome.KEPT_UNSAFE
            }
            log("Salinan sebelum pulihkan dihapus (umur cukup dan ruang kerja berisi data)")
            Outcome.DELETED
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log("Pembersihan salinan sebelum pulihkan gagal (${t.javaClass.simpleName}); salinan dipertahankan")
            Outcome.KEPT_UNSAFE
        }
    }

    companion object {
        /** Umur minimal salinan sebelum boleh dihapus: 30 hari. */
        const val DEFAULT_MIN_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000
        private const val TAG = "PreRestoreCleaner"
    }
}
