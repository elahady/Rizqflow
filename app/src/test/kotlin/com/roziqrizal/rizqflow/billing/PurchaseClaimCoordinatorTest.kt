package com.roziqrizal.rizqflow.billing

import android.app.Activity
import com.roziqrizal.rizqflow.domain.entitlement.Plan
import com.roziqrizal.rizqflow.domain.sync.ApiTokenStore
import com.roziqrizal.rizqflow.domain.sync.ClaimStatus
import com.roziqrizal.rizqflow.domain.sync.PurchaseClaim
import com.roziqrizal.rizqflow.domain.sync.PurchaseClaimResponse
import com.roziqrizal.rizqflow.domain.sync.PurchaseClaimResult
import com.roziqrizal.rizqflow.domain.sync.PurchaseRemote
import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import com.roziqrizal.rizqflow.sync.FailingPurchaseRemote
import com.roziqrizal.rizqflow.sync.FakeAccountRemote
import com.roziqrizal.rizqflow.sync.ServerSession
import com.roziqrizal.rizqflow.sync.TokenRefresher
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Token API palsu di memori; null berarti belum masuk ke server (akun lokal atau belum masuk Google). */
private class FakeApiTokenStore(var token: String? = "api-token-1") : ApiTokenStore {
    override fun read() = token

    override fun expiresAt(): java.time.Instant? = null

    override fun save(token: String, expiresAt: java.time.Instant?): Boolean {
        this.token = token
        return true
    }

    override fun clear() {
        token = null
    }
}

/**
 * Server palsu untuk [PurchaseRemote]: mencatat tiap panggilan, lalu menjawab lewat [reply]. Bawaannya
 * menjawab ACTIVE untuk semua item. [gate] menahan jawaban sampai tes melepasnya (meniru jaringan lambat).
 */
private class FakePurchaseRemote : PurchaseRemote {
    val calls = mutableListOf<List<PurchaseClaim>>()
    val apiTokens = mutableListOf<String>()
    var gate: CompletableDeferred<Unit>? = null
    var reply: (List<PurchaseClaim>) -> RemoteResult<PurchaseClaimResponse> = { statusFor(it) { ClaimStatus.ACTIVE } }

    override suspend fun claim(apiToken: String, claims: List<PurchaseClaim>): RemoteResult<PurchaseClaimResponse> {
        calls += claims
        apiTokens += apiToken
        gate?.await()
        return reply(claims)
    }
}

/** Jawaban sukses 200 dengan status per item sejajar masukan, ditentukan [status]. */
private fun statusFor(claims: List<PurchaseClaim>, status: (PurchaseClaim) -> ClaimStatus) =
    RemoteResult.Success(
        PurchaseClaimResponse(
            plans = emptySet(),
            results = claims.map { PurchaseClaimResult(it.productId, status(it)) },
        ),
    )

class PurchaseClaimCoordinatorTest {
    private val gateway = FakeBillingGateway()
    private val remote = FakePurchaseRemote()
    private val tokenStore = FakeApiTokenStore()
    private val memory = ClaimedTokenMemory()
    private val coordinator = newCoordinator()

    private fun newCoordinator(sharedMemory: ClaimedTokenMemory = memory) =
        PurchaseClaimCoordinator(gateway, remote, tokenStore, sharedMemory)

    private fun owned(plan: Plan, token: String = "token-${plan.name}", state: PurchaseState = PurchaseState.PURCHASED) =
        OwnedPurchase(plan, token, state, acknowledged = true)

    private fun ownedByStore(vararg purchases: OwnedPurchase) {
        gateway.ownedResult = GatewayResult.Ok(purchases.toList())
    }

    private val noActivity: () -> Activity? = { null }

    // --- Kapan mengirim ---

    @Test
    fun `terkirim begitu akun punya token server, misalnya sesudah masuk Google`() = runBlocking {
        ownedByStore(owned(Plan.PRO))
        tokenStore.clear()
        coordinator.claim()
        assertTrue(remote.calls.isEmpty())

        // Masuk ke server berhasil: token tersimpan, lalu pemulihan pembelian lama dilaporkan.
        tokenStore.save("api-token-baru", null)
        coordinator.claim()

        assertEquals(listOf(listOf(PurchaseClaim("rizqflow_pro", "token-PRO"))), remote.calls)
        assertEquals(listOf("api-token-baru"), remote.apiTokens)
    }

