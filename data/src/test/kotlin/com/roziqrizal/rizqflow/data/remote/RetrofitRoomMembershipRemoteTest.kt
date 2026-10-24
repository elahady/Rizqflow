package com.roziqrizal.rizqflow.data.remote

import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import com.roziqrizal.rizqflow.domain.sync.RoomMemberInfo
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

/** [RetrofitRoomMembershipRemote] terhadap server HTTP sungguhan ([MockWebServer]), bukan mock interface. */
class RetrofitRoomMembershipRemoteTest {
    private lateinit var server: MockWebServer
    private lateinit var remote: RetrofitRoomMembershipRemote

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        remote = RetrofitRoomMembershipRemote(RizqflowApiClient.create(server.url("/").toString()))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `join sukses mengirim bearer token ke roomId yang benar`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = remote.join(apiToken = "abc123", roomId = "ruang-keluarga-1")

        assertEquals(RemoteResult.Success(Unit), result)
        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/rooms/ruang-keluarga-1/join", sent.path)
        assertEquals("Bearer abc123", sent.getHeader("Authorization"))
    }

    @Test
    fun `join tanpa langganan Sync mengembalikan SyncRequired`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"Butuh langganan Sync."}"""))

        val result = remote.join(apiToken = "abc123", roomId = "ruang-keluarga-1")

        assertEquals(RemoteResult.Failure(RemoteError.SyncRequired), result)
    }

    @Test
    fun `members sukses mengembalikan daftar anggota`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"members":[{"name":"Roziq","email":"roziq@example.com","you":true},{"name":null,"email":"partner@example.com","you":false}]}"""),
        )

        val result = remote.members(apiToken = "abc123", roomId = "ruang-keluarga-1")

        assertEquals(
            RemoteResult.Success(
                listOf(
                    RoomMemberInfo("Roziq", "roziq@example.com", you = true),
                    RoomMemberInfo(null, "partner@example.com", you = false),
                ),
            ),
            result,
        )
        val sent = server.takeRequest()
        assertEquals("GET", sent.method)
        assertEquals("/rooms/ruang-keluarga-1/members", sent.path)
    }

    @Test
    fun `members menolak bukan anggota`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"Bukan anggota ruang ini."}"""))

        val result = remote.members(apiToken = "abc123", roomId = "ruang-keluarga-1")

        assertEquals(RemoteResult.Failure(RemoteError.SyncRequired), result)
    }

    @Test
    fun `server tidak terjangkau mengembalikan Unreachable, bukan melempar exception`() = runBlocking {
        server.shutdown()

        val result = remote.join(apiToken = "abc123", roomId = "ruang-keluarga-1")

        assertEquals(RemoteResult.Failure(RemoteError.Unreachable), result)
    }
}
