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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/**
 * Implementasi [BillingGateway] di atas Google Play Billing Library. Satu instans untuk seluruh
 * aplikasi (dibuat di `RizqflowApplication`), karena [BillingClient] mahal dibuat dan hanya boleh
 * ada satu per proses. Koneksi dibuka malas saat pertama dipakai dan dihubungkan ulang otomatis.
 *
 * Belum teruji sungguhan: butuh aplikasi terpasang dari Play (jalur Internal testing), produk
 * `rizqflow_pro` dan `rizqflow_sync` aktif, dan akun penguji lisensi. Logika keputusan ada di
 * [PurchaseController], pemilihan penawaran di [chooseOffer], dan terjemahan jawaban Play di
 * `BillingTranslation.kt` ([snapshotOf], [ownedFromStore], [billingErrorOf]), semuanya diuji tanpa
 * Google Play; kelas ini sengaja tipis, hanya memanggil `BillingClient` dan meneruskan hasilnya.
 */
class PlayBillingGateway(context: Context) : BillingGateway {
    private val appContext = context.applicationContext
    private val connectLock = Mutex()
    private var pendingPurchase: CompletableDeferred<GatewayResult<List<OwnedPurchase>>>? = null
    private val externalUpdateSignals = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val externalUpdates: Flow<Unit> = externalUpdateSignals.asSharedFlow()

    private val client: BillingClient = BillingClient.newBuilder(appContext)
        .setListener { result, purchases ->
            val waiting = pendingPurchase
            if (waiting != null) {
                waiting.complete(purchaseResult(result, purchases))
            } else if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                // Pembelian yang tidak ditunggu buy(): mis. pembayaran tertunda yang baru lunas. Hanya
                // diisyaratkan; PurchaseController membaca ulang toko lewat queryOwned lalu membuka dan meng-acknowledge.
                externalUpdateSignals.tryEmit(Unit)
            }
        }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    override suspend fun price(plan: Plan): GatewayResult<String> {
        // Harga satu angka hanya ada untuk produk sekali beli; langganan punya harga per masa (subscriptionOffers).
        val product = BillingProduct.of(plan)?.takeIf { it.type == BillingClient.ProductType.INAPP }
            ?: return GatewayResult.Failure(BillingError.PRODUCT_NOT_FOUND)
        return when (val details = productDetails(product)) {
            is GatewayResult.Failure -> details
            is GatewayResult.Ok -> details.value.oneTimePurchaseOfferDetails?.formattedPrice
                ?.let { GatewayResult.Ok(it) }
                ?: GatewayResult.Failure(BillingError.PRODUCT_NOT_FOUND)
        }
    }

    override suspend fun subscriptionOffers(): GatewayResult<List<SubscriptionOffer>> {
        val details = when (val found = productDetails(BillingProduct.SYNC)) {
            is GatewayResult.Failure -> return found
            is GatewayResult.Ok -> found.value
        }
        val storeOffers = details.storeOffers()
        val offers = BillingPeriod.entries.mapNotNull { chooseOffer(storeOffers, it)?.offer }
        return if (offers.isEmpty()) GatewayResult.Failure(BillingError.PRODUCT_NOT_FOUND) else GatewayResult.Ok(offers)
    }

    override suspend fun purchase(plan: Plan, period: BillingPeriod?, activity: () -> Activity?): GatewayResult<List<OwnedPurchase>> {
        val product = BillingProduct.of(plan) ?: return GatewayResult.Failure(BillingError.PRODUCT_NOT_FOUND)
        val subscribing = product.type == BillingClient.ProductType.SUBS
        // Langganan tanpa masa yang dipilih adalah kesalahan pemanggil; jangan menebak bulanan atau tahunan.
        if (subscribing && period == null) return GatewayResult.Failure(BillingError.UNKNOWN)
        val details = when (val found = productDetails(product)) {
            is GatewayResult.Failure -> return found
            is GatewayResult.Ok -> found.value
        }
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details)
        if (subscribing) {
            // Produk langganan wajib membawa offerToken; tanpa penawaran untuk masa itu tidak ada yang bisa dibeli.
            val chosen = chooseOffer(details.storeOffers(), checkNotNull(period)) ?: return GatewayResult.Failure(BillingError.PRODUCT_NOT_FOUND)
            productParams.setOfferToken(chosen.token)
        }
        val host = activity() ?: return GatewayResult.Failure(BillingError.UNAVAILABLE)
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams.build()))
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

    override suspend fun queryOwned(): OwnedSnapshot {
        if (!connect()) return OwnedSnapshot.Unavailable
        // Sekali beli (INAPP) dan langganan (SUBS) ditanyakan sendiri-sendiri: langganan bisa gagal
        // (FEATURE_NOT_SUPPORTED di perangkat tertentu) tanpa membuat hasil INAPP ikut terbuang.
        return snapshotOf(ProductKind.entries.associateWith { queryPurchases(it.billingType()) })
    }

    private suspend fun queryPurchases(type: String): GatewayResult<List<OwnedPurchase>> {
        // includeSuspendedSubscriptions sengaja dibiarkan mati: langganan yang ditahan (account hold)
        // tidak boleh membuka Sync; masa tenggang dan batal-tapi-belum-berakhir tetap dikembalikan Play.
        val params = QueryPurchasesParams.newBuilder().setProductType(type).build()
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
            ownedFromStore(purchases.orEmpty().map { StorePurchase(it.products, it.purchaseToken, it.purchaseState, it.isAcknowledged) })
        } else {
            GatewayResult.Failure(errorOf(result))
        }

    /** Menerjemahkan penawaran langganan Google ke bentuk murni [StoreOffer] untuk [chooseOffer]. */
    private fun ProductDetails.storeOffers(): List<StoreOffer> =
        subscriptionOfferDetails.orEmpty().map { offer ->
            StoreOffer(
                basePlanId = offer.basePlanId,
                offerId = offer.offerId,
                offerToken = offer.offerToken,
                phases = offer.pricingPhases.pricingPhaseList.map { phase ->
                    StorePhase(
                        priceMicros = phase.priceAmountMicros,
                        formattedPrice = phase.formattedPrice,
                        billingPeriod = phase.billingPeriod,
                        recurring = phase.recurrenceMode == ProductDetails.RecurrenceMode.INFINITE_RECURRING,
                    )
                },
            )
        }

    private fun errorOf(result: BillingResult): BillingError = billingErrorOf(result.responseCode)
}