    @Test
    fun `tidak terkirim tanpa token server dan toko pun tidak ditanyai`() = runBlocking {
        ownedByStore(owned(Plan.PRO), owned(Plan.SYNC))
        tokenStore.clear()

        val report = coordinator.claim()

        assertFalse(report.showClaimedByOtherNotice)
        assertTrue(remote.calls.isEmpty())
        assertEquals(0, gateway.queryCount)
    }

    @Test
    fun `terkirim sesudah beli yang membuka paket`() = runBlocking {
        val store = PurchaseStore(FakePlanStorage())
        val purchase = owned(Plan.PRO)
        gateway.purchaseResult = GatewayResult.Ok(listOf(purchase))
        ownedByStore(purchase)

        val outcome = PurchaseController(store, gateway, revokeMissing = true).buy(Plan.PRO, noActivity)
        assertTrue(outcome.opensPlan())
        coordinator.claim()

        assertEquals(setOf(Plan.PRO), store.plans.value)
        assertEquals(listOf(listOf(PurchaseClaim("rizqflow_pro", "token-PRO"))), remote.calls)
    }

    @Test
    fun `terkirim sesudah pulihkan yang membuka paket`() = runBlocking {
        val store = PurchaseStore(FakePlanStorage())
        ownedByStore(owned(Plan.SYNC))

        val report = PurchaseController(store, gateway, revokeMissing = true).restoreReport()
        assertTrue(report.opensPlan())
        coordinator.claim()

        assertEquals(setOf(Plan.SYNC), store.plans.value)
        assertEquals(listOf(listOf(PurchaseClaim("rizqflow_sync", "token-SYNC"))), remote.calls)
    }

    @Test
    fun `hanya hasil beli dan pulihkan yang membuka paket memicu laporan`() {
        assertTrue(BuyOutcome.GRANTED.opensPlan())
        assertTrue(BuyOutcome.ALREADY_OWNED.opensPlan())
        listOf(
            BuyOutcome.PENDING,
            BuyOutcome.OWNED_INACTIVE,
            BuyOutcome.CANCELED,
            BuyOutcome.UNAVAILABLE,
            BuyOutcome.NOT_SUPPORTED,
            BuyOutcome.FAILED,
        ).forEach { assertFalse(it.opensPlan(), "$it tidak membuka paket") }

        assertTrue(RestoreReport(RestoreOutcome.RESTORED, setOf(Plan.PRO), emptySet(), emptySet()).opensPlan())
        assertFalse(RestoreReport(RestoreOutcome.PENDING, emptySet(), setOf(Plan.PRO), emptySet()).opensPlan())
        assertFalse(RestoreReport(RestoreOutcome.NOTHING_FOUND, emptySet(), emptySet(), emptySet()).opensPlan())
        assertFalse(RestoreReport(RestoreOutcome.UNAVAILABLE, emptySet(), emptySet(), setOf(Plan.PRO, Plan.SYNC)).opensPlan())
    }

    @Test
    fun `terkirim saat sync pembukaan aplikasi, sesudah paket lokal dibereskan`() = runBlocking {
        val store = PurchaseStore(FakePlanStorage())
        ownedByStore(owned(Plan.PRO))
        val controller = PurchaseController(store, gateway, revokeMissing = true)

        controller.sync()
        coordinator.claim()

        assertEquals(setOf(Plan.PRO), store.plans.value)
        assertEquals(1, remote.calls.size)
    }

    @Test
    fun `sync akibat pembelian tertunda yang lunas memanggil laporan sesudah sync`() = runBlocking {
        val store = PurchaseStore(FakePlanStorage())
        val controller = PurchaseController(store, gateway, revokeMissing = true)
        ownedByStore(owned(Plan.SYNC))
        val reported = CompletableDeferred<Unit>()

        val job = async {
            controller.syncOnExternalUpdates(afterSync = {
                // Saat laporan jalan, paket lokal sudah terbuka.
                assertEquals(setOf(Plan.SYNC), store.plans.value)
                coordinator.claim()
                reported.complete(Unit)
            })
        }
        yield()
        gateway.signalExternalUpdate()
        reported.await()
        job.cancel()

        assertEquals(listOf(listOf(PurchaseClaim("rizqflow_sync", "token-SYNC"))), remote.calls)
    }

