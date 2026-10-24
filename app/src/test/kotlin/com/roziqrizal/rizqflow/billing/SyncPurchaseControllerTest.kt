package com.roziqrizal.rizqflow.billing

import android.app.Activity
import com.roziqrizal.rizqflow.domain.entitlement.Plan
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Langganan Sync (SUBS) di [PurchaseController]: dibuka dari pembelian PURCHASED, tidak pernah dicabut
 * karena kegagalan toko, dan independen dari Pro (INAPP). Pro sendiri diuji di [PurchaseControllerTest].
 */
class SyncPurchaseControllerTest {

    private fun purchase(plan: Plan, state: PurchaseState = PurchaseState.PURCHASED, acknowledged: Boolean = true) =
        OwnedPurchase(plan, "token-${plan.name}", state, acknowledged)

    private fun controller(store: PurchaseStore, gateway: FakeBillingGateway, revokeMissing: Boolean = true) =
        PurchaseController(store, gateway, revokeMissing)

    private fun storeOf(vararg plans: Plan) = PurchaseStore(FakePlanStorage(plans.map { it.name }.toSet()))

    private val noActivity: () -> Activity? = { null }

    // --- Membeli ---

    @Test
    fun `beli bulanan membuka Sync dan meneruskan masa yang dipilih ke toko`() = runBlocking {
        val store = storeOf()
        val gateway = FakeBillingGateway().apply { purchaseResult = GatewayResult.Ok(listOf(purchase(Plan.SYNC))) }

        val outcome = controller(store, gateway).buy(Plan.SYNC, noActivity, BillingPeriod.MONTHLY)

        assertEquals(BuyOutcome.GRANTED, outcome)
        assertEquals(setOf(Plan.SYNC), store.plans.value)
        assertEquals<List<Pair<Plan, BillingPeriod?>>>(listOf(Plan.SYNC to BillingPeriod.MONTHLY), gateway.purchaseRequests)
    }

    @Test
    fun `beli tahunan membuka Sync dan meneruskan masa yang dipilih ke toko`() = runBlocking {
        val store = storeOf()
        val gateway = FakeBillingGateway().apply { purchaseResult = GatewayResult.Ok(listOf(purchase(Plan.SYNC))) }

        val outcome = controller(store, gateway).buy(Plan.SYNC, noActivity, BillingPeriod.YEARLY)

        assertEquals(BuyOutcome.GRANTED, outcome)
        assertEquals(setOf(Plan.SYNC), store.plans.value)
        assertEquals<List<Pair<Plan, BillingPeriod?>>>(listOf(Plan.SYNC to BillingPeriod.YEARLY), gateway.purchaseRequests)
    }

    @Test
    fun `beli Pro tidak membawa masa langganan`() = runBlocking {
        val gateway = FakeBillingGateway().apply { purchaseResult = GatewayResult.Ok(listOf(purchase(Plan.PRO))) }

        controller(storeOf(), gateway).buy(Plan.PRO, noActivity)

        assertEquals<List<Pair<Plan, BillingPeriod?>>>(listOf(Plan.PRO to null), gateway.purchaseRequests)
    }

    @Test
    fun `langganan tertunda tidak membuka Sync`() = runBlocking {
        val store = storeOf()
        val gateway = FakeBillingGateway().apply {
            purchaseResult = GatewayResult.Ok(listOf(purchase(Plan.SYNC, PurchaseState.PENDING, acknowledged = false)))
        }

        val outcome = controller(store, gateway).buy(Plan.SYNC, noActivity, BillingPeriod.MONTHLY)

        assertEquals(BuyOutcome.PENDING, outcome)
        assertTrue(store.plans.value.isEmpty())
        assertTrue(gateway.acknowledged.isEmpty())
    }

