package com.roziqrizal.rizqflow.data.remote

import com.roziqrizal.rizqflow.domain.sync.RecoveryKeyLookup
import com.roziqrizal.rizqflow.domain.sync.RecoveryKeyReceipt
import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** [RetrofitRecoveryKeyRemote] terhadap server HTTP sungguhan ([MockWebServer]), badan persis kontrak. */
class RetrofitRecoveryKeyRemoteTest {
    private lateinit var server: MockWebServer
    private lateinit var remote: RetrofitRecoveryKeyRemote

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        remote = RetrofitRecoveryKeyRemote(RizqflowApiClient.create(server.url("/").toString()))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    // Bytes sengaja menghasilkan karakter plus dan garis miring serta butuh padding sama dengan di base64 standar (bukan URL-safe).
    private val wrapped = byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0xbf.toByte(), 0x3e, 0x3f, 0x01, 0x02)

    @Test
    fun `put mengirim PUT dengan badan persis kontrak (base64 standar dan version 1) dan bearer token`() = runBlocking {
        server.enqueue(json("""{"version":1,"updated_at":"2026-10-25T10:00:00Z"}"""))

        val result = remote.put("abc123", wrapped)

        assertEquals(RemoteResult.Success(RecoveryKeyReceipt(1, "2026-10-25T10:00:00Z")), result)
        val sent = server.takeRequest()
        assertEquals("PUT", sent.method)
        assertEquals("/account/recovery-key", sent.path)
        assertEquals("Bearer abc123", sent.getHeader("Authorization"))
        assertTrue(sent.getHeader("Content-Type")!!.startsWith("application/json"))
        val body = Json.parseToJsonElement(sent.body.readUtf8()).jsonObject
        assertEquals(setOf("wrapped_key", "version"), body.keys)
        assertEquals(1, body.getValue("version").jsonPrimitive.int)
        val wire = body.getValue("wrapped_key").jsonPrimitive.content
        assertEquals(Base64.getEncoder().encodeToString(wrapped), wire)
        assertTrue(wire.contains('+') && wire.contains('/') && wire.endsWith("="), "harus base64 standar dengan padding: $wire")
        assertEquals(wrapped.toList(), Base64.getDecoder().decode(wire).toList())
    }

    @Test
    fun `put tanpa langganan Sync dipetakan jelas ke SyncRequired`() = runBlocking {
        server.enqueue(json("""{"message":"Butuh langganan Sync."}""", 403))

        assertEquals(RemoteResult.Failure(RemoteError.SyncRequired), remote.put("t", wrapped))
    }

    @Test
    fun `put 422 membawa pesan server`() = runBlocking {
        server.enqueue(json("""{"message":"Salinan tidak sah."}""", 422))

        assertEquals(RemoteResult.Failure(RemoteError.Rejected("Salinan tidak sah.")), remote.put("t", wrapped))
    }

    @Test
    fun `put 401 menjadi Unauthorized`() = runBlocking {
        server.enqueue(json("""{"message":"Unauthenticated."}""", 401))

        assertEquals(RemoteResult.Failure(RemoteError.Unauthorized), remote.put("t", wrapped))
    }

    @Test
    fun `put jaringan putus menjadi Unreachable`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertEquals(RemoteResult.Failure(RemoteError.Unreachable), remote.put("t", wrapped))
    }

    @Test
    fun `put dengan badan 200 yang bukan JSON kontrak menjadi Unexpected, bukan crash`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>proxy</html>"))

        assertEquals(RemoteResult.Failure(RemoteError.Unexpected(200)), remote.put("t", wrapped))
    }

    @Test
    fun `get sukses mendekode base64 standar menjadi bytes asli`() = runBlocking {
        val wire = Base64.getEncoder().encodeToString(wrapped)
        server.enqueue(json("""{"wrapped_key":"$wire","version":1,"updated_at":"2026-10-25T10:00:00Z"}"""))

        val result = remote.get("abc123")

        assertEquals(RemoteResult.Success(RecoveryKeyLookup.Found(wrapped, 1, "2026-10-25T10:00:00Z")), result)
        val sent = server.takeRequest()
        assertEquals("GET", sent.method)
        assertEquals("/account/recovery-key", sent.path)
        assertEquals("Bearer abc123", sent.getHeader("Authorization"))
    }

    @Test
    fun `get 404 dengan penanda no_recovery_key adalah hasil biasa NotFound, bukan galat`() = runBlocking {
        server.enqueue(json("""{"message":"Belum ada kode pemulihan","code":"no_recovery_key"}""", 404))

        assertEquals(RemoteResult.Success(RecoveryKeyLookup.NotFound), remote.get("t"))
    }

    @Test
    fun `get 404 tanpa penanda (rute belum dideploy, proxy, server lama) adalah galat server belum siap, bukan belum ada kode`() = runBlocking {
        // Laravel 404 rute tak dikenal (JSON), halaman HTML proxy, badan kosong, JSON tanpa code, dan code lain.
        server.enqueue(json("""{"message":"The route api/account/recovery-key could not be found."}""", 404))
        server.enqueue(MockResponse().setResponseCode(404).setHeader("Content-Type", "text/html").setBody("<html>404 Not Found</html>"))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(json("""{"message":"Belum ada kode pemulihan"}""", 404))
        server.enqueue(json("""{"message":"x","code":"route_not_found"}""", 404))

        repeat(5) { i ->
            assertEquals(RemoteResult.Failure(RemoteError.Unexpected(404)), remote.get("t"), "kasus ke-$i")
        }
    }

    @Test
    fun `penanda no_recovery_key hanya dipercaya pada 404`() = runBlocking {
        server.enqueue(json("""{"message":"x","code":"no_recovery_key"}""", 500))

        assertEquals(RemoteResult.Failure(RemoteError.Unexpected(500)), remote.get("t"))
    }

    @Test
    fun `toString DTO kode pemulihan tidak mencetak isi bungkusan`() {
        val blob = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo="

        assertTrue(!RecoveryKeyPutDto(blob, 1).toString().contains(blob))
        assertTrue(!RecoveryKeyDto(blob, 1, "2026-10-25T10:00:00Z").toString().contains(blob))
        assertEquals("RecoveryKeyPutDto(version=1, wrappedKey=disembunyikan)", RecoveryKeyPutDto(blob, 1).toString())
        assertEquals("RecoveryKeyDto(version=1, updatedAt=t, wrappedKey=disembunyikan)", RecoveryKeyDto(blob, 1, "t").toString())
    }

    @Test
    fun `serialisasi JSON tetap membawa isi penuh walau toString disembunyikan`() {
        val blob = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo="

        // toString yang disembunyikan tidak boleh mengubah isi yang dikirim.
        val wire = Json.encodeToString(RecoveryKeyPutDto.serializer(), RecoveryKeyPutDto(blob, 1))
        assertTrue(wire.contains(blob))
    }

    @Test
    fun `get 403 401 dan 500 dipetakan seperti endpoint lain`() = runBlocking {
        server.enqueue(json("""{"message":"x"}""", 403))
        server.enqueue(json("""{"message":"x"}""", 401))
        server.enqueue(json("""{"message":"x"}""", 500))

        assertEquals(RemoteResult.Failure(RemoteError.SyncRequired), remote.get("t"))
        assertEquals(RemoteResult.Failure(RemoteError.Unauthorized), remote.get("t"))
        assertEquals(RemoteResult.Failure(RemoteError.Unexpected(500)), remote.get("t"))
    }

    @Test
    fun `get dengan base64 rusak atau badan bukan JSON menjadi Unexpected`() = runBlocking {
        server.enqueue(json("""{"wrapped_key":"***bukan base64***","version":1,"updated_at":"x"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("bukan json"))

        assertEquals(RemoteResult.Failure(RemoteError.Unexpected(200)), remote.get("t"))
        assertEquals(RemoteResult.Failure(RemoteError.Unexpected(200)), remote.get("t"))
    }

    @Test
    fun `get jaringan putus menjadi Unreachable`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertEquals(RemoteResult.Failure(RemoteError.Unreachable), remote.get("t"))
    }

    @Test
    fun `get mengembalikan versi apa adanya agar pemanggil yang memutuskan`() = runBlocking {
        server.enqueue(json("""{"wrapped_key":"AQID","version":7,"updated_at":"x"}"""))

        val found = assertIs<RemoteResult.Success<RecoveryKeyLookup>>(remote.get("t")).value
        assertEquals(7, assertIs<RecoveryKeyLookup.Found>(found).version)
    }

    @Test
    fun `delete 204 sukses dan memakai DELETE ke jalur yang benar`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        assertEquals(RemoteResult.Success(Unit), remote.delete("abc123"))
        val sent = server.takeRequest()
        assertEquals("DELETE", sent.method)
        assertEquals("/account/recovery-key", sent.path)
        assertEquals("Bearer abc123", sent.getHeader("Authorization"))
    }

    @Test
    fun `delete 401 menjadi Unauthorized`() = runBlocking {
        server.enqueue(json("""{"message":"x"}""", 401))

        assertEquals(RemoteResult.Failure(RemoteError.Unauthorized), remote.delete("t"))
    }
}
