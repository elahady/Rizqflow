package com.roziqrizal.rizqflow.data.remote

import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import com.roziqrizal.rizqflow.domain.sync.SyncLogEntryDownload
import com.roziqrizal.rizqflow.domain.sync.SyncLogEntryUpload
import com.roziqrizal.rizqflow.domain.sync.SyncPullResult
import com.roziqrizal.rizqflow.domain.sync.SyncPushResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

/** [RetrofitSyncRemote] terhadap server HTTP sungguhan ([MockWebServer]), bukan mock interface. */
class RetrofitSyncRemoteTest {
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
    fun `push mengirim entries dan balas id serta cursor`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ids":[101,102],"cursor":102}"""),
        )
        val entries = listOf(
            SyncLogEntryUpload(
                roomId = "room-1",
                recordType = "transaction",
                recordId = "txn-1",
                deviceId = "pixel-9",
                payload = "ciphertext-base64",
                deleted = false,
                changedAt = "2026-10-23T10:00:00Z",
            ),
        )

        val result = remote.push(apiToken = "abc123", entries = entries)

        assertEquals(RemoteResult.Success(SyncPushResult(ids = listOf(101, 102), cursor = 102)), result)
        val sent = server.takeRequest()
        assertEquals("Bearer abc123", sent.getHeader("Authorization"))
        assertEquals(
            """{"entries":[{"room_id":"room-1","record_type":"transaction","record_id":"txn-1","device_id":"pixel-9","payload":"ciphertext-base64","deleted":false,"changed_at":"2026-10-23T10:00:00Z"}]}""",
            sent.body.readUtf8(),
        )
    }

    @Test
    fun `push baris tanpa room_id mengirim null, bukan field hilang`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("""{"ids":[1],"cursor":1}"""))
        val entries = listOf(
            SyncLogEntryUpload(
                roomId = null,
                recordType = "account",
                recordId = "acc-1",
                deviceId = "pixel-9",
                payload = "ciphertext",
                deleted = true,
                changedAt = "2026-10-23T10:00:00Z",
            ),
        )

        remote.push(apiToken = "abc123", entries = entries)

        val sent = server.takeRequest()
        assertEquals(
            """{"entries":[{"record_type":"account","record_id":"acc-1","device_id":"pixel-9","payload":"ciphertext","deleted":true,"changed_at":"2026-10-23T10:00:00Z"}]}""",
            sent.body.readUtf8(),
        )
    }

    @Test
    fun `push ditolak 403 tanpa langganan Sync`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"message":"Butuh langganan Sync."}"""),
        )

        val result = remote.push(apiToken = "abc123", entries = emptyList())

        assertEquals(RemoteResult.Failure(RemoteError.SyncRequired), result)
    }

    @Test
    fun `pull mengembalikan entries dengan kursor paginasi`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"entries":[{"id":5,"room_id":"room-1","record_type":"transaction","record_id":"txn-1","device_id":"pixel-9","payload":"ciphertext","deleted":false,"changed_at":"2026-10-23T10:00:00Z"}],"next_after":5,"has_more":false}""",
                ),
        )

        val result = remote.pull(apiToken = "abc123", after = 0, limit = 200)

        assertEquals(
            RemoteResult.Success(
                SyncPullResult(
                    entries = listOf(
                        SyncLogEntryDownload(
                            id = 5,
                            roomId = "room-1",
                            recordType = "transaction",
                            recordId = "txn-1",
                            deviceId = "pixel-9",
                            payload = "ciphertext",
                            deleted = false,
                            changedAt = "2026-10-23T10:00:00Z",
                        ),
                    ),
                    nextAfter = 5,
                    hasMore = false,
                ),
            ),
            result,
        )
        val sent = server.takeRequest()
        assertEquals("/sync/pull?after=0&limit=200", sent.path)
    }
}