    // --- Akun lokal dan demo tidak pernah mengirim ---

    @Test
    fun `dinonaktifkan untuk akun lokal atau demo tidak ada panggilan jaringan dan toko tidak ditanyai`() = runBlocking {
        ownedByStore(owned(Plan.PRO), owned(Plan.SYNC))
        val disabled = PurchaseClaimCoordinator(gateway, remote, tokenStore, ClaimedTokenMemory(), enabled = { false })

        val report = disabled.claim()

        assertFalse(report.showClaimedByOtherNotice)
        assertTrue(remote.calls.isEmpty())
        assertEquals(0, gateway.queryCount)
    }

    @Test
    fun `status aktif dibaca ulang tiap klaim sehingga berpindah ke demo berlaku seketika`() = runBlocking {
        ownedByStore(owned(Plan.PRO))
        var allowed = true
        val toggled = PurchaseClaimCoordinator(gateway, remote, tokenStore, ClaimedTokenMemory(), enabled = { allowed })

        allowed = false
        toggled.claim()
        assertTrue(remote.calls.isEmpty())

        allowed = true
        toggled.claim()
        assertEquals(1, remote.calls.size)
    }

    @Test
    fun `hanya akun bukan-lokal dan bukan-demo yang boleh melapor`() {
        assertTrue(purchaseClaimsAllowed(demo = false, localAccount = false))
        assertFalse(purchaseClaimsAllowed(demo = true, localAccount = false))
        assertFalse(purchaseClaimsAllowed(demo = false, localAccount = true))
        assertFalse(purchaseClaimsAllowed(demo = true, localAccount = true))
    }

    // --- Apa yang dikirim ---

