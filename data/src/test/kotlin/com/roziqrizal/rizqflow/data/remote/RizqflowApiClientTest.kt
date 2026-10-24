package com.roziqrizal.rizqflow.data.remote

import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Batas waktu klien HTTP: jaringan yang menggantung tidak boleh menahan putar token atau klaim pembelian tanpa ujung. */
class RizqflowApiClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `batas waktu seluruh panggilan 30 detik`() {
        assertEquals(30_000, RizqflowApiClient.httpClient().callTimeoutMillis)
    }

    @Test
    fun `jawaban yang menetes pelan dipotong batas waktu panggilan dan menjadi Unreachable`() = runBlocking {
        // Badan dikirim 1 byte per 150 ms: tiap byte datang jauh di bawah readTimeout (15 detik), jadi HANYA
        // batas waktu seluruh panggilan yang bisa menghentikannya. Tanpa itu panggilan berlangsung ±9 detik lalu sukses.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"token":"abc123","expires_at":null,"pad":"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"}""")
                .throttleBody(1, 150, TimeUnit.MILLISECONDS),
        )
        val remote = RetrofitAccountRemote(
            RizqflowApiClient.create(server.url("/").toString(), RizqflowApiClient.httpClient(callTimeoutMillis = 500)),
        )

        val started = System.nanoTime()
        val result = remote.refresh("token-lama")
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertEquals(RemoteResult.Failure(RemoteError.Unreachable), result)
        assertTrue(elapsedMillis < 4_000, "panggilan harus dipotong oleh batas waktu, bukan menunggu badan selesai ($elapsedMillis ms)")
    }
}
