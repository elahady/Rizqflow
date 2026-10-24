package com.roziqrizal.rizqflow.billing

import android.app.Activity
import com.roziqrizal.rizqflow.domain.entitlement.Plan
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** Penyimpanan paket palsu di memori; dipakai bersama tes [PurchaseController]. */
internal class FakePlanStorage(var names: Set<String> = emptySet()) : PlanStorage {
    override fun read() = names
    override fun write(names: Set<String>) {
        this.names = names
    }
}

/**
 * Toko palsu. Pembelian milik akun diisi lewat [ownedResult]; [failedKinds] membuat kueri jenis produk
 * tertentu gagal (tanpa mengubah hasil jenis lain), meniru FEATURE_NOT_SUPPORTED untuk langganan di
 * perangkat tertentu. [ownedResult] gagal berarti toko sama sekali tidak terjangkau. [queryGate] menahan
 * jawaban [queryOwned] pertama (yang sudah dihitung dari keadaan toko saat ditanya, jadi basi begitu
 * dilepas) sampai tes melepasnya, untuk meniru buy() yang selesai selagi kueri sync() berjalan. [externalUpdates]
 * diisi lewat [signalExternalUpdate], meniru pembelian yang datang di luar buy().
 */
internal class FakeBillingGateway : BillingGateway {
    var priceResult: GatewayResult<String> = GatewayResult.Ok("Rp99.000")
    var offersResult: GatewayResult<List<SubscriptionOffer>> = GatewayResult.Ok(emptyList())
    var purchaseResult: GatewayResult<List<OwnedPurchase>> = GatewayResult.Failure(BillingError.UNAVAILABLE)
    var ownedResult: GatewayResult<List<OwnedPurchase>> = GatewayResult.Ok(emptyList())
    var failedKinds: Set<ProductKind> = emptySet()
    var acknowledgeResult: GatewayResult<Unit> = GatewayResult.Ok(Unit)
    val acknowledged = mutableListOf<String>()
    val purchaseRequests = mutableListOf<Pair<Plan, BillingPeriod?>>()
    var queryGate: CompletableDeferred<Unit>? = null
    var queryCount = 0
        private set
    private val updates = Channel<Unit>(Channel.UNLIMITED)

    override val externalUpdates: Flow<Unit> = updates.receiveAsFlow()

    fun signalExternalUpdate() {
        updates.trySend(Unit)
    }

    override suspend fun price(plan: Plan) = priceResult
    override suspend fun subscriptionOffers() = offersResult
    override suspend fun purchase(plan: Plan, period: BillingPeriod?, activity: () -> Activity?): GatewayResult<List<OwnedPurchase>> {
        purchaseRequests += plan to period
        return purchaseResult
    }

    override suspend fun queryOwned(): OwnedSnapshot {
        queryCount++
        val snapshot = when (val result = ownedResult) {
            is GatewayResult.Failure -> OwnedSnapshot.Unavailable
            is GatewayResult.Ok -> OwnedSnapshot(
                purchases = result.value.filter { it.plan.productKind() !in failedKinds },
                succeeded = ProductKind.entries.toSet() - failedKinds,
            )
        }
        // Sekali pakai: hanya kueri pertama sesudah dipasang yang tertahan, kueri berikutnya langsung dijawab.
        val gate = queryGate
        queryGate = null
        gate?.await()
        return snapshot
    }

    override suspend fun acknowledge(purchase: OwnedPurchase): GatewayResult<Unit> {
        acknowledged += purchase.token
        return acknowledgeResult
    }
}
