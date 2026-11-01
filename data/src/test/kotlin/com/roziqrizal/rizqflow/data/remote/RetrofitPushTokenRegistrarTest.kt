package com.roziqrizal.rizqflow.data.remote

import com.roziqrizal.rizqflow.domain.sync.PushTokenRegistration
import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

/**
 * [RetrofitPushTokenRegistrar] terhadap server HTTP sungguhan ([MockWebServer]). Kontraknya (server-sync.md "Push
 * notifikasi"): `PUT /push-token` badan persis `{token, device_id, platform:"android"}`, `DELETE /push-token/{device_id}`.
 */
class RetrofitPushTokenRegistrarTest {
    private lateinit var server: MockWebServer
    private lateinit var registrar: RetrofitPushTokenRegistrar

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        registrar = RetrofitPushTokenRegistrar(RizqflowApiClient.create(server.url("/").toString()))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `mendaftarkan token mengirim badan sesuai kontrak dengan bearer`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = registrar.register("api-rahasia", PushTokenRegistration("fcm-abc", "perangkat-1"))

        assertEquals(RemoteResult.Success(Unit), result)
        val sent = server.takeRequest()
        assertEquals("PUT", sent.method)
        assertEquals("/push-token", sent.path)
        assertEquals("Bearer api-rahasia", sent.getHeader("Authorization"))
        val body = Json.parseToJsonElement(sent.body.readUtf8()) as JsonObject
        assertEquals(
            JsonObject(
                mapOf(
                    "token" to JsonPrimitive("fcm-abc"),
                    "device_id" to JsonPrimitive("perangkat-1"),
                    "platform" to JsonPrimitive("android"),
                ),
            ),
            body,
        )
    }

    @Test
    fun `jawaban 200 dengan badan juga berhasil`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))

        assertEquals(RemoteResult.Success(Unit), registrar.register("api", PushTokenRegistration("fcm", "d")))
    }

    @Test
    fun `mencabut memanggil DELETE dengan device id di path`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = registrar.revoke("api-rahasia", "perangkat-1")

        assertEquals(RemoteResult.Success(Unit), result)
        val sent = server.takeRequest()
        assertEquals("DELETE", sent.method)
        assertEquals("/push-token/perangkat-1", sent.path)
        assertEquals("Bearer api-rahasia", sent.getHeader("Authorization"))
    }

    @Test
    fun `galat server dipetakan seperti remote lain`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"message":"Token tidak sah."}"""))
        server.enqueue(MockResponse().setResponseCode(404))

        assertEquals(RemoteResult.Failure(RemoteError.Unauthorized), registrar.register("api", PushTokenRegistration("fcm", "d")))
        assertEquals(RemoteResult.Failure(RemoteError.SyncRequired), registrar.register("api", PushTokenRegistration("fcm", "d")))
        assertEquals(RemoteResult.Failure(RemoteError.Rejected("Token tidak sah.")), registrar.register("api", PushTokenRegistration("fcm", "d")))
        assertEquals(RemoteResult.Failure(RemoteError.Unexpected(404)), registrar.revoke("api", "d"))
    }

    @Test
    fun `server mati menjadi Unreachable, bukan crash`() = runBlocking {
        server.shutdown()

        assertEquals(RemoteResult.Failure(RemoteError.Unreachable), registrar.register("api", PushTokenRegistration("fcm", "d")))
    }
}
