package com.roziqrizal.rizqflow.domain.sync

import kotlin.coroutines.cancellation.CancellationException

/**
 * Mengembalikan data lokal ke salinan `<db>.prerestore` yang dibuat otomatis sebelum "Pulihkan" menimpa data (alur perangkat
 * baru, [NewDeviceRestoreFlow]; keputusan pemilik #11). Menjawab: "pulihanku ternyata bukan yang kumau, kembalikan
 * data lokalku seperti sebelum memulihkan".
 *
 * **Tidak ada primitif penimpa baru.** Mengganti berkas database memakai jalur yang sudah ada (cadangan terenkripsi
 * "Pulihkan" di layar Cadangan): isi berkas diserahkan ke `apply` (di aplikasi: `WorkspaceHost.restoreFromBackup`, yang
 * menutup ruang kerja, menimpa berkas, lalu membuka ulang). Alur ini hanya menyiapkan, menjaga, dan membuktikan urutannya.
 *
 * Urutan, dan apa yang terjadi bila gagal di tiap langkah (tidak ada data lokal yang berubah sebelum langkah 4):
 * 1. [prepare]: ambil kunci pakai salinan ([PreRestoreCopyStore.tryLock], agar `PreRestoreCleaner` di `:data` tidak
 *    menghapusnya selagi dipakai) — gagal = [RevertPreparation.Busy];
 * 2. periksa salinan: ada, berkas SQLite yang utuh, versi skemanya tidak lebih baru dari aplikasi ([SqliteDatabaseCheck]) —
 *    gagal = [RevertPreparation.NotEligible], kunci dilepas;
 * 3. baca seluruh isi salinan ke memori dan periksa ulang (ukuran dan kepala berkas) lalu ambil SALINAN DARURAT dari keadaan
 *    SEKARANG ([LocalSafetyBackup], akhiran terpisah supaya tidak menimpa `.prerestore` yang sedang dipulihkan) — gagal =
 *    [RevertPreparation.CopyUnreadable] / [RevertPreparation.EmergencyBackupFailed], kunci dilepas, tidak ada yang berubah;
 *    UI lalu meminta konfirmasi eksplisit yang menyebut kedua salinan;
 * 4. [revert] (hanya setelah konfirmasi): tandai salinan terpakai (ganti nama), lalu serahkan isinya ke `apply`. Bila
 *    `apply` melempar sebelum berkas diganti, penanda dikembalikan dan salinan tetap bisa dicoba lagi; kunci selalu dilepas.
 *
 * Kelemahan yang diketahui dan harus jujur ke pengguna: mengembalikan berkas TIDAK mengubah data di server. Berkas lokal
 * yang dikembalikan membawa kursor sinkronnya sendiri (versi sebelum pemulihan), jadi sinkron otomatis berikutnya bisa
 * menarik kembali baris yang sudah ada di server, termasuk sebagian hasil pemulihan yang ingin dibuang.
 */
