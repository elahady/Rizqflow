package com.roziqrizal.rizqflow.billing

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.Purchase
import com.roziqrizal.rizqflow.domain.entitlement.Plan

/**
 * Terjemahan murni dari jawaban Google Play Billing ke model gateway ([OwnedPurchase], [OwnedSnapshot],
 * [BillingError]). Dipisah dari [PlayBillingGateway] supaya aturan yang menjaga prinsip "kegagalan toko
 * tidak pernah mencabut akses" bisa diuji tanpa `BillingClient`: yang dibaca di sini hanya kode angka dan
 * konstanta teks Play, tanpa membuat objek Google.
 */

/**
 * Pemetaan paket ke produk di Play Console. ID produk harus SAMA PERSIS dengan yang dibuat di
 * Monetize with Play > Products (Pro, sekali beli) dan Subscriptions (Sync, dengan dua base plan
 * berID "monthly" dan "yearly", lihat [basePlanId]).
 */
internal enum class BillingProduct(val plan: Plan, val productId: String, val type: String) {
    PRO(Plan.PRO, "rizqflow_pro", BillingClient.ProductType.INAPP),
    SYNC(Plan.SYNC, "rizqflow_sync", BillingClient.ProductType.SUBS),
    ;

    companion object {
        fun of(plan: Plan): BillingProduct? = entries.firstOrNull { it.plan == plan }
        fun of(productId: String): BillingProduct? = entries.firstOrNull { it.productId == productId }
    }
}

/** Tipe produk Play untuk kueri jenis ini. */
internal fun ProductKind.billingType(): String = when (this) {
    ProductKind.ONE_TIME -> BillingClient.ProductType.INAPP
    ProductKind.SUBSCRIPTION -> BillingClient.ProductType.SUBS
}

/** Pembelian Play yang sudah dilepas dari tipe Google: hanya data yang dibaca gateway. */
internal data class StorePurchase(
    val products: List<String>,
    val token: String,
    /** Kode `Purchase.PurchaseState`. */
    val stateCode: Int,
    val acknowledged: Boolean,
)

/** Status pembelian Play yang dikenal gateway; null untuk kode lain (mis. UNSPECIFIED_STATE). */
internal fun purchaseStateOf(stateCode: Int): PurchaseState? = when (stateCode) {
    Purchase.PurchaseState.PURCHASED -> PurchaseState.PURCHASED
    Purchase.PurchaseState.PENDING -> PurchaseState.PENDING
    else -> null
}

/**
 * Pembelian Play ke [OwnedPurchase] per produk kita (satu pembelian bisa memuat beberapa produk; produk
 * yang bukan milik Rizqflow dibuang). Pembelian produk kita dengan status yang tidak dikenal
 * ([purchaseStateOf] null) menjadikan seluruh jawaban [GatewayResult.Failure]: jawaban toko yang tidak
 * dipahami bukan bukti "tidak punya", jadi jenis produk itu dianggap gagal ditanyakan dan tidak boleh
 * mencabut paket (lihat [snapshotOf]).
 */
internal fun ownedFromStore(purchases: List<StorePurchase>): GatewayResult<List<OwnedPurchase>> {
    val owned = mutableListOf<OwnedPurchase>()
    for (purchase in purchases) {
        val products = purchase.products.mapNotNull { BillingProduct.of(it) }
        if (products.isEmpty()) continue
        val state = purchaseStateOf(purchase.stateCode) ?: return GatewayResult.Failure(BillingError.UNKNOWN)
        products.mapTo(owned) { OwnedPurchase(it.plan, purchase.token, state, purchase.acknowledged) }
    }
    return GatewayResult.Ok(owned)
}

/**
 * Hasil kueri pembelian per jenis produk menjadi [OwnedSnapshot]: jenis yang kuerinya gagal (atau tidak
 * ditanyakan) tidak masuk [OwnedSnapshot.succeeded] dan pembeliannya tidak ikut.
 */
internal fun snapshotOf(results: Map<ProductKind, GatewayResult<List<OwnedPurchase>>>): OwnedSnapshot {
    val purchases = mutableListOf<OwnedPurchase>()
    val succeeded = mutableSetOf<ProductKind>()
    for (kind in ProductKind.entries) {
        val result = results[kind]
        if (result is GatewayResult.Ok) {
            purchases += result.value
            succeeded += kind
        }
    }
    return OwnedSnapshot(purchases, succeeded)
}

/** Kode jawaban Play (`BillingResponseCode`) ke galat gateway. */
internal fun billingErrorOf(responseCode: Int): BillingError = when (responseCode) {
    BillingClient.BillingResponseCode.USER_CANCELED -> BillingError.CANCELED
    BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> BillingError.ALREADY_OWNED
    BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> BillingError.PRODUCT_NOT_FOUND
    BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED -> BillingError.NOT_SUPPORTED
    BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
    BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
    BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
    BillingClient.BillingResponseCode.NETWORK_ERROR,
    -> BillingError.UNAVAILABLE
    else -> BillingError.UNKNOWN
}
