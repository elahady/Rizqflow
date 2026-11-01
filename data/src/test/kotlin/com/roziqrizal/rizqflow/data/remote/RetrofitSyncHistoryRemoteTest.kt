package com.roziqrizal.rizqflow.data.remote

import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import com.roziqrizal.rizqflow.domain.sync.ServerSyncDevice
import com.roziqrizal.rizqflow.domain.sync.ServerSyncHistory
import com.roziqrizal.rizqflow.domain.sync.SyncCompactionInfo
import com.roziqrizal.rizqflow.domain.sync.SyncQuotaUsage
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * [RetrofitSyncHistoryRemote] terhadap server HTTP sungguhan ([MockWebServer]). Kontraknya `GET /api/sync/history`
 * (`Api\SyncHistoryController@show`): hanya metadata, tanpa payload atau isi transaksi.
 */
class RetrofitSyncHistoryRemoteTest {
    private lateinit var server: MockWebServer
    private lateinit var remote: RetrofitSyncHistoryRemote

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        remote = RetrofitSyncHistoryRemote(RizqflowApiClient.create(server.url("/").toString()))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun `riwayat dipetakan dari jawaban server dan memakai token sebagai bearer`() = runBlocking {
        server.enqueue(
            json(
                """{
                    "devices":[
                        {"device_id":"hp-a","rows":12,"last_push_at":"2026-11-01T08:00:00Z","last_pull_at":"2026-11-01T08:00:05Z","cursor":40,"active":true},
                        {"device_id":"hp-b","rows":0,"last_push_at":null,"last_pull_at":null,"cursor":null,"active":false}
                    ],
                    "quota":{"rows":12,"bytes":3456,"max_rows":250000,"max_bytes":157286400},
                    "compaction":{"last_run_at":"2026-10-31T21:55:00Z","last_removed_versions":3,"last_removed_tombstones":1},
                    "server_time_ms":1793520000000
                }""",
            ),
        )

        val result = remote.history("token-rahasia")

        assertEquals(
            RemoteResult.Success(
                ServerSyncHistory(
                    devices = listOf(
                        ServerSyncDevice("hp-a", 12, Instant.parse("2026-11-01T08:00:00Z"), Instant.parse("2026-11-01T08:00:05Z"), 40, true),
                        ServerSyncDevice("hp-b", 0, null, null, null, false),
                    ),
                    quota = SyncQuotaUsage(rows = 12, bytes = 3456, maxRows = 250_000, maxBytes = 157_286_400),
                    compaction = SyncCompactionInfo(Instant.parse("2026-10-31T21:55:00Z"), removedVersions = 3, removedTombstones = 1),
                    serverTimeMs = 1_793_520_000_000,
                ),
            ),
            result,
        )
        val sent = server.takeRequest()
        assertEquals("GET", sent.method)
        assertEquals("/sync/history", sent.path)
        assertEquals("Bearer token-rahasia", sent.getHeader("Authorization"))
    }

    @Test
    fun `bidang yang hilang atau waktu yang rusak tidak menggagalkan jawaban`() = runBlocking {
        server.enqueue(json("""{"devices":[{"device_id":"hp-a","last_push_at":"bukan-waktu"}],"extra":"diabaikan"}"""))

        val result = remote.history("t")

        val history = assertIs<RemoteResult.Success<ServerSyncHistory>>(result).value
        assertEquals(ServerSyncDevice("hp-a", 0, null, null, null, false), history.devices.single())
        assertEquals(SyncQuotaUsage(0, 0, 0, 0), history.quota)
        assertEquals(SyncCompactionInfo(null, 0, 0), history.compaction)
    }

    @Test
    fun `401 menjadi Unauthorized dan jaringan mati menjadi Unreachable`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Unauthenticated."}"""))
        assertEquals(RemoteResult.Failure(RemoteError.Unauthorized), remote.history("t"))

        server.shutdown()
        assertEquals(RemoteResult.Failure(RemoteError.Unreachable), remote.history("t"))
    }

    @Test
    fun `badan 200 yang bukan JSON menjadi Unexpected, bukan crash`() = runBlocking {
        server.enqueue(json("bukan json"))

        assertEquals(RemoteResult.Failure(RemoteError.Unexpected(200)), remote.history("t"))
    }
}
