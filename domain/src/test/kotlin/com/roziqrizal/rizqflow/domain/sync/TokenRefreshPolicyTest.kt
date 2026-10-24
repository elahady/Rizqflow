package com.roziqrizal.rizqflow.domain.sync

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Keputusan memutar token API: waktu disuntikkan, jadi tidak ada tes yang bergantung pada jam sungguhan. */
class TokenRefreshPolicyTest {
    private val now = Instant.parse("2026-11-01T00:00:00Z")

    private fun due(expiresAt: Instant?, hasToken: Boolean = true, at: Instant = now) =
        TokenRefreshPolicy.isDue(hasToken, expiresAt, at)

    @Test
    fun `jendela putar token adalah 30 hari`() {
        assertEquals(Duration.ofDays(30), TokenRefreshPolicy.REFRESH_WINDOW)
    }

    @Test
    fun `token masih jauh dari kedaluwarsa tidak diputar`() {
        assertFalse(due(now.plus(Duration.ofDays(89))))
        assertFalse(due(now.plus(Duration.ofDays(31))))
        assertFalse(due(now.plus(Duration.ofDays(30)).plusSeconds(1)))
    }

    @Test
    fun `token yang kedaluwarsa dalam 30 hari atau lebih dekat diputar`() {
        assertTrue(due(now.plus(Duration.ofDays(30)))) // tepat di batas ikut diputar
        assertTrue(due(now.plus(Duration.ofDays(29))))
        assertTrue(due(now.plus(Duration.ofDays(1))))
        assertTrue(due(now.plusSeconds(1)))
    }

    @Test
    fun `token yang menurut jam ponsel sudah lewat tetap dicoba, server yang memutuskan lewat 401`() {
        assertTrue(due(now))
        assertTrue(due(now.minus(Duration.ofDays(3))))
    }

    @Test
    fun `masa berlaku belum diketahui berarti diputar sekali supaya token berikutnya membawa tanggalnya`() {
        assertTrue(due(expiresAt = null))
    }

    @Test
    fun `tanpa token tidak ada yang diputar, apa pun tanggalnya`() {
        assertFalse(due(expiresAt = null, hasToken = false))
        assertFalse(due(expiresAt = now.plusSeconds(1), hasToken = false))
        assertFalse(due(expiresAt = now.minus(Duration.ofDays(1)), hasToken = false))
    }

    // --- Jeda minimum antar putar ---

    private fun dueSince(savedAt: Instant?, expiresAt: Instant?, at: Instant = now) =
        TokenRefreshPolicy.isDue(hasToken = true, expiresAt = expiresAt, now = at, lastSavedAt = savedAt)

    @Test
    fun `jeda minimum antar putar adalah 1 hari`() {
        assertEquals(Duration.ofDays(1), TokenRefreshPolicy.MIN_INTERVAL)
    }

    @Test
    fun `token yang baru diterima tidak diputar lagi walau masa berlakunya tampak dekat`() {
        // Jam ponsel jauh ke depan: masa berlaku selalu tampak sudah lewat, tapi token baru diterima 1 jam lalu.
        val expired = now.minus(Duration.ofDays(400))
        assertFalse(dueSince(now.minus(Duration.ofHours(1)), expired))
        assertFalse(dueSince(now.minus(Duration.ofHours(23)), expiresAt = null))
        assertFalse(dueSince(now, expired), "diterima tepat sekarang")
    }

    @Test
    fun `sesudah jeda minimum lewat keputusan kembali mengikuti masa berlaku`() {
        val savedAt = now.minus(Duration.ofDays(1))
        assertTrue(dueSince(savedAt, now.plus(Duration.ofDays(5))), "tepat 1 hari: boleh")
        assertTrue(dueSince(now.minus(Duration.ofDays(2)), expiresAt = null))
        assertFalse(dueSince(savedAt, now.plus(Duration.ofDays(60))), "jeda lewat tapi masa berlaku masih jauh")
    }

    @Test
    fun `waktu terima di masa depan menurut jam sekarang diabaikan, bukan menahan putar`() {
        val bogus = now.plus(Duration.ofDays(900)) // jam ponsel sempat melompat lalu diperbaiki
        assertTrue(dueSince(bogus, now.plus(Duration.ofDays(5))))
    }

    @Test
    fun `waktu terima tidak diketahui tidak menahan putar`() {
        assertTrue(dueSince(savedAt = null, expiresAt = now.plus(Duration.ofDays(5))))
    }

    @Test
    fun `jeda minimum tidak membuat token tanpa token diputar`() {
        assertFalse(TokenRefreshPolicy.isDue(hasToken = false, expiresAt = null, now = now, lastSavedAt = now.minus(Duration.ofDays(9))))
    }

    @Test
    fun `keputusan mengikuti jam yang disuntikkan, bukan jam sungguhan`() {
        val expiresAt = Instant.parse("2027-01-22T10:00:00Z")

        assertFalse(due(expiresAt, at = Instant.parse("2026-11-01T00:00:00Z"))) // 82 hari lagi
        assertTrue(due(expiresAt, at = Instant.parse("2026-12-25T00:00:00Z"))) // 28 hari lagi
        assertTrue(due(expiresAt, at = Instant.parse("2027-02-01T00:00:00Z"))) // sudah lewat
    }
}
