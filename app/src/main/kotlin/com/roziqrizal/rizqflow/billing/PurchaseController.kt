package com.roziqrizal.rizqflow.billing

import android.app.Activity
import com.roziqrizal.rizqflow.domain.entitlement.Plan

/** Hasil menekan Beli, supaya UI memilih pesan yang jujur. */
enum class BuyOutcome {
    /** Pembayaran selesai dan paket sudah dibuka. */
    GRANTED,

    /** Dibayar tapi belum lunas (pembayaran tertunda); paket dibuka otomatis begitu lunas. */
    PENDING,

    /** Paket sudah dimiliki akun Play ini dan sekarang dibuka kembali. */
    ALREADY_OWNED,

    CANCELED,
    UNAVAILABLE,
    FAILED,
}

/** Hasil Pulihkan pembelian. */
enum class RestoreOutcome { RESTORED, NOTHING_FOUND, PENDING, UNAVAILABLE }

/**
 * Menyambungkan toko ([BillingGateway]) ke penyimpanan paket ([PurchaseStore]). Semua keputusan
 * "kapan paket dibuka" ada di sini dan bisa diuji tanpa Android sungguhan.
 *
 * Prinsipnya: paket hanya dibuka dari pembelian berstatus [PurchaseState.PURCHASED]; pembelian
 * tertunda tidak membuka apa pun. [revokeMissing] mencabut paket yang tidak lagi ada di toko
 * (pengembalian dana) — dimatikan di build debug supaya saklar "Pro (uji)" tidak tercabut tiap
 * aplikasi dibuka. Kegagalan toko (offline, Play tidak tersedia) TIDAK PERNAH mencabut apa pun:
 * pengguna yang sudah membayar tidak boleh kehilangan akses karena jaringan mati.
 */
class PurchaseController(
    private val store: PurchaseStore,
    private val gateway: BillingGateway,
    private val revokeMissing: Boolean,
) {
    /** Harga dari toko, atau null bila toko belum bisa dihubungi atau produk belum ada. */
    suspend fun price(plan: Plan = Plan.PRO): String? =
        (gateway.price(plan) as? GatewayResult.Ok)?.value

    suspend fun buy(plan: Plan, activity: () -> Activity?): BuyOutcome =
        when (val result = gateway.purchase(plan, activity)) {
            is GatewayResult.Ok -> {
                val applied = apply(result.value)
                when {
                    plan in applied.granted -> BuyOutcome.GRANTED
                    applied.pending -> BuyOutcome.PENDING
                    else -> BuyOutcome.FAILED
                }
            }

            is GatewayResult.Failure -> when (result.error) {
                BillingError.CANCELED -> BuyOutcome.CANCELED
                BillingError.UNAVAILABLE, BillingError.PRODUCT_NOT_FOUND -> BuyOutcome.UNAVAILABLE
                BillingError.ALREADY_OWNED -> restoreAfterAlreadyOwned(plan)
                BillingError.UNKNOWN -> BuyOutcome.FAILED
            }
        }

    /** Tombol Pulihkan pembelian: ambil semua pembelian sah dari toko dan buka paketnya. */
    suspend fun restore(): RestoreOutcome =
        when (val result = gateway.queryOwned()) {
            is GatewayResult.Failure -> RestoreOutcome.UNAVAILABLE
            is GatewayResult.Ok -> {
                val applied = apply(result.value)
                when {
                    applied.granted.isNotEmpty() -> RestoreOutcome.RESTORED
                    applied.pending -> RestoreOutcome.PENDING
                    else -> RestoreOutcome.NOTHING_FOUND
                }
            }
        }

    /**
     * Pemulihan diam-diam tiap aplikasi dibuka: membuka paket yang sah di toko (pasang ulang, ganti
     * perangkat) dan, bila [revokeMissing], mencabut yang sudah tidak ada. Tanpa pesan ke pengguna.
     */
    suspend fun sync() {
        val result = gateway.queryOwned() as? GatewayResult.Ok ?: return
        val applied = apply(result.value)
        if (revokeMissing) {
            // Pembelian tertunda tidak dihitung "ada", tapi juga tidak mencabut paket yang sudah sah.
            val stillOwned = applied.granted + result.value.filter { it.state == PurchaseState.PENDING }.map { it.plan }
            (store.plans.value - stillOwned).forEach { store.revoke(it) }
        }
    }

    private suspend fun restoreAfterAlreadyOwned(plan: Plan): BuyOutcome {
        val result = gateway.queryOwned() as? GatewayResult.Ok ?: return BuyOutcome.FAILED
        val applied = apply(result.value)
        return if (plan in applied.granted) BuyOutcome.ALREADY_OWNED else BuyOutcome.FAILED
    }

    private class Applied(val granted: Set<Plan>, val pending: Boolean)

    private suspend fun apply(purchases: List<OwnedPurchase>): Applied {
        val granted = mutableSetOf<Plan>()
        var pending = false
        for (purchase in purchases) {
            when (purchase.state) {
                PurchaseState.PENDING -> pending = true
                PurchaseState.PURCHASED -> {
                    store.grant(purchase.plan)
                    granted += purchase.plan
                    // Gagal acknowledge tidak menarik paket kembali; sync berikutnya mencoba lagi
                    // (toko baru mengembalikan dana bila tak di-acknowledge dalam 3 hari).
                    if (!purchase.acknowledged) gateway.acknowledge(purchase)
                }
            }
        }
        return Applied(granted, pending)
    }
}
