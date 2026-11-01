package com.roziqrizal.rizqflow.domain.sync

/**
 * Jeda antar percobaan sync otomatis yang gagal (Tahap 6 sync-desain.md bagian H). Murni tanpa coroutine dan tanpa
 * jam sendiri: waktu selalu dikirim pemanggil ([nowMs], monotonik), jadi seluruh perilakunya diuji dengan angka
 * biasa tanpa tidur nyata.
 *
 * - [RemoteError.Unreachable] dan galat lain (5xx, 4xx tak terduga, 422): berlipat dua dari [initialMs] sampai
 *   [maxMs] (30 dtk, 60, 120, 240, 480, lalu 15 menit seterusnya), dihitung per kegagalan BERUNTUN.
 * - Galat yang membawa `Retry-After` ([RemoteError.Unexpected.retryAfterSeconds], mis. 429): jeda mengikuti server,
 *   dijepit ke 1 dtk .. [retryAfterMaxMs] supaya header aneh tidak membekukan sync berjam-jam. Jeda ini tidak
 *   menaikkan hitungan berlipat dua (server yang menentukan, bukan tebakan klien).
 * - [reset] (sukses, atau sync manual) menghapus hitungan dan jeda.
 *
 * Bukan thread-safe: pemanggil ([AutoSyncOrchestrator]) memakainya di bawah kunci statenya sendiri.
 */
class SyncBackoff(
    private val initialMs: Long = 30_000L,
    private val maxMs: Long = 900_000L,
    private val retryAfterMaxMs: Long = 3_600_000L,
) {
    private var failures = 0
    private var blockedUntilMs: Long? = null

    /** Jumlah kegagalan beruntun yang memakai penundaan berlipat dua (bukan yang mengikuti `Retry-After`). */
    val consecutiveFailures: Int get() = failures

    fun isBlocked(nowMs: Long): Boolean = blockedUntilMs?.let { nowMs < it } ?: false

    /** Sisa jeda dalam milidetik; 0 bila tidak sedang menunggu. */
    fun remainingMs(nowMs: Long): Long = blockedUntilMs?.let { (it - nowMs).coerceAtLeast(0) } ?: 0L

    fun reset() {
        failures = 0
        blockedUntilMs = null
    }

    /** Mencatat satu kegagalan di [nowMs] dan mengembalikan jeda yang berlaku (milidetik). */
    fun recordFailure(error: RemoteError, nowMs: Long): Long {
        val retryAfterSeconds = (error as? RemoteError.Unexpected)?.retryAfterSeconds
        val delayMs = if (retryAfterSeconds != null) {
            // Detik besar dikalikan 1000 bisa meluap Long; jepit di detik dulu.
            (retryAfterSeconds.coerceIn(1L, retryAfterMaxMs / 1000L) * 1000L)
        } else {
            failures++
            exponentialDelayMs(failures)
        }
        blockedUntilMs = nowMs + delayMs
        return delayMs
    }

    private fun exponentialDelayMs(failureCount: Int): Long {
        // Eksponen dibatasi 20 supaya geser bit tak meluap; maxMs sudah jauh di bawahnya.
        val shift = (failureCount - 1).coerceIn(0, 20)
        return (initialMs shl shift).coerceAtMost(maxMs)
    }
}
