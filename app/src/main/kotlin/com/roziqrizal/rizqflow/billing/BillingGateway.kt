package com.roziqrizal.rizqflow.billing

import android.app.Activity
import com.roziqrizal.rizqflow.domain.entitlement.Plan

/** Satu pembelian yang diketahui toko, sudah dipetakan ke [Plan] (produk yang tak dikenal dibuang gateway). */
data class OwnedPurchase(val plan: Plan, val token: String, val state: PurchaseState, val acknowledged: Boolean)

enum class PurchaseState {
    /** Sudah dibayar; paket boleh dibuka. */
    PURCHASED,

    /** Menunggu pembayaran (mis. transfer atau gerai); paket belum boleh dibuka. */
    PENDING,
}

enum class BillingError {
    /** Toko tidak bisa dihubungi: Play Store tidak ada atau diblokir, tidak terpasang dari Play, atau jaringan mati. */
    UNAVAILABLE,

    /** Pengguna menutup layar bayar. */
    CANCELED,

    /** Paket ini sudah dimiliki akun Play ini; tidak perlu dibeli lagi, cukup dipulihkan. */
    ALREADY_OWNED,

    /** Produknya belum ada di Play Console atau belum aktif. */
    PRODUCT_NOT_FOUND,

    UNKNOWN,
}

sealed interface GatewayResult<out T> {
    data class Ok<T>(val value: T) : GatewayResult<T>
    data class Failure(val error: BillingError) : GatewayResult<Nothing>
}

/**
 * Pintu ke toko pembayaran, tanpa tipe Google di permukaannya supaya [PurchaseController] bisa diuji
 * dengan gateway palsu. Satu-satunya implementasi sungguhan adalah [PlayBillingGateway]. Harga
 * tidak pernah ada di kode: selalu dibaca dari toko (docs/monetisasi.md).
 */
interface BillingGateway {
    /** Harga terformat sesuai mata uang dan lokal pengguna, mis. "Rp99.000". */
    suspend fun price(plan: Plan): GatewayResult<String>

    /**
     * Membuka layar bayar toko lalu menunggu hasilnya. [activity] dipanggil saat dibutuhkan; null
     * (layar sudah tertutup) dilaporkan sebagai [BillingError.UNAVAILABLE]. Hasil sukses berisi
     * pembelian yang baru terjadi.
     */
    suspend fun purchase(plan: Plan, activity: () -> Activity?): GatewayResult<List<OwnedPurchase>>

    /** Semua pembelian yang masih sah milik akun Play perangkat ini; dasar "pulihkan pembelian". */
    suspend fun queryOwned(): GatewayResult<List<OwnedPurchase>>

    /** Toko mengembalikan uang pembelian yang tidak di-acknowledge dalam 3 hari, jadi wajib dipanggil. */
    suspend fun acknowledge(purchase: OwnedPurchase): GatewayResult<Unit>
}
