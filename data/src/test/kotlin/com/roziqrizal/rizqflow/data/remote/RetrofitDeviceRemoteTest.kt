package com.roziqrizal.rizqflow.data.remote

import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import com.roziqrizal.rizqflow.domain.sync.ServerDevice
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

/**
 * [RetrofitDeviceRemote] dan badan `device_name` di [RetrofitAccountRemote] terhadap server HTTP sungguhan
 * ([MockWebServer]). Kontraknya: `GET /auth/tokens` (metadata saja), `DELETE /auth/tokens` (204), dan
 * `device_name` ikut saat masuk dan memutar token (Tahap 14).
 */
class RetrofitDeviceRemoteTest {
    private lateinit var server: MockWebServer
    private lateinit var devices: RetrofitDeviceRemote

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        devices = RetrofitDeviceRemote(RizqflowApiClient.create(server.url("/").toString()))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `daftar perangkat dipetakan dan memakai token sebagai bearer`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"devices":[
                        {"id":12,"label":"Android","device_name":"Samsung SM-S911B","created_at":"2026-10-20T10:00:00Z",
                         "last_used_at":"2026-10-26T09:00:00Z","expires_at":"2027-01-18T10:00:00Z","current":true},
                        {"id":13,"label":"Perangkat lain","device_name":null,"created_at":"2026-10-01T10:00:00Z",
                         "last_used_at":null,"expires_at":"2026-10-20T10:00:00Z","current":false}
                    ]}""".trimIndent(),
                ),
        )

        val result = devices.listDevices("token-rahasia")

        val list = assertIs<RemoteResult.Success<List<ServerDevice>>>(result).value
        assertEquals(2, list.size)
        assertEquals(
            ServerDevice(
                id = 12,
                label = "Android",
                deviceName = "Samsung SM-S911B",
                createdAt = Instant.parse("2026-10-20T10:00:00Z"),
                lastUsedAt = Instant.parse("2026-10-26T09:00:00Z"),
                expiresAt = Instant.parse("2027-01-18T10:00:00Z"),
                current = true,
            ),
            list[0],
        )
        assertNull(list[1].deviceName)
        assertNull(list[1].lastUsedAt)
        assertFalse(list[1].current)

        val sent = server.takeRequest()
        assertEquals("GET", sent.method)
        assertEquals("/auth/tokens", sent.path)
        assertEquals("Bearer token-rahasia", sent.getHeader("Authorization"))
    }

    @Test
    fun `waktu yang tak terbaca dan nama kosong tidak menggagalkan daftar`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"devices":[{"id":1,"label":"","device_name":"  ","created_at":"bukan-tanggal","current":false}]}"""),
        )

        val list = assertIs<RemoteResult.Success<List<ServerDevice>>>(devices.listDevices("t")).value

        assertEquals("Perangkat lain", list.single().label)
        assertNull(list.single().deviceName)
        assertNull(list.single().createdAt)
    }

    @Test
    fun `badan 200 yang bukan kontrak menjadi galat tak terduga, bukan crash`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>proxy</html>"))

        val result = devices.listDevices("t")

        assertEquals(RemoteResult.Failure(RemoteError.Unexpected(200)), result)
    }

    @Test
    fun `401 saat daftar dijawab sebagai tidak sah`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json").setBody("""{"message":"Unauthenticated."}"""))

        assertEquals(RemoteResult.Failure(RemoteError.Unauthorized), devices.listDevices("basi"))
    }

    @Test
    fun `keluar dari semua perangkat mengirim DELETE dan sukses saat 204`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = devices.revokeAllDevices("token-hp")

        assertEquals(RemoteResult.Success(Unit), result)
        val sent = server.takeRequest()
        assertEquals("DELETE", sent.method)
        assertEquals("/auth/tokens", sent.path)
        assertEquals("Bearer token-hp", sent.getHeader("Authorization"))
    }

    @Test
    fun `keluar dari semua perangkat yang gagal menjawab galat server`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        assertEquals(RemoteResult.Failure(RemoteError.Unexpected(500)), devices.revokeAllDevices("token-hp"))
    }

    @Test
    fun `masuk dan putar token membawa device_name, dan tanpa nama badannya kosong`() = runBlocking {
        val account = RetrofitAccountRemote(RizqflowApiClient.create(server.url("/").toString()), deviceName = "Google Pixel 8")
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("""{"token":"baru","user":{"id":7,"name":"Roziq","email":"roziq@example.com"}}"""),
        )
        account.loginWithGoogle(idToken = "id-token", deviceId = "pixel-9")
        assertTrue(server.takeRequest().body.readUtf8().contains(""""device_name":"Google Pixel 8""""))

        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("""{"token":"putar"}"""))
        account.refresh("lama")
        val refreshed = server.takeRequest()
        assertEquals("/auth/refresh", refreshed.path)
        assertEquals("""{"device_name":"Google Pixel 8"}""", refreshed.body.readUtf8())

        // Tanpa nama (klien lama), badan refresh KOSONG seperti sebelum Tahap 14.
        val plain = RetrofitAccountRemote(RizqflowApiClient.create(server.url("/").toString()))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("""{"token":"putar2"}"""))
        plain.refresh("lama")
        assertEquals(0L, server.takeRequest().bodySize)
    }
}
