package com.roziqrizal.rizqflow.billing

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode as Code
import com.android.billingclient.api.Purchase
import com.roziqrizal.rizqflow.domain.entitlement.Plan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Terjemahan jawaban Play ke model gateway ([BillingTranslation.kt]) tanpa `BillingClient`: yang diuji
 * adalah aturan yang menegakkan "kegagalan toko tidak pernah mencabut akses" di sisi gateway sungguhan,
 * yang tidak terjangkau tes [PurchaseController] karena tes itu memakai gateway palsu.
 */
class BillingTranslationTest {

    private fun store(vararg products: String, state: Int = Purchase.PurchaseState.PURCHASED, token: String = "token", acknowledged: Boolean = true) =
        StorePurchase(products.toList(), token, state, acknowledged)

    private fun owned(plan: Plan, state: PurchaseState = PurchaseState.PURCHASED) = OwnedPurchase(plan, "token-${plan.name}", state, acknowledged = true)

    // --- Produk di Play Console ---

    @Test
    fun `ID produk dan tipenya harus sama dengan yang dibuat di Play Console`() {
        assertEquals("rizqflow_pro", BillingProduct.PRO.productId)
        assertEquals("inapp", BillingProduct.PRO.type)
        assertEquals("rizqflow_sync", BillingProduct.SYNC.productId)
        assertEquals("subs", BillingProduct.SYNC.type)
    }

    @Test
    fun `tipe produk tiap paket cocok dengan jenis produk yang memegangnya`() {
        assertEquals("inapp", ProductKind.ONE_TIME.billingType())
        assertEquals("subs", ProductKind.SUBSCRIPTION.billingType())
        Plan.entries.forEach { plan ->
            assertEquals(plan.productKind().billingType(), BillingProduct.of(plan)?.type, "paket ${plan.name}")
            assertEquals(plan, BillingProduct.of(BillingProduct.of(plan)!!.productId)?.plan)
        }
        assertNull(BillingProduct.of("produk_lain"))
    }

    // --- Status pembelian ---

    @Test
    fun `hanya PURCHASED dan PENDING yang dikenal, pembayaran tertunda tidak dibaca sebagai lunas`() {
        assertEquals(PurchaseState.PURCHASED, purchaseStateOf(Purchase.PurchaseState.PURCHASED))
        assertEquals(PurchaseState.PENDING, purchaseStateOf(Purchase.PurchaseState.PENDING))
        assertNull(purchaseStateOf(Purchase.PurchaseState.UNSPECIFIED_STATE))
        assertNull(purchaseStateOf(99))
    }

    @Test
    fun `pembelian dipetakan ke paketnya dengan token, status, dan acknowledge dari toko`() {
        val result = ownedFromStore(
            listOf(
                store("rizqflow_sync", state = Purchase.PurchaseState.PENDING, token = "t1", acknowledged = false),
                store("rizqflow_pro", token = "t2", acknowledged = true),
            ),
        )

        assertEquals(
            GatewayResult.Ok(
                listOf(
                    OwnedPurchase(Plan.SYNC, "t1", PurchaseState.PENDING, acknowledged = false),
                    OwnedPurchase(Plan.PRO, "t2", PurchaseState.PURCHASED, acknowledged = true),
                ),
            ),
            result,
        )
    }

    @Test
    fun `satu pembelian yang memuat beberapa produk menjadi beberapa paket, produk lain dibuang`() {
        val result = ownedFromStore(listOf(store("rizqflow_pro", "produk_lain", "rizqflow_sync", token = "t")))

        assertEquals(
            GatewayResult.Ok(
                listOf(
                    OwnedPurchase(Plan.PRO, "t", PurchaseState.PURCHASED, acknowledged = true),
                    OwnedPurchase(Plan.SYNC, "t", PurchaseState.PURCHASED, acknowledged = true),
                ),
            ),
            result,
        )
    }

    @Test
    fun `pembelian produk lain dibuang tanpa menggagalkan jawaban, apa pun statusnya`() {
        val result = ownedFromStore(
            listOf(
                store("produk_lain", state = Purchase.PurchaseState.UNSPECIFIED_STATE),
                store("rizqflow_sync"),
            ),
        )

        assertEquals(GatewayResult.Ok(listOf(OwnedPurchase(Plan.SYNC, "token", PurchaseState.PURCHASED, acknowledged = true))), result)
    }