    @Test
    fun `langganan baru yang belum di-acknowledge di-acknowledge`() = runBlocking {
        val gateway = FakeBillingGateway().apply {
            purchaseResult = GatewayResult.Ok(listOf(purchase(Plan.SYNC, acknowledged = false)))
        }

        controller(storeOf(), gateway).buy(Plan.SYNC, noActivity, BillingPeriod.YEARLY)

        assertEquals(listOf("token-SYNC"), gateway.acknowledged)
    }

    @Test
    fun `langganan yang sudah di-acknowledge tidak di-acknowledge lagi, dan gagal acknowledge tidak menarik Sync`() = runBlocking {
        val store = storeOf()
        val gateway = FakeBillingGateway().apply {
            ownedResult = GatewayResult.Ok(listOf(purchase(Plan.SYNC, acknowledged = true)))
        }
        controller(store, gateway).sync()
        assertTrue(gateway.acknowledged.isEmpty())

        gateway.ownedResult = GatewayResult.Ok(listOf(purchase(Plan.SYNC, acknowledged = false)))
        gateway.acknowledgeResult = GatewayResult.Failure(BillingError.UNAVAILABLE)
        controller(store, gateway).sync()

        assertEquals(listOf("token-SYNC"), gateway.acknowledged)
        assertEquals(setOf(Plan.SYNC), store.plans.value)
    }

    @Test
    fun `sudah dimiliki di akun Play ini dipulihkan untuk langganan, bukan dianggap gagal`() = runBlocking {
        val store = storeOf()
        val gateway = FakeBillingGateway().apply {
            purchaseResult = GatewayResult.Failure(BillingError.ALREADY_OWNED)
            ownedResult = GatewayResult.Ok(listOf(purchase(Plan.SYNC)))
        }

        val outcome = controller(store, gateway).buy(Plan.SYNC, noActivity, BillingPeriod.MONTHLY)

        assertEquals(BuyOutcome.ALREADY_OWNED, outcome)
        assertEquals(setOf(Plan.SYNC), store.plans.value)
    }

    @Test
    fun `sudah dimiliki tetapi kueri langganan gagal tidak dianggap berhasil`() = runBlocking {
        val store = storeOf()
        val gateway = FakeBillingGateway().apply {
            purchaseResult = GatewayResult.Failure(BillingError.ALREADY_OWNED)
            ownedResult = GatewayResult.Ok(listOf(purchase(Plan.SYNC)))
            failedKinds = setOf(ProductKind.SUBSCRIPTION)
        }

        val outcome = controller(store, gateway).buy(Plan.SYNC, noActivity, BillingPeriod.MONTHLY)

        assertEquals(BuyOutcome.FAILED, outcome)
        assertTrue(store.plans.value.isEmpty())
    }

    @Test
    fun `sudah dimiliki dan hanya Pro yang kembali dari toko tidak dilaporkan sebagai Sync dimiliki`() = runBlocking {
        // Kueri langganan gagal dan akun memegang Pro: yang terbuka Pro, bukan Sync yang sedang dibeli.
        val store = storeOf()
        val gateway = FakeBillingGateway().apply {
            purchaseResult = GatewayResult.Failure(BillingError.ALREADY_OWNED)
            ownedResult = GatewayResult.Ok(listOf(purchase(Plan.PRO), purchase(Plan.SYNC)))
            failedKinds = setOf(ProductKind.SUBSCRIPTION)
        }

        val outcome = controller(store, gateway).buy(Plan.SYNC, noActivity, BillingPeriod.MONTHLY)

        assertEquals(BuyOutcome.FAILED, outcome)
        assertEquals(setOf(Plan.PRO), store.plans.value)
    }

    @Test
    fun `sudah dimiliki tetapi pembayarannya tertunda dilaporkan tertunda, bukan gagal`() = runBlocking {
        val store = storeOf()
        val gateway = FakeBillingGateway().apply {
            purchaseResult = GatewayResult.Failure(BillingError.ALREADY_OWNED)
            ownedResult = GatewayResult.Ok(listOf(purchase(Plan.SYNC, PurchaseState.PENDING, acknowledged = false)))
        }

        val outcome = controller(store, gateway).buy(Plan.SYNC, noActivity, BillingPeriod.MONTHLY)

        assertEquals(BuyOutcome.PENDING, outcome)
        assertTrue(store.plans.value.isEmpty())
        assertTrue(gateway.acknowledged.isEmpty())
    }

