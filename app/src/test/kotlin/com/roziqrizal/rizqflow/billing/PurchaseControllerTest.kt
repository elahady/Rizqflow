package com.roziqrizal.rizqflow.billing

import android.app.Activity
import com.roziqrizal.rizqflow.domain.entitlement.Plan
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PurchaseControllerTest {

    private class FakeStorage(var names: Set<String> = emptySet()) : PlanStorage {
        override fun read() = names
        override fun write(names: Set<String>) {
            this.names = names
        }
    }

    private class FakeGateway : BillingGateway {
        var priceResult: GatewayResult<String> = GatewayResult.Ok("Rp99.000")
        var purchaseResult: GatewayResult<List<OwnedPurchase>> = GatewayResult.Failure(BillingError.UNAVAILABLE)
        var ownedResult: GatewayResult<List<OwnedPurchase>> = GatewayResult.Ok(emptyList())
        var acknowledgeResult: GatewayResult<Unit> = GatewayResult.Ok(Unit)
        val acknowledged = mutableListOf<String>()

        override suspend fun price(plan: Plan) = priceResult
        override suspend fun purchase(plan: Plan, activity: () -> Activity?) = purchaseResult
        override suspend fun queryOwned() = ownedResult
        override suspend fun acknowledge(purchase: OwnedPurchase): GatewayResult<Unit> {
            acknowledged += purchase.token
            return acknowledgeResult
        }
    }

    private fun purchase(state: PurchaseState = PurchaseState.PURCHASED, acknowledged: Boolean = true, plan: Plan = Plan.PRO) =
        OwnedPurchase(plan, "token-${plan.name}", state, acknowledged)

    private fun controller(store: PurchaseStore, gateway: FakeGateway, revokeMissing: Boolean = true) =
        PurchaseController(store, gateway, revokeMissing)

    private val noActivity: () -> Activity? = { null }

    @Test
    fun `harga datang dari toko, null bila toko tidak terhubung`() = runBlocking {
        val gateway = FakeGateway()
        val controller = controller(PurchaseStore(FakeStorage()), gateway)

        assertEquals("Rp99.000", controller.price())

        gateway.priceResult = GatewayResult.Failure(BillingError.UNAVAILABLE)
        assertNull(controller.price())
    }

    @Test
    fun `beli sukses membuka Pro dan meng-acknowledge pembelian yang belum`() = runBlocking {
        val store = PurchaseStore(FakeStorage())
        val gateway = FakeGateway().apply { purchaseResult = GatewayResult.Ok(listOf(purchase(acknowledged = false))) }

        val outcome = controller(store, gateway).buy(Plan.PRO, noActivity)

        assertEquals(BuyOutcome.GRANTED, outcome)
        assertEquals(setOf(Plan.PRO), store.plans.value)
        assertEquals(listOf("token-PRO"), gateway.acknowledged)
    }

    @Test
    fun `pembelian yang sudah di-acknowledge tidak di-acknowledge lagi`() = runBlocking {
        val gateway = FakeGateway().apply { purchaseResult = GatewayResult.Ok(listOf(purchase(acknowledged = true))) }

        controller(PurchaseStore(FakeStorage()), gateway).buy(Plan.PRO, noActivity)

        assertTrue(gateway.acknowledged.isEmpty())
    }

    @Test
    fun `gagal acknowledge tidak menarik Pro kembali`() = runBlocking {
        val store = PurchaseStore(FakeStorage())
        val gateway = FakeGateway().apply {
            purchaseResult = GatewayResult.Ok(listOf(purchase(acknowledged = false)))
            acknowledgeResult = GatewayResult.Failure(BillingError.UNAVAILABLE)
        }

        assertEquals(BuyOutcome.GRANTED, controller(store, gateway).buy(Plan.PRO, noActivity))
        assertEquals(setOf(Plan.PRO), store.plans.value)
    }

    @Test
    fun `pembayaran tertunda tidak membuka Pro`() = runBlocking {
        val store = PurchaseStore(FakeStorage())
        val gateway = FakeGateway().apply { purchaseResult = GatewayResult.Ok(listOf(purchase(state = PurchaseState.PENDING, acknowledged = false))) }

        val outcome = controller(store, gateway).buy(Plan.PRO, noActivity)

        assertEquals(BuyOutcome.PENDING, outcome)
        assertTrue(store.plans.value.isEmpty())
        assertTrue(gateway.acknowledged.isEmpty())
    }

    @Test
    fun `dibatalkan atau toko tidak tersedia tidak mengubah paket`() = runBlocking {
        val store = PurchaseStore(FakeStorage())
        val gateway = FakeGateway()
        val controller = controller(store, gateway)

        gateway.purchaseResult = GatewayResult.Failure(BillingError.CANCELED)
        assertEquals(BuyOutcome.CANCELED, controller.buy(Plan.PRO, noActivity))

        gateway.purchaseResult = GatewayResult.Failure(BillingError.UNAVAILABLE)
        assertEquals(BuyOutcome.UNAVAILABLE, controller.buy(Plan.PRO, noActivity))

        gateway.purchaseResult = GatewayResult.Failure(BillingError.PRODUCT_NOT_FOUND)
        assertEquals(BuyOutcome.UNAVAILABLE, controller.buy(Plan.PRO, noActivity))

        gateway.purchaseResult = GatewayResult.Failure(BillingError.UNKNOWN)
        assertEquals(BuyOutcome.FAILED, controller.buy(Plan.PRO, noActivity))

        assertTrue(store.plans.value.isEmpty())
    }

    @Test
    fun `sudah dimiliki di akun Play ini dipulihkan, bukan dianggap gagal`() = runBlocking {
        val store = PurchaseStore(FakeStorage())
        val gateway = FakeGateway().apply {
            purchaseResult = GatewayResult.Failure(BillingError.ALREADY_OWNED)
            ownedResult = GatewayResult.Ok(listOf(purchase()))
        }

        assertEquals(BuyOutcome.ALREADY_OWNED, controller(store, gateway).buy(Plan.PRO, noActivity))
        assertEquals(setOf(Plan.PRO), store.plans.value)
    }

    @Test
    fun `pulihkan membuka paket yang sah, atau melaporkan tidak ada`() = runBlocking {
        val store = PurchaseStore(FakeStorage())
        val gateway = FakeGateway()
        val controller = controller(store, gateway)

        assertEquals(RestoreOutcome.NOTHING_FOUND, controller.restore())
        assertTrue(store.plans.value.isEmpty())

        gateway.ownedResult = GatewayResult.Ok(listOf(purchase()))
        assertEquals(RestoreOutcome.RESTORED, controller.restore())
        assertEquals(setOf(Plan.PRO), store.plans.value)
    }

    @Test
    fun `pulihkan saat toko tidak tersedia dilaporkan jujur dan tidak mencabut apa pun`() = runBlocking {
        val store = PurchaseStore(FakeStorage(setOf("PRO")))
        val gateway = FakeGateway().apply { ownedResult = GatewayResult.Failure(BillingError.UNAVAILABLE) }

        assertEquals(RestoreOutcome.UNAVAILABLE, controller(store, gateway).restore())
        assertEquals(setOf(Plan.PRO), store.plans.value)
    }

    @Test
    fun `sync di perangkat baru membuka Pro yang sudah dibeli (pasang ulang)`() = runBlocking {
        val store = PurchaseStore(FakeStorage())
        val gateway = FakeGateway().apply { ownedResult = GatewayResult.Ok(listOf(purchase())) }

        controller(store, gateway).sync()

        assertEquals(setOf(Plan.PRO), store.plans.value)
    }

    @Test
    fun `sync mencabut Pro yang sudah tidak ada di toko (pengembalian dana)`() = runBlocking {
        val store = PurchaseStore(FakeStorage(setOf("PRO")))
        val gateway = FakeGateway().apply { ownedResult = GatewayResult.Ok(emptyList()) }

        controller(store, gateway, revokeMissing = true).sync()

        assertTrue(store.plans.value.isEmpty())
    }

    @Test
    fun `sync di build debug tidak mencabut saklar Pro uji`() = runBlocking {
        val store = PurchaseStore(FakeStorage(setOf("PRO")))
        val gateway = FakeGateway().apply { ownedResult = GatewayResult.Ok(emptyList()) }

        controller(store, gateway, revokeMissing = false).sync()

        assertEquals(setOf(Plan.PRO), store.plans.value)
    }

    @Test
    fun `sync saat toko gagal tidak pernah mencabut Pro (offline tidak menghilangkan akses)`() = runBlocking {
        val store = PurchaseStore(FakeStorage(setOf("PRO")))
        val gateway = FakeGateway().apply { ownedResult = GatewayResult.Failure(BillingError.UNAVAILABLE) }

        controller(store, gateway, revokeMissing = true).sync()

        assertEquals(setOf(Plan.PRO), store.plans.value)
    }

    @Test
    fun `sync dengan pembelian tertunda tidak mencabut Pro yang sudah sah`() = runBlocking {
        val store = PurchaseStore(FakeStorage(setOf("PRO")))
        val gateway = FakeGateway().apply {
            ownedResult = GatewayResult.Ok(listOf(purchase(state = PurchaseState.PENDING, acknowledged = false)))
        }

        controller(store, gateway, revokeMissing = true).sync()

        assertEquals(setOf(Plan.PRO), store.plans.value)
    }
}
