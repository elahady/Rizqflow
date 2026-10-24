package com.roziqrizal.rizqflow.data.remote

import com.roziqrizal.rizqflow.domain.entitlement.Plan
import com.roziqrizal.rizqflow.domain.sync.ClaimStatus
import com.roziqrizal.rizqflow.domain.sync.PurchaseClaim
import com.roziqrizal.rizqflow.domain.sync.PurchaseClaimResponse
import com.roziqrizal.rizqflow.domain.sync.PurchaseClaimResult
import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [RetrofitPurchaseRemote] terhadap server HTTP sungguhan ([MockWebServer]), bukan mock interface. */
class RetrofitPurchaseRemoteTest {
    private lateinit var server: MockWebServer
    private lateinit var remote: RetrofitPurchaseRemote

    private val pro = PurchaseClaim("rizqflow_pro", "token-pro-1")
    private val sync = PurchaseClaim("rizqflow_sync", "token-sync-1")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        remote = RetrofitPurchaseRemote(RizqflowApiClient.create(server.url("/").toString()))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun `klaim satu item mengirim badan JSON persis kontrak dengan bearer token`() = runBlocking {
        server.enqueue(json(200, """{"plans":["pro"],"results":[{"product_id":"rizqflow_pro","status":"active"}]}"""))

        remote.claim(apiToken = "abc123", claims = listOf(pro))

        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/purchases/claim", sent.path)
        assertEquals("Bearer abc123", sent.getHeader("Authorization"))
        assertTrue(sent.getHeader("Content-Type").orEmpty().startsWith("application/json"))
        assertEquals(
            """{"purchases":[{"product_id":"rizqflow_pro","purchase_token":"token-pro-1"}]}""",
            sent.body.readUtf8(),
        )
    }

    @Test
    fun `klaim Pro dan Sync dikirim sebagai dua item dengan urutan masukan`() = runBlocking {
        server.enqueue(json(200, """{"plans":["pro","sync"],"results":[]}"""))

        remote.claim(apiToken = "abc123", claims = listOf(pro, sync))

        assertEquals(
            """{"purchases":[""" +
                """{"product_id":"rizqflow_pro","purchase_token":"token-pro-1"},""" +
                """{"product_id":"rizqflow_sync","purchase_token":"token-sync-1"}]}""",
            server.takeRequest().body.readUtf8(),
        )
    }

    @Test
    fun `200 dipetakan ke paket server dan status per item sejajar masukan`() = runBlocking {
        server.enqueue(
            json(
                200,
                """{"plans":["pro","sync"],"results":[""" +
                    """{"product_id":"rizqflow_pro","status":"active"},""" +
                    """{"product_id":"rizqflow_sync","status":"active"}]}""",
            ),
        )

        val result = remote.claim(apiToken = "abc123", claims = listOf(pro, sync))

        assertEquals(
            RemoteResult.Success(
                PurchaseClaimResponse(
                    plans = setOf(Plan.PRO, Plan.SYNC),
                    results = listOf(
                        PurchaseClaimResult("rizqflow_pro", ClaimStatus.ACTIVE),
                        PurchaseClaimResult("rizqflow_sync", ClaimStatus.ACTIVE),
                    ),
                ),
            ),
            result,
        )
    }

    @Test
    fun `semua status kontrak dipetakan ke nilai terketik`() = runBlocking {
        val wire = listOf("active", "pending", "inactive", "claimed_by_other", "unknown_product", "unverifiable")
        val results = wire.joinToString(",") { """{"product_id":"rizqflow_pro","status":"$it"}""" }
        server.enqueue(json(200, """{"plans":[],"results":[$results]}"""))

        val result = remote.claim(apiToken = "abc123", claims = listOf(pro))

        val statuses = (result as RemoteResult.Success).value.results.map { it.status }
        assertEquals(
            listOf(
                ClaimStatus.ACTIVE,
                ClaimStatus.PENDING,
                ClaimStatus.INACTIVE,
                ClaimStatus.CLAIMED_BY_OTHER,
                ClaimStatus.UNKNOWN_PRODUCT,
                ClaimStatus.UNVERIFIABLE,
            ),
            statuses,
        )
    }

    @Test
    fun `status dan paket yang belum dikenal tidak membuat jawaban gagal`() = runBlocking {
        server.enqueue(
            json(
                200,
                """{"plans":["pro","platinum"],"results":[{"product_id":"rizqflow_pro","status":"refund_in_review","extra":1}]}""",
            ),
        )

        val result = remote.claim(apiToken = "abc123", claims = listOf(pro))

        assertEquals(
            RemoteResult.Success(
                PurchaseClaimResponse(
                    plans = setOf(Plan.PRO),
                    results = listOf(PurchaseClaimResult("rizqflow_pro", ClaimStatus.UNKNOWN)),
                ),
            ),
            result,
        )
    }

    @Test
    fun `200 yang bukan JSON kontrak menjadi kegagalan, bukan crash`() = runBlocking {
        server.enqueue(json(200, "<html>Bad gateway dari proxy</html>"))

        val result = remote.claim(apiToken = "abc123", claims = listOf(pro))

        assertEquals(RemoteResult.Failure(RemoteError.Unexpected(200)), result)
    }

    @Test
    fun `401 tanpa token API yang sah mengembalikan Unauthorized`() = runBlocking {
        server.enqueue(json(401, """{"message":"Unauthenticated."}"""))

        val result = remote.claim(apiToken = "kedaluwarsa", claims = listOf(pro))

        assertEquals(RemoteResult.Failure(RemoteError.Unauthorized), result)
    }

    @Test
    fun `422 bentuk badan salah mengembalikan pesan server`() = runBlocking {
        server.enqueue(json(422, """{"message":"Data pembelian tidak valid.","errors":{"purchases":["wajib"]}}"""))

        val result = remote.claim(apiToken = "abc123", claims = listOf(pro))

        assertEquals(RemoteResult.Failure(RemoteError.Rejected("Data pembelian tidak valid.")), result)
    }

    @Test
    fun `503 verifikasi Play belum dikonfigurasi menjadi kegagalan yang bisa dicoba lagi`() = runBlocking {
        server.enqueue(json(503, """{"message":"Verifikasi pembelian belum tersedia."}"""))

        val result = remote.claim(apiToken = "abc123", claims = listOf(pro))

        assertEquals(RemoteResult.Failure(RemoteError.Unexpected(503)), result)
    }

    @Test
    fun `server tidak terjangkau mengembalikan Unreachable, bukan melempar exception`() = runBlocking {
        server.shutdown()

        val result = remote.claim(apiToken = "abc123", claims = listOf(pro))

        assertEquals(RemoteResult.Failure(RemoteError.Unreachable), result)
    }
}