    @Test
    fun `status tak dikenal pada produk kita menggagalkan jawaban, bukan membuangnya diam-diam`() {
        val result = ownedFromStore(listOf(store("rizqflow_sync", state = Purchase.PurchaseState.UNSPECIFIED_STATE)))

        assertEquals(GatewayResult.Failure(BillingError.UNKNOWN), result)
    }

    @Test
    fun `tanpa pembelian jawabannya sukses dan kosong`() {
        assertEquals(GatewayResult.Ok(emptyList<OwnedPurchase>()), ownedFromStore(emptyList()))
    }

    // --- Potret kepemilikan ---

    @Test
    fun `kueri yang gagal tidak masuk succeeded dan pembeliannya tidak ikut`() {
        val snapshot = snapshotOf(
            mapOf(
                ProductKind.ONE_TIME to GatewayResult.Ok(listOf(owned(Plan.PRO))),
                ProductKind.SUBSCRIPTION to GatewayResult.Failure(BillingError.NOT_SUPPORTED),
            ),
        )

        assertEquals(setOf(ProductKind.ONE_TIME), snapshot.succeeded)
        assertEquals(listOf(owned(Plan.PRO)), snapshot.purchases)
        assertFalse(snapshot.complete)
    }

    @Test
    fun `kueri sukses walau kosong tetap tercatat berhasil, itulah satu-satunya bukti tidak punya`() {
        val snapshot = snapshotOf(
            mapOf(
                ProductKind.ONE_TIME to GatewayResult.Failure(BillingError.UNAVAILABLE),
                ProductKind.SUBSCRIPTION to GatewayResult.Ok(emptyList()),
            ),
        )

        assertEquals(setOf(ProductKind.SUBSCRIPTION), snapshot.succeeded)
        assertTrue(snapshot.purchases.isEmpty())
    }

    @Test
    fun `semua kueri sukses membuat potret lengkap, jenis yang tidak ditanyakan dianggap gagal`() {
        val both = snapshotOf(
            mapOf(
                ProductKind.ONE_TIME to GatewayResult.Ok(listOf(owned(Plan.PRO))),
                ProductKind.SUBSCRIPTION to GatewayResult.Ok(listOf(owned(Plan.SYNC, PurchaseState.PENDING))),
            ),
        )
        assertTrue(both.complete)
        assertEquals(listOf(owned(Plan.PRO), owned(Plan.SYNC, PurchaseState.PENDING)), both.purchases)

        val missing = snapshotOf(mapOf(ProductKind.ONE_TIME to GatewayResult.Ok(emptyList())))
        assertEquals(setOf(ProductKind.ONE_TIME), missing.succeeded)
        assertEquals(OwnedSnapshot.Unavailable, snapshotOf(emptyMap()))
    }

    // --- Kode jawaban Play ---

    @Test
    fun `kode jawaban Play dipetakan ke galat gateway`() {
        assertEquals(BillingError.CANCELED, billingErrorOf(Code.USER_CANCELED))
        assertEquals(BillingError.ALREADY_OWNED, billingErrorOf(Code.ITEM_ALREADY_OWNED))
        assertEquals(BillingError.PRODUCT_NOT_FOUND, billingErrorOf(Code.ITEM_UNAVAILABLE))
        listOf(Code.BILLING_UNAVAILABLE, Code.SERVICE_UNAVAILABLE, Code.SERVICE_DISCONNECTED, Code.NETWORK_ERROR).forEach {
            assertEquals(BillingError.UNAVAILABLE, billingErrorOf(it), "kode $it")
        }
        assertEquals(BillingError.UNKNOWN, billingErrorOf(Code.ERROR))
        assertEquals(BillingError.UNKNOWN, billingErrorOf(Code.DEVELOPER_ERROR))
    }

    @Test
    fun `fitur tidak didukung dibedakan dari toko tidak terjangkau`() {
        // Galat tak bisa diulang (Play Store lama): jangan disamakan dengan "butuh koneksi internet".
        assertEquals(BillingError.NOT_SUPPORTED, billingErrorOf(Code.FEATURE_NOT_SUPPORTED))
        assertEquals(BillingError.UNAVAILABLE, billingErrorOf(Code.BILLING_UNAVAILABLE))
    }
}