    @Test
    fun `sudah dimiliki tetapi langganan tidak dikembalikan aktif (ditahan atau berakhir) dilaporkan khusus`() = runBlocking {
        // Kueri langganan SUKSES dan tidak memuat Sync, padahal toko menjawab sudah dimiliki: tidak ada yang
        // bisa dibuka. Itu bukan kegagalan umum "coba lagi"; yang perlu dibereskan ada di Google Play.
        val store = storeOf()
        val gateway = FakeBillingGateway().apply {
            purchaseResult = GatewayResult.Failure(BillingError.ALREADY_OWNED)
            ownedResult = GatewayResult.Ok(emptyList())
        }

        val outcome = controller(store, gateway).buy(Plan.SYNC, noActivity, BillingPeriod.YEARLY)

        assertEquals(BuyOutcome.OWNED_INACTIVE, outcome)
        assertTrue(store.plans.value.isEmpty())
    }

    @Test
    fun `sudah dimiliki untuk Pro yang tidak kembali dari toko tetap gagal biasa`() = runBlocking {
        val gateway = FakeBillingGateway().apply {
            purchaseResult = GatewayResult.Failure(BillingError.ALREADY_OWNED)
            ownedResult = GatewayResult.Ok(emptyList())
        }

        assertEquals(BuyOutcome.FAILED, controller(storeOf(), gateway).buy(Plan.PRO, noActivity))
    }

    @Test
    fun `fitur langganan tidak didukung perangkat dilaporkan tidak didukung, bukan masalah koneksi`() = runBlocking {
        val store = storeOf()
        val gateway = FakeBillingGateway().apply { purchaseResult = GatewayResult.Failure(BillingError.NOT_SUPPORTED) }

        val outcome = controller(store, gateway).buy(Plan.SYNC, noActivity, BillingPeriod.MONTHLY)

        assertEquals(BuyOutcome.NOT_SUPPORTED, outcome)
        assertTrue(store.plans.value.isEmpty())
    }

    @Test
    fun `dua paket dalam satu hasil dengan token yang sama dibuka semuanya`() = runBlocking {
        val store = storeOf()
        val gateway = FakeBillingGateway().apply {
            ownedResult = GatewayResult.Ok(
                listOf(
                    OwnedPurchase(Plan.PRO, "token-sama", PurchaseState.PURCHASED, acknowledged = true),
                    OwnedPurchase(Plan.SYNC, "token-sama", PurchaseState.PURCHASED, acknowledged = true),
                ),
            )
        }

        assertEquals(RestoreOutcome.RESTORED, controller(store, gateway).restore())
        assertEquals(setOf(Plan.PRO, Plan.SYNC), store.plans.value)
    }

    // --- Pro dan Sync independen ---

    @Test
    fun `memiliki Pro tidak membuka Sync, dan memiliki Sync tidak membuka Pro`() = runBlocking {
        val gateway = FakeBillingGateway()

        val proOnly = storeOf()
        gateway.ownedResult = GatewayResult.Ok(listOf(purchase(Plan.PRO)))
        controller(proOnly, gateway).restore()
        assertEquals(setOf(Plan.PRO), proOnly.plans.value)

        val syncOnly = storeOf()
        gateway.ownedResult = GatewayResult.Ok(listOf(purchase(Plan.SYNC)))
        controller(syncOnly, gateway).restore()
        assertEquals(setOf(Plan.SYNC), syncOnly.plans.value)
    }