class PreRestoreRevertFlow(
    private val copies: PreRestoreCopyStore,
    private val emergency: LocalSafetyBackup,
    /** Versi skema (`PRAGMA user_version`) tertinggi yang bisa dibuka aplikasi ini; lebih tinggi = tidak boleh dikembalikan. */
    private val maxSchemaVersion: () -> Int,
) {
    /** Kelayakan SAAT INI; tidak mengambil kunci dan tidak mengubah apa pun. Menentukan baris menu muncul atau tidak. */
    suspend fun eligibility(): RevertEligibility {
        val stat = copies.stat() ?: return RevertEligibility.NoCopy
        val unusable = SqliteDatabaseCheck.check(stat.header, stat.sizeBytes, maxSchemaVersion())
        return if (unusable == null) {
            RevertEligibility.Available(PreRestoreCopyInfo(stat.sizeBytes, stat.lastModifiedMillis))
        } else {
            RevertEligibility.Unusable(unusable)
        }
    }

    /** Langkah 1 sampai 3 di atas. Tidak mengubah data lokal. Hasil [RevertPreparation.Ready] WAJIB berakhir di [revert] atau [cancel]. */
    suspend fun prepare(): RevertPreparation {
        if (!copies.tryLock()) return RevertPreparation.Busy
        var handedOver = false
        try {
            val stat = copies.stat() ?: return RevertPreparation.NotEligible(RevertEligibility.NoCopy)
            SqliteDatabaseCheck.check(stat.header, stat.sizeBytes, maxSchemaVersion())?.let {
                return RevertPreparation.NotEligible(RevertEligibility.Unusable(it))
            }
            val bytes = copies.readAll() ?: return RevertPreparation.CopyUnreadable
            // Dibaca ulang dari isi sebenarnya: berkas bisa berubah antara stat dan baca.
            if (bytes.size.toLong() != stat.sizeBytes ||
                SqliteDatabaseCheck.check(bytes.copyOf(minOf(bytes.size, SqliteDatabaseCheck.HEADER_SIZE)), bytes.size.toLong(), maxSchemaVersion()) != null
            ) {
                return RevertPreparation.CopyUnreadable
            }
            val backup = emergency.take() ?: return RevertPreparation.EmergencyBackupFailed
            handedOver = true
            return RevertPreparation.Ready(PreRestoreCopyInfo(stat.sizeBytes, stat.lastModifiedMillis), backup, bytes)
        } finally {
            if (!handedOver) copies.unlock()
        }
    }

    /** Pengguna membatalkan setelah [prepare] berhasil: lepas kunci. Salinan darurat dibiarkan (sudah terlanjur dibuat, tidak berbahaya). */
    fun cancel(ready: RevertPreparation.Ready) {
        if (!ready.consumed) {
            ready.consumed = true
            copies.unlock()
        }
    }

    /**
     * Langkah 4, hanya setelah konfirmasi eksplisit. [apply] menyerahkan isi salinan ke penimpa berkas (lihat KDoc kelas).
     * Mengembalikan [RevertResult.Reverted] bila `apply` selesai tanpa galat; di aplikasi itu berarti penggantian berkas sudah
     * DISERAHKAN (penimpa membuka ulang ruang kerja secara asinkron).
     */
    suspend fun revert(ready: RevertPreparation.Ready, apply: suspend (ByteArray) -> Unit): RevertResult {
        if (ready.consumed) return RevertResult.AlreadyUsed
        ready.consumed = true
        var marked = false
        var applied = false
        try {
            if (!copies.markUsed()) return RevertResult.CopyGone
            marked = true
            apply(ready.bytes)
            applied = true
            return RevertResult.Reverted(ready.emergencyBackup)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return RevertResult.ApplyFailed(e.javaClass.simpleName)
        } finally {
            // Gagal sebelum berkas diganti: salinan dikembalikan agar tetap bisa dicoba lagi (ganti nama kembali).
            if (marked && !applied) copies.unmarkUsed()
            copies.unlock()
        }
    }
}

/** Salinan `.prerestore` seperti terlihat pengguna. */
data class PreRestoreCopyInfo(val sizeBytes: Long, val createdAtMillis: Long)

/** Hasil pemeriksaan berkas salinan: [stat]. [header] = sampai [SqliteDatabaseCheck.HEADER_SIZE] byte pertama, null bila tak terbaca. */
class PreRestoreCopyStat(val sizeBytes: Long, val lastModifiedMillis: Long, val header: ByteArray?)

/** Berkas salinan di penyimpanan privat aplikasi. Implementasi berkas ada di `:data`; tes memakai fake. Tidak ada yang melempar. */
interface PreRestoreCopyStore {
    /** Null = tidak ada salinan. */
    suspend fun stat(): PreRestoreCopyStat?

    /** Seluruh isi salinan; null bila hilang atau tak terbaca. */
    suspend fun readAll(): ByteArray?

    /** Kunci pakai: selama dipegang, pembersih tidak menghapus apa pun. False bila sudah dipegang (dan belum basi). */
    fun tryLock(): Boolean

    fun unlock()

    /** Ganti nama salinan menjadi "terpakai" (lepas dari menu); false bila salinan sudah tidak ada. */
    suspend fun markUsed(): Boolean

    /** Kembalikan [markUsed] (hanya bila penggantian berkas gagal sebelum terjadi). */
    suspend fun unmarkUsed()
}

sealed interface RevertEligibility {
    /** Tidak ada salinan: baris menu tidak tampil. */
    data object NoCopy : RevertEligibility

