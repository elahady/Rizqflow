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
    fun `members membaca bendera pemilik dan user id dari server`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"members":[{"user_id":7,"name":"Roziq","email":"roziq@example.com","you":true,"owner":true},{"user_id":9,"name":null,"email":"partner@example.com","you":false,"owner":false}]}"""),
        )

        val result = remote.members(apiToken = "abc123", roomId = "ruang-keluarga-1")

        assertEquals(
            RemoteResult.Success(
                listOf(
                    RoomMemberInfo("Roziq", "roziq@example.com", you = true, owner = true, userId = 7),
                    RoomMemberInfo(null, "partner@example.com", you = false, owner = false, userId = 9),
                ),
            ),
            result,
        )
    }

    @Test
    fun `members membaca member_id buram dari server, null bila server lama belum mengirimnya`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"members":[{"user_id":7,"name":"Roziq","email":"roziq@example.com","you":true,"owner":true,"member_id":"abc123"},""" +
                        """{"user_id":9,"name":null,"email":"partner@example.com","you":false,"owner":false}]}""",
                ),
        )

        val result = remote.members(apiToken = "abc123", roomId = "ruang-keluarga-1")

        assertEquals(
            RemoteResult.Success(
                listOf(
                    RoomMemberInfo("Roziq", "roziq@example.com", you = true, owner = true, userId = 7, memberId = "abc123"),
                    RoomMemberInfo(null, "partner@example.com", you = false, owner = false, userId = 9, memberId = null),
                ),
            ),
            result,
        )
    }

    @Test
    fun `remove mengirim DELETE ke anggota yang dipilih`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = remote.remove(apiToken = "abc123", roomId = "ruang-keluarga-1", userId = 9)

        assertEquals(RemoteResult.Success(Unit), result)
        val sent = server.takeRequest()
        assertEquals("DELETE", sent.method)
        assertEquals("/rooms/ruang-keluarga-1/members/9", sent.path)
        assertEquals("Bearer abc123", sent.getHeader("Authorization"))
    }

    @Test
    fun `remove oleh bukan pemilik ditolak tanpa membuka paywall`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"message":"Hanya pemilik ruang yang bisa mengeluarkan anggota.","code":"not_room_owner"}"""),
        )

        val result = remote.remove(apiToken = "abc123", roomId = "ruang-keluarga-1", userId = 9)

        assertEquals(
            RemoteResult.Failure(RemoteError.Rejected("Hanya pemilik ruang yang bisa mengeluarkan anggota.", "not_room_owner")),
            result,
        )
    }

    @Test
    fun `remove tanpa langganan Sync tetap SyncRequired, dengan atau tanpa kode`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"Butuh langganan Sync."}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"Butuh langganan Sync.","code":"sync_required"}"""))

        assertEquals(RemoteResult.Failure(RemoteError.SyncRequired), remote.remove("abc123", "ruang-keluarga-1", 9))
        assertEquals(RemoteResult.Failure(RemoteError.SyncRequired), remote.remove("abc123", "ruang-keluarga-1", 9))
    }

    @Test
    fun `members oleh bukan anggota berkode ditolak tanpa membuka paywall`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"Bukan anggota ruang ini.","code":"not_room_member"}"""))

        val result = remote.members(apiToken = "abc123", roomId = "ruang-keluarga-1")

        assertEquals(RemoteResult.Failure(RemoteError.Rejected("Bukan anggota ruang ini.", "not_room_member")), result)
    }

    @Test
    fun `members 403 tanpa kode dari server lama tetap SyncRequired demi kompatibilitas`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"Bukan anggota ruang ini."}"""))

        val result = remote.members(apiToken = "abc123", roomId = "ruang-keluarga-1")

        assertEquals(RemoteResult.Failure(RemoteError.SyncRequired), result)
    }

    @Test
    fun `remove anggota yang tidak ada dan pemilik yang dikeluarkan dipetakan per kode`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Anggota tidak ditemukan.","code":"member_not_found"}"""))
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"message":"Pemilik tidak bisa dikeluarkan.","code":"owner_cannot_be_removed"}"""))

        assertEquals(
            RemoteResult.Failure(RemoteError.Rejected("Anggota tidak ditemukan.", "member_not_found")),
            remote.remove("abc123", "ruang-keluarga-1", 9),
        )
        assertEquals(
            RemoteResult.Failure(RemoteError.Rejected("Pemilik tidak bisa dikeluarkan.", "owner_cannot_be_removed")),
            remote.remove("abc123", "ruang-keluarga-1", 7),
        )
    }

    @Test
    fun `leave sukses mengirim POST bertoken ke ruang yang benar`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = remote.leave(apiToken = "abc123", roomId = "ruang-keluarga-1")

        assertEquals(RemoteResult.Success(Unit), result)
        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/rooms/ruang-keluarga-1/leave", sent.path)
        assertEquals("Bearer abc123", sent.getHeader("Authorization"))
    }

    @Test
    fun `leave oleh pemilik ditolak 422 owner_cannot_leave`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"message":"Pemilik ruang tidak bisa keluar.","code":"owner_cannot_leave"}"""))

        val result = remote.leave(apiToken = "abc123", roomId = "ruang-keluarga-1")

        assertEquals(RemoteResult.Failure(RemoteError.Rejected("Pemilik ruang tidak bisa keluar.", "owner_cannot_leave")), result)
    }

    @Test
    fun `leave oleh bukan anggota 404 not_room_member menjadi Rejected, bukan galat tak terduga`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Bukan anggota ruang ini.","code":"not_room_member"}"""))

        val result = remote.leave(apiToken = "abc123", roomId = "ruang-keluarga-1")

        assertEquals(RemoteResult.Failure(RemoteError.Rejected("Bukan anggota ruang ini.", "not_room_member")), result)
    }

    @Test
    fun `leave 404 polos tanpa kode tetap galat tak terduga`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}"""))

        val result = remote.leave(apiToken = "abc123", roomId = "ruang-keluarga-1")

        assertEquals(RemoteResult.Failure(RemoteError.Unexpected(404)), result)
    }

    @Test
    fun `leave 401 adalah Unauthorized dan 429 membawa Retry-After`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Unauthenticated.","code":"unauthenticated"}"""))
        server.enqueue(
            MockResponse().setResponseCode(429).setHeader("Retry-After", "20")
                .setBody("""{"message":"Terlalu banyak permintaan.","code":"rate_limited","retry_after":20}"""),
        )

        assertEquals(RemoteResult.Failure(RemoteError.Unauthorized), remote.leave("abc123", "ruang-keluarga-1"))
        assertEquals(RemoteResult.Failure(RemoteError.Unexpected(429, 20L)), remote.leave("abc123", "ruang-keluarga-1"))
    }

    @Test
    fun `leave tanpa jaringan mengembalikan Unreachable`() = runBlocking {
        server.shutdown()

        assertEquals(RemoteResult.Failure(RemoteError.Unreachable), remote.leave("abc123", "ruang-keluarga-1"))
    }

    @Test
    fun `transferOwnership sukses mengirim POST bertoken ke anggota yang dipilih`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = remote.transferOwnership(apiToken = "abc123", roomId = "ruang-keluarga-1", targetUserId = 9)

        assertEquals(RemoteResult.Success(Unit), result)
        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/rooms/ruang-keluarga-1/members/9/make-owner", sent.path)
        assertEquals("Bearer abc123", sent.getHeader("Authorization"))
    }

    @Test
    fun `transferOwnership oleh bukan pemilik ditolak not_room_owner tanpa membuka paywall`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"message":"Hanya pemilik ruang yang bisa memindahkan kepemilikan.","code":"not_room_owner"}"""),
        )

        val result = remote.transferOwnership(apiToken = "abc123", roomId = "ruang-keluarga-1", targetUserId = 9)

        assertEquals(
            RemoteResult.Failure(RemoteError.Rejected("Hanya pemilik ruang yang bisa memindahkan kepemilikan.", "not_room_owner")),
            result,
        )
    }

    @Test
    fun `transferOwnership ke anggota yang sudah pemilik 422 already_owner, dan target tak dikenal 404 member_not_found`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"message":"Anggota itu sudah menjadi pemilik ruang.","code":"already_owner"}"""))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Anggota tidak ditemukan di ruang ini.","code":"member_not_found"}"""))

        assertEquals(
            RemoteResult.Failure(RemoteError.Rejected("Anggota itu sudah menjadi pemilik ruang.", "already_owner")),
            remote.transferOwnership("abc123", "ruang-keluarga-1", 7),
        )
        assertEquals(
            RemoteResult.Failure(RemoteError.Rejected("Anggota tidak ditemukan di ruang ini.", "member_not_found")),
            remote.transferOwnership("abc123", "ruang-keluarga-1", 9),
        )
    }

    @Test
    fun `transferOwnership tanpa langganan Sync tetap SyncRequired`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"Butuh langganan Sync.","code":"sync_required"}"""))

        assertEquals(RemoteResult.Failure(RemoteError.SyncRequired), remote.transferOwnership("abc123", "ruang-keluarga-1", 9))
    }

    @Test
    fun `server tidak terjangkau mengembalikan Unreachable, bukan melempar exception`() = runBlocking {
        server.shutdown()

        val result = remote.join(apiToken = "abc123", roomId = "ruang-keluarga-1")

        assertEquals(RemoteResult.Failure(RemoteError.Unreachable), result)
    }
}