    @Test
    fun `membeli Sync tidak membuka Pro`() = runBlocking {
        val store = storeOf()
        val gateway = FakeBillingGateway().apply { purchaseResult = GatewayResult.Ok(listOf(purchase(Plan.SYNC))) }

        controller(store, gateway).buy(Plan.SYNC, noActivity, BillingPeriod.MONTHLY)

        assertEquals(setOf(Plan.SYNC), store.plans.value)
    }

    @Test
    fun `sync mencabut hanya paket yang hilang dari toko, tidak menyentuh paket lain`() = runBlocking {
        val gateway = FakeBillingGateway()

        val syncGone = storeOf(Plan.PRO, Plan.SYNC)
        gateway.ownedResult = GatewayResult.Ok(listOf(purchase(Plan.PRO)))
        controller(syncGone, gateway).sync()
        assertEquals(setOf(Plan.PRO), syncGone.plans.value)

        val proGone = storeOf(Plan.PRO, Plan.SYNC)
        gateway.ownedResult = GatewayResult.Ok(listOf(purchase(Plan.SYNC)))
        controller(proGone, gateway).sync()
        assertEquals(setOf(Plan.SYNC), proGone.plans.value)
    }

    // --- Pencabutan ---

    @Test
    fun `sync di perangkat baru membuka Sync yang masih berlangganan (pasang ulang)`() = runBlocking {
        val store = storeOf()
        val gateway = FakeBillingGateway().apply { ownedResult = GatewayResult.Ok(listOf(purchase(Plan.SYNC))) }

        controller(store, gateway).sync()

        assertEquals(setOf(Plan.SYNC), store.plans.value)
    }

    @Test
    fun `sync mencabut Sync yang tidak ada di toko bila revokeMissing dan kueri langganan sukses`() = runBlocking {
        val store = storeOf(Plan.SYNC)
        val gateway = FakeBillingGateway().apply { ownedResult = GatewayResult.Ok(emptyList()) }

        controller(store, gateway, revokeMissing = true).sync()

        assertTrue(store.plans.value.isEmpty())
    }

    @Test
    fun `langganan yang masih dikembalikan toko (masa tenggang atau batal sebelum berakhir) tetap terbuka`() = runBlocking {
        val store = storeOf(Plan.SYNC)
        // Play masih mengembalikan langganan dalam masa tenggang atau yang sudah dibatalkan tapi belum
        // berakhir sebagai PURCHASED; aksesnya tidak boleh tercabut sebelum langganan benar-benar berakhir.
        val gateway = FakeBillingGateway().apply { ownedResult = GatewayResult.Ok(listOf(purchase(Plan.SYNC))) }

        controller(store, gateway, revokeMissing = true).sync()

        assertEquals(setOf(Plan.SYNC), store.plans.value)
    }

    @Test
    fun `sync dengan langganan tertunda tidak mencabut Sync yang sudah sah`() = runBlocking {
        val store = storeOf(Plan.SYNC)
        val gateway = FakeBillingGateway().apply {
            ownedResult = GatewayResult.Ok(listOf(purchase(Plan.SYNC, PurchaseState.PENDING, acknowledged = false)))
        }

        controller(store, gateway, revokeMissing = true).sync()

        assertEquals(setOf(Plan.SYNC), store.plans.value)
    }

    @Test
    fun `build debug (revokeMissing mati) tidak pernah mencabut Pro maupun Sync`() = runBlocking {
        val store = storeOf(Plan.PRO, Plan.SYNC)
        val gateway = FakeBillingGateway().apply { ownedResult = GatewayResult.Ok(emptyList()) }

        controller(store, gateway, revokeMissing = false).sync()

        assertEquals(setOf(Plan.PRO, Plan.SYNC), store.plans.value)
    }

    @Test
    fun `kueri langganan gagal tidak mencabut Sync`() = runBlocking {
        val store = storeOf(Plan.SYNC)
        val gateway = FakeBillingGateway().apply {
            ownedResult = GatewayResult.Ok(emptyList())
            failedKinds = setOf(ProductKind.SUBSCRIPTION)
        }

        controller(store, gateway, revokeMissing = true).sync()

        assertEquals(setOf(Plan.SYNC), store.plans.value)
    }

