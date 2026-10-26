package com.roziqrizal.rizqflow.domain.sync

/**
 * Salinan kunci akun yang terbungkus kode pemulihan, disimpan di server (Tahap 13, server-sync.md
 * bagian "Model kunci"). Kontrak murni tanpa Retrofit/OkHttp; implementasi sungguhan
 * (`RetrofitRecoveryKeyRemote`) ada di `:data`. Server hanya menyimpan bytes buram: ia tidak pernah
 * melihat kode 24 kata maupun kunci terbaca.
 *
 * - [put] butuh langganan Sync: tanpa itu jawabannya [RemoteResult.Failure] dengan [RemoteError.SyncRequired].
 * - [get] tidak butuh Sync. Belum adanya salinan BUKAN galat: hasilnya [RemoteResult.Success] dengan
 *   [RecoveryKeyLookup.NotFound], karena perangkat baru memang wajar belum menemukan apa pun.
 * - 401 ditangani satu tempat (`ServerSession`), bukan di sini maupun di pemanggil.
 */
interface RecoveryKeyRemote {
    /** Mengunggah (atau mengganti) salinan terbungkus [wrappedKey], selalu format [RECOVERY_KEY_VERSION] (yang DITULIS klien ini). */
    suspend fun put(apiToken: String, wrappedKey: ByteArray): RemoteResult<RecoveryKeyReceipt>

    suspend fun get(apiToken: String): RemoteResult<RecoveryKeyLookup>

    suspend fun delete(apiToken: String): RemoteResult<Unit>
}

/**
 * Versi format salinan terbungkus yang DITULIS klien ini sekarang (sejak 2026-10-27, roadmap.md
 * Tahap 13 "Masukkan kunci ruang keluarga..."): isi terbuka (plaintext, sebelum dibungkus
 * [AccountKeyRecoveryCrypto.wrap]) adalah [RecoveryBundle] — kunci akun PLUS kunci semua ruang
 * keluarga aktif yang diikuti perangkat ini saat [RecoveryKeyService.enableRecovery] dipanggil.
 * Kriptonya sendiri (AES-256-GCM, kunci PBKDF2 dari kode 24 kata) tidak berubah dari versi 1.
 */
const val RECOVERY_KEY_VERSION = 2

/**
 * Versi format LAMA (sebelum 2026-10-27): isi terbuka adalah bytes mentah
 * [AccountKeyProvider.exportAccountKeyForRecovery] TANPA struktur apa pun — hanya kunci akun, kunci
 * ruang keluarga tidak ikut. Klien ini TIDAK PERNAH menulis versi ini lagi (belum ada pengguna
 * sungguhan yang pernah memakainya, lihat roadmap.md); dipertahankan hanya supaya
 * [RecoveryKeyService.recoverKey] tidak mogok bila suatu saat menemukan salinan lama ini.
 */
const val RECOVERY_KEY_VERSION_LEGACY_ACCOUNT_KEY_ONLY = 1

/** Tanda terima unggahan: [version] dan [updatedAt] (ISO-8601 UTC) persis dari jawaban server. */
data class RecoveryKeyReceipt(val version: Int, val updatedAt: String)

sealed interface RecoveryKeyLookup {
    /** Belum ada salinan di server untuk akun ini. */
    data object NotFound : RecoveryKeyLookup

    /**
     * Salinan ada. Kelas biasa (bukan data class) supaya [wrappedKey] dibandingkan isinya dan [toString]
     * tidak mencetak bytes; salinan ini sudah terbungkus kode pemulihan, tapi tak ada alasan membiarkannya di log.
     */
    class Found(val wrappedKey: ByteArray, val version: Int, val updatedAt: String) : RecoveryKeyLookup {
        override fun equals(other: Any?): Boolean =
            other is Found && version == other.version && updatedAt == other.updatedAt && wrappedKey.contentEquals(other.wrappedKey)

        override fun hashCode(): Int = 31 * (31 * version + updatedAt.hashCode()) + wrappedKey.contentHashCode()

        override fun toString(): String = "Found(version=$version, updatedAt=$updatedAt, wrappedKey=${wrappedKey.size} byte)"
    }
}
