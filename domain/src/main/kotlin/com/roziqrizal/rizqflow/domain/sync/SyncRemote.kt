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
     * selama [SyncPullResult.hasMore] true. [deviceId] (Tahap 2 sync-desain.md bagian G) dicatat
     * server di `sync_devices` sebagai kursor per perangkat; dasar retensi/kedaluwarsa kursor Tahap 7.
     */
    suspend fun pull(apiToken: String, deviceId: String, after: Long = 0, limit: Int = 200): RemoteResult<SyncPullResult>
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
    /**
     * Cap waktu milidetik (Tahap 2 sync-desain.md bagian G): tambahan presisi di atas [changedAt]
     * (`timestamp` server berhenti di detik). Null hanya dari pemanggil lama; [SyncCoordinator]
     * selalu mengisinya.
     */
    val changedAtMillis: Long? = null,
)

/** [serverTimeMs] (Tahap 2, bagian G): jam server saat menjawab, dasar [SyncClock.setServerOffset]. */
data class SyncPushResult(val ids: List<Long>, val cursor: Long, val serverTimeMs: Long? = null)

/**
 * Satu baris yang diterima dari server lewat [SyncRemote.pull]. [payload] masih terenkripsi.
 * [author] (Tahap 2, bagian B R3): pengenal buram penulis PERTAMA baris ini (ditentukan server, bukan
 * klien) — dasar penulis tunggal; null dari server lama. [changedAtMillis] sama dengan [changedAt]
 * tapi presisi milidetik; null dari server lama (fallback: parse [changedAt]).
 */
data class SyncLogEntryDownload(
    val id: Long,
    val roomId: String?,
    val recordType: String,
    val recordId: String,
    val deviceId: String,
    val payload: String,
    val deleted: Boolean,
    val changedAt: String,
    val changedAtMillis: Long? = null,
    val author: String? = null,
)

data class SyncPullResult(
    val entries: List<SyncLogEntryDownload>,
    val nextAfter: Long,
    val hasMore: Boolean,
    val serverTimeMs: Long? = null,
)