    @Test
    fun `kueri sekali beli gagal tidak mencabut Pro dan tidak menghalangi pemberian Sync`() = runBlocking {
        val store = storeOf(Plan.PRO)
        val gateway = FakeBillingGateway().apply {
            ownedResult = GatewayResult.Ok(listOf(purchase(Plan.SYNC)))
            failedKinds = setOf(ProductKind.ONE_TIME)
        }

        controller(store, gateway, revokeMissing = true).sync()

        assertEquals(setOf(Plan.PRO, Plan.SYNC), store.plans.value)
    }

    @Test
    fun `kueri langganan gagal tidak menghalangi pemberian Pro, dan Sync yang ada tetap`() = runBlocking {
        val store = storeOf(Plan.SYNC)
        val gateway = FakeBillingGateway().apply {
            ownedResult = GatewayResult.Ok(listOf(purchase(Plan.PRO)))
            failedKinds = setOf(ProductKind.SUBSCRIPTION)
        }

        controller(store, gateway, revokeMissing = true).sync()

        assertEquals(setOf(Plan.PRO, Plan.SYNC), store.plans.value)
    }

    @Test
    fun `kueri langganan sukses tetap mencabut Sync yang berakhir walau kueri sekali beli gagal`() = runBlocking {
        val store = storeOf(Plan.PRO, Plan.SYNC)
        val gateway = FakeBillingGateway().apply {
            ownedResult = GatewayResult.Ok(emptyList())
            failedKinds = setOf(ProductKind.ONE_TIME)
        }

        controller(store, gateway, revokeMissing = true).sync()

        assertEquals(setOf(Plan.PRO), store.plans.value)
    }

    @Test
    fun `toko tidak terjangkau sama sekali tidak mencabut apa pun`() = runBlocking {
        val store = storeOf(Plan.PRO, Plan.SYNC)
        val gateway = FakeBillingGateway().apply { ownedResult = GatewayResult.Failure(BillingError.UNAVAILABLE) }

        controller(store, gateway, revokeMissing = true).sync()

        assertEquals(setOf(Plan.PRO, Plan.SYNC), store.plans.value)
    }

    @Test
    fun `sync dua kali berturut-turut mengikuti keadaan toko terkini`() = runBlocking {
        val store = storeOf()
        val gateway = FakeBillingGateway().apply { ownedResult = GatewayResult.Ok(listOf(purchase(Plan.SYNC))) }
        val controller = controller(store, gateway, revokeMissing = true)

        controller.sync()
        controller.sync()
        assertEquals(setOf(Plan.SYNC), store.plans.value)

        // Langganan berakhir di antara dua pembukaan aplikasi.
        gateway.ownedResult = GatewayResult.Ok(emptyList())
        controller.sync()
        controller.sync()
        assertTrue(store.plans.value.isEmpty())
    }

    // --- sync() bersamaan dengan beli, dan pembelian yang datang tanpa diminta ---

    @Test
    fun `sync yang berjalan bersamaan dengan beli tidak mencabut paket yang baru dibeli`() = runBlocking {
        val store = storeOf()
        val gate = CompletableDeferred<Unit>()
        val gateway = FakeBillingGateway().apply {
            // Jawaban toko saat sync() bertanya: langganan belum ada (kueri langganan sukses dan kosong).
            ownedResult = GatewayResult.Ok(emptyList())
            purchaseResult = GatewayResult.Ok(listOf(purchase(Plan.SYNC)))
            queryGate = gate
        }
        val controller = controller(store, gateway, revokeMissing = true)

        val syncing = launch { controller.sync() }
        yield()
        assertEquals(1, gateway.queryCount, "sync() sudah bertanya ke toko dan menunggu jawabannya")

        // Pembelian selesai selagi jawaban toko yang lebih lama belum sampai ke sync().
        assertEquals(BuyOutcome.GRANTED, controller.buy(Plan.SYNC, noActivity, BillingPeriod.MONTHLY))
        gate.complete(Unit)
        syncing.join()

        assertEquals(setOf(Plan.SYNC), store.plans.value)
    }