    /** Ada tetapi tidak boleh dikembalikan; layar menjelaskan alasannya. */
    data class Unusable(val reason: SqliteDatabaseCheck.Problem) : RevertEligibility

    data class Available(val copy: PreRestoreCopyInfo) : RevertEligibility
}

sealed interface RevertPreparation {
    /** Bukti bahwa kunci diambil, salinan dibaca dan valid, dan salinan darurat dibuat. Satu kali pakai: [PreRestoreRevertFlow.revert] atau `cancel`. */
    class Ready internal constructor(
        val copy: PreRestoreCopyInfo,
        /** Salinan darurat dari keadaan sebelum dikembalikan (`.prerevert`). */
        val emergencyBackup: LocalBackupHandle,
        internal val bytes: ByteArray,
    ) : RevertPreparation {
        internal var consumed = false
    }

    /** Pengembalian lain sedang berjalan (kunci dipegang). */
    data object Busy : RevertPreparation

    data class NotEligible(val why: RevertEligibility) : RevertPreparation

    data object CopyUnreadable : RevertPreparation

    data object EmergencyBackupFailed : RevertPreparation
}

sealed interface RevertResult {
    /** Isi salinan sudah diserahkan ke penimpa berkas. [emergencyBackup] menyimpan keadaan sebelumnya. */
    data class Reverted(val emergencyBackup: LocalBackupHandle) : RevertResult

    /** Salinan hilang di antara konfirmasi dan eksekusi (mis. dibersihkan); tidak ada yang berubah. */
    data object CopyGone : RevertResult

    /** Penimpa melempar sebelum menyelesaikan; [error] = nama kelas galat saja. Salinan dikembalikan agar bisa dicoba lagi. */
    data class ApplyFailed(val error: String) : RevertResult

    /** [PreRestoreRevertFlow.revert] dipanggil dua kali dengan [RevertPreparation.Ready] yang sama. */
    data object AlreadyUsed : RevertResult
}

/**
 * Pemeriksaan murni kepala berkas SQLite 100 byte pertama sebelum sebuah berkas dipakai menggantikan database aktif: bukan
 * pengganti `integrity_check`, tetapi mencegah dua kegagalan yang paling mungkin dan paling merusak (berkas terpotong atau
 * bukan SQLite; versi skema lebih baru dari yang dikenal aplikasi sehingga Room menolak membukanya dan aplikasi tak bisa
 * dipakai). Salinan lama dengan versi LEBIH RENDAH wajar: Room memigrasinya (dengan pagar `.premigrate`).
 */
object SqliteDatabaseCheck {
    const val HEADER_SIZE = 100

    enum class Problem { NOT_SQLITE, TRUNCATED, SCHEMA_TOO_NEW, EMPTY_SCHEMA }

    private val MAGIC = "SQLite format 3\u0000".encodeToByteArray()

    /** Null = layak. */
    fun check(header: ByteArray?, fileSize: Long, maxSchemaVersion: Int): Problem? {
        if (header == null || fileSize < HEADER_SIZE || header.size < HEADER_SIZE) return Problem.NOT_SQLITE
        for (i in MAGIC.indices) if (header[i] != MAGIC[i]) return Problem.NOT_SQLITE
        // Ukuran halaman: dua byte di offset 16, big-endian; nilai 1 berarti 65536.
        val rawPageSize = ((header[16].toInt() and 0xFF) shl 8) or (header[17].toInt() and 0xFF)
        val pageSize = if (rawPageSize == 1) 65_536 else rawPageSize
        if (pageSize < 512 || pageSize > 65_536 || (pageSize and (pageSize - 1)) != 0) return Problem.NOT_SQLITE
        if (fileSize < pageSize || fileSize % pageSize != 0L) return Problem.TRUNCATED
        // Versi skema pengguna (`PRAGMA user_version`, yang dipakai Room): empat byte di offset 60, big-endian.
        val version = ((header[60].toInt() and 0xFF) shl 24) or
            ((header[61].toInt() and 0xFF) shl 16) or
            ((header[62].toInt() and 0xFF) shl 8) or
            (header[63].toInt() and 0xFF)
        if (version <= 0) return Problem.EMPTY_SCHEMA
        if (version > maxSchemaVersion) return Problem.SCHEMA_TOO_NEW
        return null
    }
}
