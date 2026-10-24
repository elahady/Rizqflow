package com.roziqrizal.rizqflow.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.roziqrizal.rizqflow.domain.entitlement.Plan
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/**
 * Pemetaan paket ke produk di Play Console. ID produk harus SAMA PERSIS dengan yang dibuat di
 * Monetize with Play > Products. Sync (langganan) belum dijual sampai rilis 1.1, jadi belum ada di sini.
 */
internal enum class BillingProduct(val plan: Plan, val productId: String, val type: String) {
    PRO(Plan.PRO, "rizqflow_pro", BillingClient.ProductType.INAPP),
    ;

    companion object {
        fun of(plan: Plan): BillingProduct? = entries.firstOrNull { it.plan == plan }
        fun of(productId: String): BillingProduct? = entries.firstOrNull { it.productId == productId }
    }
}

/**
 * Implementasi [BillingGateway] di atas Google Play Billing Library. Satu instans untuk seluruh
 * aplikasi (dibuat di `RizqflowApplication`), karena [BillingClient] mahal dibuat dan hanya boleh
 * ada satu per proses. Koneksi dibuka malas saat pertama dipakai dan dihubungkan ulang otomatis.
 *
 * Belum teruji sungguhan: butuh aplikasi terpasang dari Play (jalur Internal testing), produk
 * `rizqflow_pro` aktif, dan akun penguji lisensi. Logika keputusan ada di [PurchaseController],
 * yang diuji dengan gateway palsu; kelas ini sengaja tipis supaya hanya menerjemahkan.
 */
class PlayBillingGateway(context: Context) : BillingGateway {
    private val appContext = context.applicationContext
    private val connectLock = Mutex()
    private var pendingPurchase: CompletableDeferred<GatewayResult<List<OwnedPurchase>>>? = null

    private val client: BillingClient = BillingClient.newBuilder(appContext)
        .setListener { result, purchases -> pendingPurchase?.complete(purchaseResult(result, purchases)) }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    override suspend fun price(plan: Plan): GatewayResult<String> {
        val product = BillingProduct.of(plan) ?: return GatewayResult.Failure(BillingError.PRODUCT_NOT_FOUND)
        return when (val details = productDetails(product)) {
            is GatewayResult.Failure -> details
            is GatewayResult.Ok -> details.value.oneTimePurchaseOfferDetails?.formattedPrice
                ?.let { GatewayResult.Ok(it) }
                ?: GatewayResult.Failure(BillingError.PRODUCT_NOT_FOUND)
        }
    }

    override suspend fun purchase(plan: Plan, activity: () -> Activity?): GatewayResult<List<OwnedPurchase>> {
        val product = BillingProduct.of(plan) ?: return GatewayResult.Failure(BillingError.PRODUCT_NOT_FOUND)
        val details = when (val found = productDetails(product)) {
            is GatewayResult.Failure -> return found
            is GatewayResult.Ok -> found.value
        }
        val host = activity() ?: return GatewayResult.Failure(BillingError.UNAVAILABLE)
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).build()),
            )
            .build()

        val outcome = CompletableDeferred<GatewayResult<List<OwnedPurchase>>>()
        pendingPurchase = outcome
        val launched = client.launchBillingFlow(host, flowParams)
        if (launched.responseCode != BillingClient.BillingResponseCode.OK) {
            pendingPurchase = null
            return GatewayResult.Failure(errorOf(launched))
        }
        return try {
            outcome.await()
        } finally {
            pendingPurchase = null
        }
    }

    override suspend fun queryOwned(): GatewayResult<List<OwnedPurchase>> {
        if (!connect()) return GatewayResult.Failure(BillingError.UNAVAILABLE)
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        return suspendCancellableCoroutine { continuation ->
            client.queryPurchasesAsync(params) { result, purchases ->
                continuation.resume(purchaseResult(result, purchases))
            }
        }
    }

    override suspend fun acknowledge(purchase: OwnedPurchase): GatewayResult<Unit> {
        if (!connect()) return GatewayResult.Failure(BillingError.UNAVAILABLE)
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.token).build()
        return suspendCancellableCoroutine { continuation ->
            client.acknowledgePurchase(params) { result ->
                continuation.resume(
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) GatewayResult.Ok(Unit) else GatewayResult.Failure(errorOf(result)),
                )
            }
        }
    }

    private suspend fun productDetails(product: BillingProduct): GatewayResult<ProductDetails> {
        if (!connect()) return GatewayResult.Failure(BillingError.UNAVAILABLE)
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(QueryProductDetailsParams.Product.newBuilder().setProductId(product.productId).setProductType(product.type).build()),
            )
            .build()
        return suspendCancellableCoroutine { continuation ->
            client.queryProductDetailsAsync(params) { result, queryResult ->
                val found = queryResult.productDetailsList.orEmpty().firstOrNull { it.productId == product.productId }
                continuation.resume(
                    when {
                        result.responseCode != BillingClient.BillingResponseCode.OK -> GatewayResult.Failure(errorOf(result))
                        found == null -> GatewayResult.Failure(BillingError.PRODUCT_NOT_FOUND)
                        else -> GatewayResult.Ok(found)
                    },
                )
            }
        }
    }

    /** Membuka koneksi ke Play bila belum; false bila Play Store tidak bisa dihubungi. */
    private suspend fun connect(): Boolean = connectLock.withLock {
        if (client.isReady) return@withLock true
        suspendCancellableCoroutine { continuation ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (continuation.isActive) continuation.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                }

                override fun onBillingServiceDisconnected() = Unit
            })
        }
    }

    private fun purchaseResult(result: BillingResult, purchases: List<Purchase>?): GatewayResult<List<OwnedPurchase>> =
        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
            GatewayResult.Ok(purchases.orEmpty().flatMap(::ownedFrom))
        } else {
            GatewayResult.Failure(errorOf(result))
        }

    /** Satu pembelian Play bisa memuat beberapa produk; produk yang tidak dikenal dibuang. */
    private fun ownedFrom(purchase: Purchase): List<OwnedPurchase> {
        val state = when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> PurchaseState.PURCHASED
            Purchase.PurchaseState.PENDING -> PurchaseState.PENDING
            else -> return emptyList()
        }
        return purchase.products.mapNotNull { BillingProduct.of(it) }
            .map { OwnedPurchase(it.plan, purchase.purchaseToken, state, purchase.isAcknowledged) }
    }

    private fun errorOf(result: BillingResult): BillingError = when (result.responseCode) {
        BillingClient.BillingResponseCode.USER_CANCELED -> BillingError.CANCELED
        BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> BillingError.ALREADY_OWNED
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> BillingError.PRODUCT_NOT_FOUND
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
        BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
        BillingClient.BillingResponseCode.NETWORK_ERROR,
        BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED,
        -> BillingError.UNAVAILABLE
        else -> BillingError.UNKNOWN
    }
}