    @Test
    fun `sync yang berjalan bersamaan dengan pulihkan tidak mencabut paket yang baru dipulihkan`() = runBlocking {
        val store = storeOf()
        val gate = CompletableDeferred<Unit>()
        val gateway = FakeBillingGateway().apply {
            ownedResult = GatewayResult.Ok(emptyList())
            queryGate = gate
        }
        val controller = controller(store, gateway, revokeMissing = true)

        val syncing = launch { controller.sync() }
        yield()
        assertEquals(1, gateway.queryCount, "sync() sudah bertanya ke toko dan menunggu jawabannya")

        // Pulihkan ditekan selagi sync() menunggu; kali ini toko sudah membawa langganan.
        gateway.ownedResult = GatewayResult.Ok(listOf(purchase(Plan.SYNC)))
        assertEquals(RestoreOutcome.RESTORED, controller.restore())
        gate.complete(Unit)
        syncing.join()

        assertEquals(setOf(Plan.SYNC), store.plans.value)
    }

    @Test
    fun `pembayaran tertunda yang baru lunas di luar beli dibuka dan di-acknowledge setelah isyarat toko`() = runBlocking {
        val store = storeOf()
        val gateway = FakeBillingGateway()
        val controller = controller(store, gateway, revokeMissing = true)

        val watching = launch { controller.syncOnExternalUpdates() }
        yield()
        assertEquals(0, gateway.queryCount, "tanpa isyarat toko tidak ada kueri")
        assertTrue(store.plans.value.isEmpty())

        // Toko kini mengembalikan pembelian yang tadinya tertunda sebagai lunas.
        gateway.ownedResult = GatewayResult.Ok(listOf(purchase(Plan.SYNC, acknowledged = false)))
        gateway.signalExternalUpdate()
        repeat(5) { yield() }

        assertEquals(setOf(Plan.SYNC), store.plans.value)
        assertEquals(listOf("token-SYNC"), gateway.acknowledged)
        watching.cancelAndJoin()
    }

    @Test
    fun `isyarat toko saat kueri langganan gagal tidak mencabut Sync`() = runBlocking {
        val store = storeOf(Plan.SYNC)
        val gateway = FakeBillingGateway().apply { failedKinds = setOf(ProductKind.SUBSCRIPTION) }
        val controller = controller(store, gateway, revokeMissing = true)

        val watching = launch { controller.syncOnExternalUpdates() }
        yield()
        gateway.signalExternalUpdate()
        repeat(5) { yield() }

        assertEquals(1, gateway.queryCount)
        assertEquals(setOf(Plan.SYNC), store.plans.value)
        watching.cancelAndJoin()
    }

    // --- Pulihkan pembelian yang jujur ---

    @Test
    fun `pulihkan melaporkan tidak ada hanya bila semua kueri sukses dan kosong`() = runBlocking {
        val gateway = FakeBillingGateway()

        assertEquals(RestoreOutcome.NOTHING_FOUND, controller(storeOf(), gateway).restore())
    }

    @Test
    fun `pulihkan tidak menyimpulkan tidak ada pembelian dari kueri yang gagal`() = runBlocking {
        val gateway = FakeBillingGateway()

        gateway.failedKinds = setOf(ProductKind.SUBSCRIPTION)
        assertEquals(RestoreOutcome.UNAVAILABLE, controller(storeOf(), gateway).restore())

        gateway.failedKinds = setOf(ProductKind.ONE_TIME)
        assertEquals(RestoreOutcome.UNAVAILABLE, controller(storeOf(), gateway).restore())

        gateway.failedKinds = ProductKind.entries.toSet()
        assertEquals(RestoreOutcome.UNAVAILABLE, controller(storeOf(), gateway).restore())

        gateway.ownedResult = GatewayResult.Failure(BillingError.UNAVAILABLE)
        gateway.failedKinds = emptySet()
        assertEquals(RestoreOutcome.UNAVAILABLE, controller(storeOf(), gateway).restore())
    }

