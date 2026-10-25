package com.roziqrizal.rizqflow.data.remote

import com.roziqrizal.rizqflow.domain.sync.ApiAccount
import com.roziqrizal.rizqflow.domain.sync.RefreshedToken
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    // --- Masa berlaku token (Tahap 12) ---

    @Test
    fun `login memuat expires_at dari server`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"token":"abc123","expires_at":"2027-01-22T10:00:00Z","user":{"id":7,"name":"Roziq","email":"roziq@example.com"}}"""),
        )

        val result = remote.loginWithGoogle(idToken = "google-id-token", deviceId = "pixel-9")

        assertEquals(
            RemoteResult.Success(
                ApiAccount(
                    apiToken = "abc123",
                    userId = 7,
                    name = "Roziq",
                    email = "roziq@example.com",
                    expiresAt = Instant.parse("2027-01-22T10:00:00Z"),
                ),
            ),
            result,
        )
    }

    @Test
    fun `login dari server lama tanpa expires_at tetap sukses dengan masa berlaku null`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"token":"abc123","user":{"id":7,"name":null,"email":"roziq@example.com"}}"""),
        )

        val result = remote.loginWithGoogle(idToken = "google-id-token", deviceId = "pixel-9")

        val account = assertIs<RemoteResult.Success<ApiAccount>>(result).value
        assertEquals("abc123", account.apiToken)
        assertNull(account.expiresAt)
    }

    @Test
    fun `expires_at berbentuk offset dan yang tak terbaca ditangani tanpa menggagalkan login`() {
        assertEquals(Instant.parse("2027-01-22T10:00:00Z"), parseExpiresAt("2027-01-22T10:00:00+00:00"))
        assertEquals(Instant.parse("2027-01-22T03:00:00Z"), parseExpiresAt("2027-01-22T10:00:00+07:00"))
        assertEquals(Instant.parse("2027-01-22T10:00:00.5Z"), parseExpiresAt(" 2027-01-22T10:00:00.5Z "))
        // Bentuk bawaan Carbon/Laravel (toJSON): enam digit pecahan detik dan akhiran Z.
        assertEquals(Instant.parse("2027-01-22T10:00:00Z"), parseExpiresAt("2027-01-22T10:00:00.000000Z"))
        assertEquals(Instant.parse("2027-01-22T10:00:00.123456Z"), parseExpiresAt("2027-01-22T10:00:00.123456+00:00"))
        assertNull(parseExpiresAt(null))
        assertNull(parseExpiresAt(""))
        assertNull(parseExpiresAt("   "))
        assertNull(parseExpiresAt("besok lusa"))
        assertNull(parseExpiresAt("2027-13-45T99:00:00Z"))
    }

    // --- Putar token ---

    @Test
    fun `refresh mengirim POST kosong dengan bearer token lama dan mengembalikan token baru`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"token":"token-baru","expires_at":"2027-01-22T10:00:00Z"}"""),
        )

        val result = remote.refresh(apiToken = "token-lama")

        assertEquals(
            RemoteResult.Success(RefreshedToken(apiToken = "token-baru", expiresAt = Instant.parse("2027-01-22T10:00:00Z"))),
            result,
        )
        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/auth/refresh", sent.path)
        assertEquals("Bearer token-lama", sent.getHeader("Authorization"))
        assertEquals(0L, sent.bodySize)
    }

    @Test
    fun `refresh tanpa expires_at tetap berhasil dengan masa berlaku null`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"token":"token-baru"}"""),
        )

        val result = remote.refresh(apiToken = "token-lama")

        assertEquals(RemoteResult.Success(RefreshedToken(apiToken = "token-baru", expiresAt = null)), result)
    }

    @Test
    fun `refresh dengan token tidak sah atau kedaluwarsa mengembalikan Unauthorized`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Unauthenticated."}"""))

        val result = remote.refresh(apiToken = "token-kedaluwarsa")

        assertEquals(RemoteResult.Failure(RemoteError.Unauthorized), result)
    }

    @Test
    fun `refresh ke server yang sedang gangguan mengembalikan Unexpected dengan kodenya`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(429))

        assertEquals(RemoteResult.Failure(RemoteError.Unexpected(503)), remote.refresh(apiToken = "t"))
        assertEquals(RemoteResult.Failure(RemoteError.Unexpected(429)), remote.refresh(apiToken = "t"))
    }

    @Test
    fun `refresh dengan badan 200 yang bukan JSON kontrak jadi kegagalan biasa, bukan crash`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>halaman pemeliharaan</html>"))

        val result = remote.refresh(apiToken = "token-lama")

        assertEquals(RemoteResult.Failure(RemoteError.Unexpected(200)), result)
    }

    @Test
    fun `refresh saat server tidak terjangkau mengembalikan Unreachable`() = runBlocking {
        server.shutdown()

        assertEquals(RemoteResult.Failure(RemoteError.Unreachable), remote.refresh(apiToken = "t"))
    }

    // --- Keluar (cabut token ini saja) ---

    @Test
    fun `logout mengirim POST kosong dengan bearer token dan 204 berarti sukses`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = remote.logout(apiToken = "abc123")

        assertEquals(RemoteResult.Success(Unit), result)
        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/auth/logout", sent.path)
        assertEquals("Bearer abc123", sent.getHeader("Authorization"))
        assertEquals(0L, sent.bodySize)
    }

    @Test
    fun `logout dengan token yang sudah tidak sah mengembalikan Unauthorized`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))

        assertEquals(RemoteResult.Failure(RemoteError.Unauthorized), remote.logout(apiToken = "token-lama"))
    }

    @Test
    fun `logout ke server yang gangguan mengembalikan Unexpected dengan kodenya`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))

        assertEquals(RemoteResult.Failure(RemoteError.Unexpected(503)), remote.logout(apiToken = "t"))
    }

    @Test
    fun `logout saat server tidak terjangkau mengembalikan Unreachable, bukan melempar`() = runBlocking {
        server.shutdown()

        assertEquals(RemoteResult.Failure(RemoteError.Unreachable), remote.logout(apiToken = "t"))
    }

    // --- Masuk dengan email+sandi (Tahap 14) ---

    @Test
    fun `masuk email sukses mengirim email, sandi, dan device_id lalu membaca token dan masa berlakunya`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"token":"abc123","expires_at":"2027-01-22T10:00:00Z","user":{"id":7,"name":"Roziq","email":"roziq@example.com"}}"""),
        )

        val result = remote.loginWithEmail(email = "roziq@example.com", password = "kopi-susu", deviceId = "pixel-9")

        assertEquals(
            RemoteResult.Success(
                ApiAccount(
                    apiToken = "abc123",
                    userId = 7,
                    name = "Roziq",
                    email = "roziq@example.com",
                    expiresAt = Instant.parse("2027-01-22T10:00:00Z"),
                ),
            ),
            result,
        )
        val sent = server.takeRequest()
        assertEquals("/auth/login", sent.path)
        assertEquals("""{"email":"roziq@example.com","password":"kopi-susu","device_id":"pixel-9"}""", sent.body.readUtf8())
    }

    @Test
    fun `masuk email yang ditolak 422 membawa pesan Bahasa Indonesia dari server`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(422)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"message":"Email atau kata sandi salah.","errors":{"email":["Email atau kata sandi salah."]}}"""),
        )

        val result = remote.loginWithEmail(email = "roziq@example.com", password = "salah", deviceId = "pixel-9")

        assertEquals(RemoteResult.Failure(RemoteError.Rejected("Email atau kata sandi salah.")), result)
    }

    @Test
    fun `masuk email saat server gangguan mengembalikan Unexpected dengan kodenya`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        assertEquals(RemoteResult.Failure(RemoteError.Unexpected(500)), remote.loginWithEmail("a@b.c", "x", "pixel-9"))
    }

    @Test
    fun `masuk email saat server tidak terjangkau mengembalikan Unreachable, bukan melempar`() = runBlocking {
        server.shutdown()

        assertEquals(RemoteResult.Failure(RemoteError.Unreachable), remote.loginWithEmail("a@b.c", "x", "pixel-9"))
    }

    @Test
    fun `sandi tidak pernah tercetak lewat toString permintaan masuk email`() {
        val body = EmailLoginRequestDto(email = "roziq@example.com", password = "rahasia-banget", deviceId = "pixel-9")

        assertFalse(body.toString().contains("rahasia-banget"))
        assertTrue(body.toString().contains("roziq@example.com"))
    }
}