    @Test
    fun `pembelian tertunda tidak dikirim`() = runBlocking {
        ownedByStore(owned(Plan.PRO, state = PurchaseState.PENDING))

        coordinator.claim()

        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun `hanya yang berstatus PURCHASED ikut dikirim bila bercampur dengan yang tertunda`() = runBlocking {
        ownedByStore(owned(Plan.PRO, state = PurchaseState.PENDING), owned(Plan.SYNC))

        coordinator.claim()

        assertEquals(listOf(listOf(PurchaseClaim("rizqflow_sync", "token-SYNC"))), remote.calls)
    }

    @Test
    fun `Pro dan Sync dikirim sebagai dua item dalam satu permintaan dengan productId dari kontrak`() = runBlocking {
        ownedByStore(owned(Plan.PRO), owned(Plan.SYNC))

        coordinator.claim()

        assertEquals(
            listOf(
                listOf(
                    PurchaseClaim("rizqflow_pro", "token-PRO"),
                    PurchaseClaim("rizqflow_sync", "token-SYNC"),
                ),
            ),
            remote.calls,
        )
    }

    @Test
    fun `tanpa pembelian di toko tidak ada panggilan jaringan`() = runBlocking {
        ownedByStore()

        coordinator.claim()

        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun `toko yang tidak terjangkau tidak menghasilkan panggilan jaringan`() = runBlocking {
        gateway.ownedResult = GatewayResult.Failure(BillingError.UNAVAILABLE)

        coordinator.claim()

        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun `jenis produk yang gagal ditanyakan tidak menghalangi yang lain`() = runBlocking {
        ownedByStore(owned(Plan.PRO), owned(Plan.SYNC))
        gateway.failedKinds = setOf(ProductKind.SUBSCRIPTION)

        coordinator.claim()

        assertEquals(listOf(listOf(PurchaseClaim("rizqflow_pro", "token-PRO"))), remote.calls)
    }

    @Test
    fun `token yang sama dari dua entri dikirim sekali`() = runBlocking {
        ownedByStore(owned(Plan.PRO, token = "sama"), owned(Plan.PRO, token = "sama"))

        coordinator.claim()

        assertEquals(listOf(listOf(PurchaseClaim("rizqflow_pro", "sama"))), remote.calls)
    }

    @Test
    fun `lebih dari sepuluh item dibagi ke beberapa permintaan sesuai batas kontrak`() = runBlocking {
        ownedByStore(*Array(12) { owned(Plan.SYNC, token = "sync-$it") })

        coordinator.claim()

        assertEquals(listOf(10, 2), remote.calls.map { it.size })
        assertEquals((0 until 12).map { "sync-$it" }, remote.calls.flatten().map { it.purchaseToken })
    }

    // --- Tidak mengirim berulang ---

    @Test
    fun `token yang sudah dijawab ACTIVE tidak dikirim lagi di sync berikutnya`() = runBlocking {
        ownedByStore(owned(Plan.PRO))

        coordinator.claim()
        coordinator.claim()
        coordinator.claim()

        assertEquals(1, remote.calls.size)
    }

    @Test
    fun `CLAIMED_BY_OTHER tidak dikirim lagi di sync berikutnya`() = runBlocking {
        ownedByStore(owned(Plan.PRO), owned(Plan.SYNC))
        remote.reply = { statusFor(it) { ClaimStatus.CLAIMED_BY_OTHER } }

        coordinator.claim()
        coordinator.claim()

        assertEquals(1, remote.calls.size)
    }

    @Test
    fun `INACTIVE dicoba lagi karena bukan jawaban final, dan berhenti begitu dijawab ACTIVE`() = runBlocking {
        ownedByStore(owned(Plan.PRO), owned(Plan.SYNC))
        // Server juga menjawab inactive untuk token yang belum dikenal Play (sesaat setelah beli, atau konfigurasi
        // server masih salah): bukan keputusan akhir, jadi dicoba lagi di sync berikutnya, bahkan sesudah
        // konfigurasi server diperbaiki dan penguji menekan Pulihkan di proses yang sama.
        remote.reply = { statusFor(it) { ClaimStatus.INACTIVE } }

        coordinator.claim()
        coordinator.claim()
        assertEquals(2, remote.calls.size)
        assertEquals(remote.calls[0], remote.calls[1])

        remote.reply = { statusFor(it) { ClaimStatus.ACTIVE } }
        coordinator.claim()
        coordinator.claim()
        assertEquals(3, remote.calls.size)
    }

    @Test
    fun `pembayaran tertunda dari server dicoba lagi sampai dijawab tegas`() = runBlocking {
        ownedByStore(owned(Plan.SYNC))
        remote.reply = { statusFor(it) { ClaimStatus.PENDING } }

        val report = coordinator.claim()
        coordinator.claim()

        assertFalse(report.showClaimedByOtherNotice)
        assertEquals(2, remote.calls.size)
        remote.reply = { statusFor(it) { ClaimStatus.ACTIVE } }
        coordinator.claim()
        coordinator.claim()
        assertEquals(3, remote.calls.size)
    }

    @Test
    fun `hanya item yang belum dijawab tegas yang dikirim ulang`() = runBlocking {
        ownedByStore(owned(Plan.PRO), owned(Plan.SYNC))
        remote.reply = { claims ->
            statusFor(claims) { if (it.productId == "rizqflow_pro") ClaimStatus.ACTIVE else ClaimStatus.UNVERIFIABLE }
        }

        coordinator.claim()
        coordinator.claim()

        assertEquals(
            listOf(
                listOf(PurchaseClaim("rizqflow_pro", "token-PRO"), PurchaseClaim("rizqflow_sync", "token-SYNC")),
                listOf(PurchaseClaim("rizqflow_sync", "token-SYNC")),
            ),
            remote.calls,
        )
    }

    @Test
    fun `pembelian baru dengan token lain tetap dikirim walau token lama sudah dilaporkan`() = runBlocking {
        ownedByStore(owned(Plan.SYNC, token = "langganan-1"))
        coordinator.claim()

        ownedByStore(owned(Plan.SYNC, token = "langganan-2"))
        coordinator.claim()

        assertEquals(
            listOf(listOf(PurchaseClaim("rizqflow_sync", "langganan-1")), listOf(PurchaseClaim("rizqflow_sync", "langganan-2"))),
            remote.calls,
        )
    }

    @Test
    fun `ganti akun Google di proses yang sama melaporkan ulang untuk akun baru`() = runBlocking {
        ownedByStore(owned(Plan.PRO))
        coordinator.claim()

        tokenStore.save("api-token-akun-lain", null)
        coordinator.claim()

        assertEquals(listOf("api-token-1", "api-token-akun-lain"), remote.apiTokens)
    }

    @Test
    fun `dua pemicu bersamaan hanya menghasilkan satu permintaan`() = runBlocking {
        ownedByStore(owned(Plan.PRO))
        val gate = CompletableDeferred<Unit>()
        remote.gate = gate

        val first = async { coordinator.claim() }
        val second = async { coordinator.claim() }
        yield()
        gate.complete(Unit)
        awaitAll(first, second)

        assertEquals(1, remote.calls.size)
    }

    // --- Kegagalan: diam, dicoba lagi, tidak menyentuh paket lokal ---

    @Test
    fun `unverifiable dicoba lagi di sync berikutnya sampai dijawab tegas`() = runBlocking {
        ownedByStore(owned(Plan.PRO))
        remote.reply = { statusFor(it) { ClaimStatus.UNVERIFIABLE } }

        val report = coordinator.claim()
        coordinator.claim()
        assertFalse(report.showClaimedByOtherNotice)
        assertEquals(2, remote.calls.size)

        remote.reply = { statusFor(it) { ClaimStatus.ACTIVE } }
        coordinator.claim()
        coordinator.claim()
        assertEquals(3, remote.calls.size)
    }

    @Test
    fun `kegagalan di permintaan pertama menghentikan sisanya, tidak dihantam berkali-kali`() = runBlocking {
        ownedByStore(*Array(25) { owned(Plan.SYNC, token = "sync-$it") })
        remote.reply = { RemoteResult.Failure(RemoteError.Unexpected(429)) }

        coordinator.claim()

        assertEquals(listOf(10), remote.calls.map { it.size }, "hanya batch pertama yang dicoba")
        // Tidak ada yang diingat: sync berikutnya mencoba dari awal.
        remote.reply = { statusFor(it) { ClaimStatus.ACTIVE } }
        coordinator.claim()
        assertEquals(listOf(10, 10, 10, 5), remote.calls.map { it.size })
    }

    @Test
    fun `kegagalan di permintaan kedua tidak membatalkan hasil permintaan pertama`() = runBlocking {
        ownedByStore(*Array(12) { owned(Plan.SYNC, token = "sync-$it") })
        remote.reply = { claims ->
            if (claims.size == MAX_CLAIMS_PER_REQUEST) statusFor(claims) { ClaimStatus.ACTIVE } else RemoteResult.Failure(RemoteError.Unreachable)
        }

        coordinator.claim()
        coordinator.claim()

        // Sepuluh pertama sudah dijawab tegas (tidak dikirim lagi); dua sisanya dicoba lagi.
        assertEquals(listOf(10, 2, 2), remote.calls.map { it.size })
    }

    @Test
    fun `kegagalan jaringan dan galat server dicoba lagi dan tidak mencabut paket lokal`() = runBlocking {
        val store = PurchaseStore(FakePlanStorage())
        ownedByStore(owned(Plan.PRO), owned(Plan.SYNC))
        val controller = PurchaseController(store, gateway, revokeMissing = true)
        controller.sync()
        assertEquals(setOf(Plan.PRO, Plan.SYNC), store.plans.value)

        val failures = listOf(
            RemoteError.Unreachable,
            RemoteError.Unauthorized,
            RemoteError.Rejected("Data pembelian tidak valid."),
            RemoteError.Unexpected(503),
            RemoteError.Unexpected(429),
        )
        for (error in failures) {
            remote.reply = { RemoteResult.Failure(error) }
            val report = coordinator.claim()
            controller.sync()

            assertFalse(report.showClaimedByOtherNotice, "$error tidak boleh memunculkan pesan")
            assertEquals(setOf(Plan.PRO, Plan.SYNC), store.plans.value, "$error tidak boleh mengubah paket lokal")
        }
        // Tiap kegagalan dicoba lagi: tidak ada yang diingat sebagai "sudah dilaporkan".
        assertEquals(failures.size, remote.calls.size)

        remote.reply = { statusFor(it) { ClaimStatus.ACTIVE } }
        coordinator.claim()
        assertEquals(failures.size + 1, remote.calls.size)
    }

    @Test
    fun `status server apa pun tidak mengubah paket lokal`() = runBlocking {
        val store = PurchaseStore(FakePlanStorage())
        ownedByStore(owned(Plan.PRO), owned(Plan.SYNC))
        val controller = PurchaseController(store, gateway, revokeMissing = true)
        controller.sync()

        for (status in ClaimStatus.entries) {
            val isolated = newCoordinator(ClaimedTokenMemory())
            remote.reply = { statusFor(it) { status } }
            isolated.claim()
            controller.sync()

            assertEquals(setOf(Plan.PRO, Plan.SYNC), store.plans.value, "$status tidak boleh mengubah paket lokal")
        }
    }

    @Test
    fun `kegagalan laporan tidak menghalangi beli atau pulihkan`() = runBlocking {
        val store = PurchaseStore(FakePlanStorage())
        val purchase = owned(Plan.PRO)
        gateway.purchaseResult = GatewayResult.Ok(listOf(purchase))
        ownedByStore(purchase)
        remote.reply = { RemoteResult.Failure(RemoteError.Unreachable) }
        val controller = PurchaseController(store, gateway, revokeMissing = true)

        assertEquals(BuyOutcome.GRANTED, controller.buy(Plan.PRO, noActivity))
        coordinator.claim()
        assertEquals(RestoreOutcome.RESTORED, controller.restore())

        assertEquals(setOf(Plan.PRO), store.plans.value)
    }

    @Test
    fun `galat tak terduga di klien dibungkam dan tidak melempar`() = runBlocking {
        ownedByStore(owned(Plan.PRO))
        remote.reply = { error("jawaban rusak") }

        val report = coordinator.claim()

        assertFalse(report.showClaimedByOtherNotice)
        // Tidak diingat sebagai terkirim: sync berikutnya mencoba lagi.
        remote.reply = { statusFor(it) { ClaimStatus.ACTIVE } }
        coordinator.claim()
        assertEquals(2, remote.calls.size)
    }

    @Test
    fun `pembatalan coroutine tetap diteruskan, tidak ikut dibungkam`() {
        ownedByStore(owned(Plan.PRO))
        remote.reply = { throw CancellationException("dibatalkan") }

        assertFailsWith<CancellationException> { runBlocking { coordinator.claim() } }
    }

    @Test
    fun `status yang tak dikenal tidak crash, tidak diingat, dan tidak memunculkan pesan`() = runBlocking {
        ownedByStore(owned(Plan.PRO), owned(Plan.SYNC))
        remote.reply = { claims ->
            statusFor(claims) { if (it.productId == "rizqflow_pro") ClaimStatus.UNKNOWN else ClaimStatus.UNKNOWN_PRODUCT }
        }

        val report = coordinator.claim()
        coordinator.claim()

        assertFalse(report.showClaimedByOtherNotice)
        assertEquals(2, remote.calls.size)
    }

    @Test
    fun `jawaban tidak sejajar dengan masukan diabaikan seluruhnya`() = runBlocking {
        ownedByStore(owned(Plan.PRO), owned(Plan.SYNC))
        // Kurang satu hasil, dan satu lagi dengan productId yang tidak cocok.
        remote.reply = { RemoteResult.Success(PurchaseClaimResponse(emptySet(), listOf(PurchaseClaimResult("rizqflow_pro", ClaimStatus.CLAIMED_BY_OTHER)))) }
        val shortReport = coordinator.claim()
        remote.reply = { claims ->
            RemoteResult.Success(
                PurchaseClaimResponse(emptySet(), claims.map { PurchaseClaimResult("produk_lain", ClaimStatus.CLAIMED_BY_OTHER) }),
            )
        }
        val mismatchReport = coordinator.claim()

        assertFalse(shortReport.showClaimedByOtherNotice)
        assertFalse(mismatchReport.showClaimedByOtherNotice)
        // Tidak ada yang diingat: tiap kali dicoba lagi.
        assertEquals(2, remote.calls.size)
        remote.reply = { statusFor(it) { ClaimStatus.ACTIVE } }
        coordinator.claim()
        assertEquals(3, remote.calls.size)
    }

    // --- Pesan claimed_by_other ---

    @Test
    fun `claimed_by_other memunculkan pesan sekali per token per proses`() = runBlocking {
        ownedByStore(owned(Plan.PRO, token = "pro-1"))
        remote.reply = { statusFor(it) { ClaimStatus.CLAIMED_BY_OTHER } }

        val first = coordinator.claim()
        val second = coordinator.claim()

        assertTrue(first.showClaimedByOtherNotice)
        assertFalse(second.showClaimedByOtherNotice)
        // Sesudah dijawab tegas, tokennya tidak dikirim lagi sama sekali.
        assertEquals(1, remote.calls.size)
    }

    @Test
    fun `pesan tidak muncul lagi walau koordinator dibuat ulang selama ingatan proses sama`() = runBlocking {
        ownedByStore(owned(Plan.PRO, token = "pro-1"))
        remote.reply = { statusFor(it) { ClaimStatus.CLAIMED_BY_OTHER } }

        assertTrue(coordinator.claim().showClaimedByOtherNotice)
        // Ruang kerja dibuka ulang (ganti akun lokal, pulihkan cadangan): koordinator baru, ingatan proses sama.
        val reopened = newCoordinator(memory)
        val report = reopened.claim()

        assertFalse(report.showClaimedByOtherNotice)
        assertEquals(1, remote.calls.size)
    }

    @Test
    fun `dua koordinator yang berbagi ingatan memunculkan pesan satu kali walau jawabannya datang bersamaan`() = runBlocking {
        ownedByStore(owned(Plan.PRO, token = "pro-1"))
        val gate = CompletableDeferred<Unit>()
        remote.gate = gate
        remote.reply = { statusFor(it) { ClaimStatus.CLAIMED_BY_OTHER } }
        // Dua ruang kerja (dua koordinator, dua mutex) berbagi ingatan proses yang sama.
        val other = newCoordinator(memory)

        val first = async { coordinator.claim() }
        val second = async { other.claim() }
        yield()
        gate.complete(Unit)
        val reports = awaitAll(first, second)

        assertEquals(2, remote.calls.size, "keduanya sempat mengirim sebelum ada yang mengingat")
        assertEquals(1, reports.count { it.showClaimedByOtherNotice }, "pesan hanya muncul sekali")
    }

    @Test
    fun `token pembelian lain yang juga dipakai akun lain memunculkan pesan sendiri`() = runBlocking {
        remote.reply = { statusFor(it) { ClaimStatus.CLAIMED_BY_OTHER } }
        ownedByStore(owned(Plan.PRO, token = "pro-1"))
        assertTrue(coordinator.claim().showClaimedByOtherNotice)

        ownedByStore(owned(Plan.PRO, token = "pro-1"), owned(Plan.SYNC, token = "sync-1"))
        assertTrue(coordinator.claim().showClaimedByOtherNotice)
        assertFalse(coordinator.claim().showClaimedByOtherNotice)
    }

    @Test
    fun `Pro dan Sync sama-sama dipakai akun lain menghasilkan satu pesan saja`() = runBlocking {
        ownedByStore(owned(Plan.PRO), owned(Plan.SYNC))
        remote.reply = { statusFor(it) { ClaimStatus.CLAIMED_BY_OTHER } }

        val report = coordinator.claim()

        assertTrue(report.showClaimedByOtherNotice)
        // Keduanya sudah diingat: sync berikutnya diam.
        assertFalse(coordinator.claim().showClaimedByOtherNotice)
        assertEquals(1, remote.calls.size)
    }

    @Test
    fun `ACTIVE dan INACTIVE tidak memunculkan pesan apa pun`() = runBlocking {
        ownedByStore(owned(Plan.PRO), owned(Plan.SYNC))
        remote.reply = { claims ->
            statusFor(claims) { if (it.productId == "rizqflow_pro") ClaimStatus.ACTIVE else ClaimStatus.INACTIVE }
        }

        assertFalse(coordinator.claim().showClaimedByOtherNotice)
    }

    @Test
    fun `akun Google lain yang mendapat claimed_by_other untuk token sama diberi tahu sendiri`() = runBlocking {
        ownedByStore(owned(Plan.PRO, token = "pro-1"))
        remote.reply = { statusFor(it) { ClaimStatus.CLAIMED_BY_OTHER } }
        assertTrue(coordinator.claim().showClaimedByOtherNotice)

        tokenStore.save("api-token-akun-lain", null)

        assertTrue(coordinator.claim().showClaimedByOtherNotice)
    }

    // --- Putar token sebelum klaim, dan 401 lewat ServerSession (Tahap 12) ---

    @Test
    fun `putar token dijalankan sebelum token dibaca, jadi klaim memakai token yang baru`() = runBlocking {
        ownedByStore(owned(Plan.PRO))
        val rotating = PurchaseClaimCoordinator(
            gateway, remote, tokenStore, memory,
            refreshToken = { tokenStore.save("api-token-hasil-putar", null) },
        )

        rotating.claim()

        assertEquals(listOf("api-token-hasil-putar"), remote.apiTokens)
    }

    @Test
    fun `putar token tidak dijalankan bila laporan dimatikan`() = runBlocking {
        var rotations = 0
        val disabled = PurchaseClaimCoordinator(gateway, remote, tokenStore, memory, enabled = { false }, refreshToken = { rotations++ })

        disabled.claim()

        assertEquals(0, rotations)
        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun `putar token yang mengakhiri sesi membuat klaim berhenti diam tanpa panggilan ke server`() = runBlocking {
        ownedByStore(owned(Plan.PRO))
        val ending = PurchaseClaimCoordinator(gateway, remote, tokenStore, memory, refreshToken = { tokenStore.clear() })

        val report = ending.claim()

        assertFalse(report.showClaimedByOtherNotice)
        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun `putar token yang melempar galat tidak menjatuhkan klaim`() = runBlocking {
        ownedByStore(owned(Plan.PRO))
        val broken = PurchaseClaimCoordinator(gateway, remote, tokenStore, memory, refreshToken = { error("meledak") })

        assertFalse(broken.claim().showClaimedByOtherNotice)
    }

    @Test
    fun `401 dari klaim diurus ServerSession, sesi berakhir, paket lokal utuh, lalu pulih setelah sambung ulang`() = runBlocking {
        val store = PurchaseStore(FakePlanStorage())
        ownedByStore(owned(Plan.PRO))
        val controller = PurchaseController(store, gateway, revokeMissing = true)
        controller.sync()
        val session = ServerSession(tokenStore)
        val guarded = PurchaseClaimCoordinator(gateway, session.guard(FailingPurchaseRemote(RemoteError.Unauthorized)), tokenStore, memory)

        val report = guarded.claim()

        // Penanganan 401 ada di satu tempat (ServerSession), bukan di koordinator ini.
        assertFalse(report.showClaimedByOtherNotice)
        assertNull(tokenStore.token)
        assertTrue(session.needsReconnect.value)
        assertEquals(setOf(Plan.PRO), store.plans.value, "paket dari Google Play tidak terpengaruh")

        // Sesudah itu klaim berhenti diam sampai tersambung ulang: tidak ada panggilan server tanpa token.
        guarded.claim()
        assertTrue(remote.calls.isEmpty())

        // Sambung ulang: token baru tersimpan lewat jalur yang sama dengan masuk, klaim berjalan normal.
        session.connect(FakeAccountRemote(), "id-token", "perangkat-1")
        val resumed = PurchaseClaimCoordinator(gateway, session.guard(remote), tokenStore, memory)
        resumed.claim()
        assertEquals(listOf("login-token"), remote.apiTokens)
        assertFalse(session.needsReconnect.value)
    }

    @Test
    fun `klaim sebelum kedaluwarsa memutar token lebih dulu lewat pemutar sungguhan dan memakai token barunya`() = runBlocking {
        ownedByStore(owned(Plan.PRO))
        val now = java.time.Instant.parse("2026-12-01T00:00:00Z")
        val accountStore = com.roziqrizal.rizqflow.sync.FakeTokenStore("t-lama", now.plusSeconds(5L * 24 * 3600))
        val session = ServerSession(accountStore)
        val accountRemote = FakeAccountRemote(valid = setOf("t-lama"))
        val refresher = TokenRefresher(session, session.guard(accountRemote), clock = { now })
        val rotating = PurchaseClaimCoordinator(
            gateway, session.guard(remote), accountStore, memory,
            refreshToken = { refresher.refreshIfDue() },
        )

        rotating.claim()

        assertEquals(listOf("refresh:t-lama"), accountRemote.calls)
        assertEquals(listOf("rotated-1"), remote.apiTokens, "klaim memakai token hasil putar, bukan yang sudah dicabut")
    }
}
