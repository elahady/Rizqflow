package com.roziqrizal.rizqflow.data.remote

import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Header `Retry-After` untuk backoff sync otomatis: dibaca sebagai detik atau tanggal HTTP, tak terbaca berarti null. */
class RetryAfterTest {
    private val now: Instant = Instant.parse("2026-11-01T07:28:00Z")

    @Test
    fun `detik bulat dibaca apa adanya`() {
        assertEquals(120L, parseRetryAfterSeconds("120", now))
        assertEquals(30L, parseRetryAfterSeconds("  30 ", now))
        assertEquals(0L, parseRetryAfterSeconds("0", now))
    }

    @Test
    fun `tanggal HTTP dihitung selisihnya dari sekarang`() {
        assertEquals(30L, parseRetryAfterSeconds("Sun, 01 Nov 2026 07:28:30 GMT", now))
    }

    @Test
    fun `tanggal yang sudah lewat, angka negatif, dan sampah menghasilkan null`() {
        assertNull(parseRetryAfterSeconds("Sun, 01 Nov 2026 07:27:00 GMT", now))
        assertNull(parseRetryAfterSeconds("-5", now))
        assertNull(parseRetryAfterSeconds("segera", now))
        assertNull(parseRetryAfterSeconds("", now))
        assertNull(parseRetryAfterSeconds(null, now))
    }

    @Test
    fun `hanya galat tak terduga yang membawa Retry-After, 401 403 dan 422 tidak`() {
        assertEquals(RemoteError.Unexpected(429, 60L), mapHttpError(429, null, "60", now))
        assertEquals(RemoteError.Unexpected(503, null), mapHttpError(503, null, null, now))
        assertEquals(RemoteError.Unauthorized, mapHttpError(401, null, "60", now))
        assertEquals(RemoteError.SyncRequired, mapHttpError(403, null, "60", now))
    }

    private lateinit var server: MockWebServer
    private lateinit var remote: RetrofitSyncRemote

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        remote = RetrofitSyncRemote(RizqflowApiClient.create(server.url("/").toString()))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `jawaban 429 dari server membawa Retry-After sampai ke RemoteError`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "45").setBody("{}"))

        val result = remote.pull(apiToken = "t", deviceId = "d", after = 0)

        assertEquals(RemoteResult.Failure(RemoteError.Unexpected(429, 45L)), result)
    }
}
