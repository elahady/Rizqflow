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
    /** Mengunggah (atau mengganti) salinan terbungkus [wrappedKey]. Format [RECOVERY_KEY_VERSION] 1 = [AccountKeyRecoveryCrypto.wrap]. */
    suspend fun put(apiToken: String, wrappedKey: ByteArray): RemoteResult<RecoveryKeyReceipt>

    suspend fun get(apiToken: String): RemoteResult<RecoveryKeyLookup>

    suspend fun delete(apiToken: String): RemoteResult<Unit>
}

/** Versi format salinan terbungkus yang dikenal klien ini: 1 = [AccountKeyRecoveryCrypto.wrap] (BackupCrypto AES-256-GCM, PBKDF2). */
const val RECOVERY_KEY_VERSION = 1

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
