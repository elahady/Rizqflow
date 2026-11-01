package com.roziqrizal.rizqflow.data.remote

import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.domain.sync.isRateLimited
import com.roziqrizal.rizqflow.domain.sync.rateLimitRetryAfterSeconds
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pemetaan badan galat server `{message, code, retry_after}` ke [RemoteError] (Tahap 9). Tanpa jaringan: [mapHttpError]
 * adalah fungsi murni. Aturan inti: HANYA 403 `sync_required` (dan 403 tanpa kode, server lama) yang menjadi
 * `SyncRequired` (paywall Sync); kode lain tidak pernah membuka paywall.
 */
class ServerErrorMappingTest {
    private val now: Instant = Instant.parse("2026-11-01T07:28:00Z")

    private fun map(status: Int, body: String?, retryAfterHeader: String? = null): RemoteError =
        mapHttpError(status, body, retryAfterHeader, now)

    @Test
    fun `403 sync_required dan 403 tanpa kode membuka paywall Sync`() {
        assertEquals(RemoteError.SyncRequired, map(403, """{"message":"Butuh langganan Sync.","code":"sync_required"}"""))
        assertEquals(RemoteError.SyncRequired, map(403, """{"message":"Butuh langganan Sync."}"""))
        assertEquals(RemoteError.SyncRequired, map(403, null))
        assertEquals(RemoteError.SyncRequired, map(403, "bukan json"))
        assertEquals(RemoteError.SyncRequired, map(403, """{"message":"x","code":null}"""))
        assertEquals(RemoteError.SyncRequired, map(403, """{"message":"x","code":"  "}"""))
    }

    @Test
    fun `403 berkode lain menjadi Rejected berkode, bukan paywall`() {
        assertEquals(
            RemoteError.Rejected("Bukan anggota ruang ini.", "not_room_member"),
            map(403, """{"message":"Bukan anggota ruang ini.","code":"not_room_member"}"""),
        )
        assertEquals(
            RemoteError.Rejected("Hanya pemilik.", "not_room_owner"),
            map(403, """{"message":"Hanya pemilik.","code":"not_room_owner"}"""),
        )
        // Kode 403 generik buatan Laravel dan kode yang belum dikenal klien juga tidak membuka paywall.
        assertEquals(RemoteError.Rejected("Dilarang.", "forbidden"), map(403, """{"message":"Dilarang.","code":"forbidden"}"""))
        assertEquals(RemoteError.Rejected("Permintaan ditolak server.", "kode_baru"), map(403, """{"code":"kode_baru"}"""))
    }

    @Test
    fun `kode yang bukan teks diabaikan dan 403-nya tetap dianggap tanpa kode`() {
        assertEquals(RemoteError.SyncRequired, map(403, """{"message":"x","code":403}"""))
        assertEquals(RemoteError.SyncRequired, map(403, """{"message":"x","code":{"a":1}}"""))
    }

    @Test
    fun `404 dan 422 berkode keanggotaan menjadi Rejected, 404 polos tetap Unexpected`() {
        assertEquals(RemoteError.Rejected("Bukan anggota.", "not_room_member"), map(404, """{"message":"Bukan anggota.","code":"not_room_member"}"""))
        assertEquals(RemoteError.Rejected("Tidak ada.", "member_not_found"), map(404, """{"message":"Tidak ada.","code":"member_not_found"}"""))
        assertEquals(RemoteError.Rejected("Pemilik.", "owner_cannot_leave"), map(422, """{"message":"Pemilik.","code":"owner_cannot_leave"}"""))
        assertEquals(RemoteError.Rejected("Pemilik.", "owner_cannot_be_removed"), map(422, """{"message":"Pemilik.","code":"owner_cannot_be_removed"}"""))
        assertEquals(RemoteError.Rejected("Id salah.", "invalid_room_id"), map(422, """{"message":"Id salah.","code":"invalid_room_id"}"""))
        assertEquals(RemoteError.Unexpected(404), map(404, """{"message":"Not Found","code":"not_found"}"""))
        assertEquals(RemoteError.Unexpected(404), map(404, null))
    }

    @Test
    fun `422 tanpa kode tetap Rejected dengan pesan server atau pesan bawaan`() {
        assertEquals(RemoteError.Rejected("Email sudah dipakai."), map(422, """{"message":"Email sudah dipakai."}"""))
        assertEquals(RemoteError.Rejected("Permintaan ditolak server."), map(422, null))
        assertEquals(
            RemoteError.Rejected("Data tidak sah.", "validation_failed"),
            map(422, """{"message":"Data tidak sah.","code":"validation_failed","errors":{"email":["x"]}}"""),
        )
    }

    @Test
    fun `401 selalu Unauthorized`() {
        assertEquals(RemoteError.Unauthorized, map(401, """{"message":"x","code":"unauthenticated"}"""))
    }

    @Test
    fun `429 membawa Retry-After dari header lebih dulu, lalu dari badan`() {
        val body = """{"message":"Terlalu banyak permintaan.","code":"rate_limited","retry_after":42}"""
        assertEquals(RemoteError.Unexpected(429, 15L), map(429, body, "15"))
        assertEquals(RemoteError.Unexpected(429, 42L), map(429, body, null))
        // Header tak terbaca jatuh ke badan.
        assertEquals(RemoteError.Unexpected(429, 42L), map(429, body, "segera"))
        assertEquals(RemoteError.Unexpected(429, null), map(429, """{"code":"rate_limited"}""", null))
        assertEquals(RemoteError.Unexpected(429, null), map(429, null, null))
    }

    @Test
    fun `retry_after di badan yang bukan angka bulat tidak negatif dianggap tidak ada`() {
        assertEquals(RemoteError.Unexpected(429, null), map(429, """{"retry_after":"12"}"""))
        assertEquals(RemoteError.Unexpected(429, null), map(429, """{"retry_after":-3}"""))
        assertEquals(RemoteError.Unexpected(429, null), map(429, """{"retry_after":1.5}"""))
        assertEquals(RemoteError.Unexpected(429, null), map(429, """{"retry_after":null}"""))
        assertEquals(RemoteError.Unexpected(429, 0L), map(429, """{"retry_after":0}"""))
    }

    @Test
    fun `503 dengan retry_after di badan ikut terbawa`() {
        assertEquals(RemoteError.Unexpected(503, 30L), map(503, """{"message":"Maintenance","code":"server_error","retry_after":30}"""))
        assertEquals(RemoteError.Unexpected(500, null), map(500, """{"message":"Server error","code":"server_error"}"""))
    }

    @Test
    fun `isRateLimited hanya untuk 429`() {
        assertTrue(map(429, null).isRateLimited())
        assertEquals(20L, map(429, null, "20").rateLimitRetryAfterSeconds())
        assertNull(map(429, null).rateLimitRetryAfterSeconds())
        assertFalse(map(503, null, "20").isRateLimited())
        assertNull(map(503, null, "20").rateLimitRetryAfterSeconds())
        assertFalse(RemoteError.Unreachable.isRateLimited())
        assertFalse(RemoteError.Rejected("x", "rate_limited").isRateLimited())
    }
}
