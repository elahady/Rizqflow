package com.roziqrizal.rizqflow.data.remote

import com.roziqrizal.rizqflow.domain.sync.ApiAccount
import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

/** [RetrofitAccountRemote] terhadap server HTTP sungguhan ([MockWebServer]), bukan mock interface. */
class RetrofitAccountRemoteTest {
    private lateinit var server: MockWebServer
    private lateinit var remote: RetrofitAccountRemote

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        remote = RetrofitAccountRemote(RizqflowApiClient.create(server.url("/").toString()))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `login sukses mengembalikan token dan data akun`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"token":"abc123","user":{"id":7,"name":"Roziq","email":"roziq@example.com"}}"""),
        )

        val result = remote.loginWithGoogle(idToken = "google-id-token", deviceId = "pixel-9")

        assertEquals(
            RemoteResult.Success(ApiAccount(apiToken = "abc123", userId = 7, name = "Roziq", email = "roziq@example.com")),
            result,
        )
        val sent = server.takeRequest()
        assertEquals("/auth/google", sent.path)
        assertEquals("""{"credential":"google-id-token","device_id":"pixel-9"}""", sent.body.readUtf8())
    }

    @Test
    fun `login gagal 422 mengembalikan pesan server`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(422)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"message":"Masuk dengan Google gagal."}"""),
        )

        val result = remote.loginWithGoogle(idToken = "token-rusak", deviceId = "pixel-9")

        assertEquals(RemoteResult.Failure(RemoteError.Rejected("Masuk dengan Google gagal.")), result)
    }

    @Test
    fun `hapus akun sukses mengirim bearer token yang benar`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = remote.deleteAccount(apiToken = "abc123")

        assertEquals(RemoteResult.Success(Unit), result)
        val sent = server.takeRequest()
        assertEquals("DELETE", sent.method)
        assertEquals("Bearer abc123", sent.getHeader("Authorization"))
    }

    @Test
    fun `hapus akun dengan token kedaluwarsa mengembalikan Unauthorized`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = remote.deleteAccount(apiToken = "token-lama")

        assertEquals(RemoteResult.Failure(RemoteError.Unauthorized), result)
    }

    @Test
    fun `server tidak terjangkau mengembalikan Unreachable, bukan melempar exception`() = runBlocking {
        server.shutdown() // server mati sebelum sempat dipanggil, bukan time out

        val result = remote.deleteAccount(apiToken = "abc123")

        assertEquals(RemoteResult.Failure(RemoteError.Unreachable), result)
    }
}
