package com.roziqrizal.rizqflow.domain.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Jeda sync otomatis: murni angka, tanpa jam nyata (Tahap 6 sync-desain.md bagian H). */
class SyncBackoffTest {
    private val unreachable = RemoteError.Unreachable

    @Test
    fun `kegagalan beruntun berlipat dua dari 30 detik sampai 15 menit lalu bertahan`() {
        val backoff = SyncBackoff()
        val delays = (1..9).map { backoff.recordFailure(unreachable, nowMs = 0) }

        assertEquals(
            listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 900_000L, 900_000L, 900_000L, 900_000L),
            delays,
        )
        assertEquals(9, backoff.consecutiveFailures)
    }

    @Test
    fun `5xx dan galat tak terduga lain memakai jeda berlipat dua yang sama`() {
        val backoff = SyncBackoff()

        assertEquals(30_000L, backoff.recordFailure(RemoteError.Unexpected(503), 0))
        assertEquals(60_000L, backoff.recordFailure(RemoteError.Unexpected(500), 0))
        assertEquals(120_000L, backoff.recordFailure(RemoteError.Rejected("kuota"), 0))
    }

    @Test
    fun `terblokir tepat sampai jeda habis lalu bebas, sisa jeda menurun`() {
        val backoff = SyncBackoff()
        backoff.recordFailure(unreachable, nowMs = 1_000)

        assertTrue(backoff.isBlocked(1_000))
        assertTrue(backoff.isBlocked(30_999))
        assertEquals(10_000L, backoff.remainingMs(21_000))
        assertFalse(backoff.isBlocked(31_000))
        assertEquals(0L, backoff.remainingMs(31_000))
    }

    @Test
    fun `reset menghapus hitungan dan jeda`() {
        val backoff = SyncBackoff()
        repeat(4) { backoff.recordFailure(unreachable, 0) }

        backoff.reset()

        assertFalse(backoff.isBlocked(0))
        assertEquals(0, backoff.consecutiveFailures)
        assertEquals(30_000L, backoff.recordFailure(unreachable, 0), "setelah reset mulai lagi dari 30 detik")
    }

    @Test
    fun `429 mengikuti Retry-After dan tidak menaikkan hitungan berlipat dua`() {
        val backoff = SyncBackoff()

        val delay = backoff.recordFailure(RemoteError.Unexpected(429, retryAfterSeconds = 120), nowMs = 5_000)

        assertEquals(120_000L, delay)
        assertTrue(backoff.isBlocked(124_999))
        assertFalse(backoff.isBlocked(125_000))
        assertEquals(0, backoff.consecutiveFailures)
        assertEquals(30_000L, backoff.recordFailure(unreachable, 200_000), "Retry-After tidak ikut menaikkan eksponen")
    }

    @Test
    fun `Retry-After dijepit antara 1 detik dan batas atas`() {
        val backoff = SyncBackoff(retryAfterMaxMs = 3_600_000L)

        assertEquals(1_000L, backoff.recordFailure(RemoteError.Unexpected(429, retryAfterSeconds = 0), 0))
        assertEquals(3_600_000L, backoff.recordFailure(RemoteError.Unexpected(429, retryAfterSeconds = 999_999_999_999L), 0))
    }

    @Test
    fun `429 tanpa Retry-After jatuh ke jeda berlipat dua`() {
        val backoff = SyncBackoff()

        assertEquals(30_000L, backoff.recordFailure(RemoteError.Unexpected(429), 0))
        assertEquals(60_000L, backoff.recordFailure(RemoteError.Unexpected(429), 0))
    }
}
