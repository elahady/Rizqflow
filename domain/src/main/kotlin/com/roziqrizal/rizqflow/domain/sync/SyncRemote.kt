package com.roziqrizal.rizqflow.domain.sync

/**
 * Push/pull log sinkronisasi append-only (Tahap 9, server-sync.md "Model konflik sinkronisasi").
 * Server menyimpan [SyncLogEntryUpload.payload] apa adanya (ciphertext buram, dienkripsi lewat
 * [SyncAead] sebelum dikirim ke sini) dan tidak pernah memutuskan siapa menang saat konflik — itu
 * pekerjaan pemanggil setelah [pull] (last-write-wins dari `changedAt`, dikerjakan di aplikasi
 * setelah mendekripsi), bukan kontrak ini. Kontrak murni tanpa Retrofit/OkHttp; implementasi
 * sungguhan (`RetrofitSyncRemote`) ada di `:data`.
 */
interface SyncRemote {
    /** Maksimal 500 baris per panggilan (dipaksa juga oleh server). */
    suspend fun push(apiToken: String, entries: List<SyncLogEntryUpload>): RemoteResult<SyncPushResult>

    /**
     * [after] = 0 di perangkat baru sekaligus jadi restore dari backup, karena seluruh riwayat
     * perubahan ada di log ini. Hasil dipaginasi; ulangi pemanggilan dengan [SyncPullResult.nextAfter]
     * selama [SyncPullResult.hasMore] true.
     */
    suspend fun pull(apiToken: String, after: Long = 0, limit: Int = 200): RemoteResult<SyncPullResult>
}

/** Satu baris yang dikirim ke server lewat [SyncRemote.push]. [payload] sudah terenkripsi pemanggil. */
data class SyncLogEntryUpload(
    val roomId: String?,
    val recordType: String,
    val recordId: String,
    val deviceId: String,
    val payload: String,
    val deleted: Boolean,
    val changedAt: String,
)

data class SyncPushResult(val ids: List<Long>, val cursor: Long)

/** Satu baris yang diterima dari server lewat [SyncRemote.pull]. [payload] masih terenkripsi. */
data class SyncLogEntryDownload(
    val id: Long,
    val roomId: String?,
    val recordType: String,
    val recordId: String,
    val deviceId: String,
    val payload: String,
    val deleted: Boolean,
    val changedAt: String,
)

data class SyncPullResult(
    val entries: List<SyncLogEntryDownload>,
    val nextAfter: Long,
    val hasMore: Boolean,
)