    @Test
    fun `pulihkan membuka paket dari jenis yang berhasil walau jenis lain gagal`() = runBlocking {
        val gateway = FakeBillingGateway()

        val withSyncFailed = storeOf()
        gateway.ownedResult = GatewayResult.Ok(listOf(purchase(Plan.PRO), purchase(Plan.SYNC)))
        gateway.failedKinds = setOf(ProductKind.SUBSCRIPTION)
        assertEquals(RestoreOutcome.RESTORED, controller(withSyncFailed, gateway).restore())
        assertEquals(setOf(Plan.PRO), withSyncFailed.plans.value)

        val withProFailed = storeOf()
        gateway.failedKinds = setOf(ProductKind.ONE_TIME)
        assertEquals(RestoreOutcome.RESTORED, controller(withProFailed, gateway).restore())
        assertEquals(setOf(Plan.SYNC), withProFailed.plans.value)
    }

    @Test
    fun `pulihkan membuka Pro dan Sync sekaligus bila keduanya sah`() = runBlocking {
        val store = storeOf()
        val gateway = FakeBillingGateway().apply {
            ownedResult = GatewayResult.Ok(listOf(purchase(Plan.PRO), purchase(Plan.SYNC)))
        }

        assertEquals(RestoreOutcome.RESTORED, controller(store, gateway).restore())
        assertEquals(setOf(Plan.PRO, Plan.SYNC), store.plans.value)
    }

    @Test
    fun `pulihkan dengan hanya pembelian tertunda dilaporkan tertunda dan tidak membuka apa pun`() = runBlocking {
        val store = storeOf()
        val gateway = FakeBillingGateway().apply {
            ownedResult = GatewayResult.Ok(listOf(purchase(Plan.SYNC, PurchaseState.PENDING, acknowledged = false)))
        }

        assertEquals(RestoreOutcome.PENDING, controller(store, gateway).restore())
        assertTrue(store.plans.value.isEmpty())

        // Pembelian tertunda yang ditemukan tetap kabar yang benar walau kueri jenis lain gagal.
        gateway.failedKinds = setOf(ProductKind.ONE_TIME)
        assertEquals(RestoreOutcome.PENDING, controller(store, gateway).restore())
    }

    @Test
    fun `pulihkan saat toko gagal tidak mencabut Sync`() = runBlocking {
        val store = storeOf(Plan.SYNC)
        val gateway = FakeBillingGateway().apply { ownedResult = GatewayResult.Failure(BillingError.UNAVAILABLE) }

        assertEquals(RestoreOutcome.UNAVAILABLE, controller(store, gateway).restore())
        assertEquals(setOf(Plan.SYNC), store.plans.value)
    }

    @Test
    fun `pulihkan dengan satu paket terbuka dan satu tertunda dilaporkan dipulihkan`() = runBlocking {
        val store = storeOf()
        val gateway = FakeBillingGateway().apply {
            ownedResult = GatewayResult.Ok(listOf(purchase(Plan.PRO), purchase(Plan.SYNC, PurchaseState.PENDING, acknowledged = false)))
        }

        val report = controller(store, gateway).restoreReport()

        assertEquals(RestoreOutcome.RESTORED, report.outcome)
        assertEquals(setOf(Plan.PRO), report.granted)
        assertEquals(setOf(Plan.SYNC), report.pending)
        assertEquals(setOf(Plan.PRO), store.plans.value)
    }

