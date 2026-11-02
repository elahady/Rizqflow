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

    /**
     * Pull per cakupan (Tahap 9 lanjutan, docs/server-sync.md "Pull per cakupan dan waktu per cakupan", bagian
     * "Kontrak klien untuk agen Android berikutnya"). [scope] = `"a"` (pribadi, [roomId] WAJIB null) atau `"r"`
     * ([roomId] WAJIB id ruang). Bawaan antarmuka ini (dipakai implementasi/tiruan yang belum diperbarui untuk pull
     * ber-cakupan) hanya meneruskan ke [pull] LAMA tanpa `scope`/`room_id` — mensimulasikan server lama dengan setia:
     * [SyncPullResult.scope]/[SyncPullResult.roomId] pada hasilnya tetap null, dan pemanggil WAJIB memeriksa gema itu
     * (kontrak klien butir 3: tanpa gema, jangan menerapkan barisnya sebagai satu cakupan — pakai pull lama, kursor
     * global) sebelum mempercayai bahwa jawabannya sudah tersaring server. [RetrofitSyncRemote] menimpa ini untuk
     * benar-benar mengirim param dan membaca gema server; [PersonalSyncCoordinator] dan [SyncCoordinator] memanggil
     * ini (bukan [pull]) untuk pull cakupan pribadi/ruang bersama.
     */
    suspend fun pullScoped(
        apiToken: String,
        deviceId: String,
        scope: String,
        roomId: String?,
        after: Long = 0,
        limit: Int = 200,
    ): RemoteResult<SyncPullResult> = pull(apiToken, deviceId, after, limit)
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

/**
 * [scope]/[roomId] (Tahap 9 lanjutan): GEMA dari `scope`/`room_id` yang diminta [SyncRemote.pullScoped], hanya terisi
 * oleh server yang mendukung pull per cakupan. Null dari server lama atau dari [SyncRemote.pull] biasa — pemanggil
 * yang meminta pull ber-cakupan WAJIB memeriksa field ini sama dengan yang diminta sebelum menganggap [entries] sudah
 * tersaring satu cakupan (kontrak klien butir 3, docs/server-sync.md).
 */
data class SyncPullResult(
    val entries: List<SyncLogEntryDownload>,
    val nextAfter: Long,
    val hasMore: Boolean,
    val serverTimeMs: Long? = null,
    val scope: String? = null,
    val roomId: String? = null,
)