    @Test
    fun `rincian pulihkan menyebut paket yang dibuka, tertunda, dan tak bisa dipastikan`() = runBlocking {
        val gateway = FakeBillingGateway()

        // Semua kueri sukses dan kosong: tidak ada yang tak bisa dipastikan.
        assertEquals(RestoreReport(RestoreOutcome.NOTHING_FOUND, emptySet(), emptySet(), emptySet()), controller(storeOf(), gateway).restoreReport())

        // Kueri langganan gagal: Sync tak bisa dipastikan, Pro yang sah tetap terbuka.
        gateway.ownedResult = GatewayResult.Ok(listOf(purchase(Plan.PRO)))
        gateway.failedKinds = setOf(ProductKind.SUBSCRIPTION)
        assertEquals(
            RestoreReport(RestoreOutcome.RESTORED, setOf(Plan.PRO), emptySet(), setOf(Plan.SYNC)),
            controller(storeOf(), gateway).restoreReport(),
        )

        // Kueri sekali beli gagal: Pro tak bisa dipastikan.
        gateway.ownedResult = GatewayResult.Ok(listOf(purchase(Plan.SYNC)))
        gateway.failedKinds = setOf(ProductKind.ONE_TIME)
        assertEquals(
            RestoreReport(RestoreOutcome.RESTORED, setOf(Plan.SYNC), emptySet(), setOf(Plan.PRO)),
            controller(storeOf(), gateway).restoreReport(),
        )

        // Hanya Pro yang tertunda, tidak ada kueri gagal: tertunda milik Pro, bukan milik Sync.
        gateway.failedKinds = emptySet()
        gateway.ownedResult = GatewayResult.Ok(listOf(purchase(Plan.PRO, PurchaseState.PENDING, acknowledged = false)))
        assertEquals(
            RestoreReport(RestoreOutcome.PENDING, emptySet(), setOf(Plan.PRO), emptySet()),
            controller(storeOf(), gateway).restoreReport(),
        )

        // Toko tidak terjangkau sama sekali: semua paket tak bisa dipastikan.
        gateway.ownedResult = GatewayResult.Failure(BillingError.UNAVAILABLE)
        assertEquals(
            RestoreReport(RestoreOutcome.UNAVAILABLE, emptySet(), emptySet(), Plan.entries.toSet()),
            controller(storeOf(), gateway).restoreReport(),
        )
    }

    // --- Harga dan penawaran dari toko ---

    @Test
    fun `penawaran Sync datang dari toko, null bila toko tidak terhubung`() = runBlocking {
        val gateway = FakeBillingGateway()
        val offers = listOf(
            SubscriptionOffer(BillingPeriod.MONTHLY, "Rp19.000", freeTrialDays = 7),
            SubscriptionOffer(BillingPeriod.YEARLY, "Rp149.000", freeTrialDays = null),
        )
        gateway.offersResult = GatewayResult.Ok(offers)
        val controller = controller(storeOf(), gateway)

        assertEquals(offers, controller.syncOffers())

        gateway.offersResult = GatewayResult.Failure(BillingError.UNAVAILABLE)
        assertNull(controller.syncOffers())
    }

    // --- Model gateway ---

    @Test
    fun `setiap paket punya jenis produk, dan Pro sekali beli sedangkan Sync langganan`() {
        assertEquals(ProductKind.ONE_TIME, Plan.PRO.productKind())
        assertEquals(ProductKind.SUBSCRIPTION, Plan.SYNC.productKind())
        assertEquals(ProductKind.entries.toSet(), Plan.entries.map { it.productKind() }.toSet())
    }

    @Test
    fun `hasil kueri hanya lengkap bila semua jenis produk berhasil`() {
        assertTrue(OwnedSnapshot(emptyList(), ProductKind.entries.toSet()).complete)
        assertFalse(OwnedSnapshot(emptyList(), setOf(ProductKind.ONE_TIME)).complete)
        assertFalse(OwnedSnapshot.Unavailable.complete)
    }
}
