package com.roziqrizal.rizqflow.billing

import android.app.Activity
import com.roziqrizal.rizqflow.domain.entitlement.Plan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Satu pembelian yang diketahui toko, sudah dipetakan ke [Plan] (produk yang tak dikenal dibuang gateway). */
data class OwnedPurchase(val plan: Plan, val token: String, val state: PurchaseState, val acknowledged: Boolean)

enum class PurchaseState {
    /** Sudah dibayar; paket boleh dibuka. */
    PURCHASED,

    /** Menunggu pembayaran (mis. transfer atau gerai); paket belum boleh dibuka. */
    PENDING,
}

/** Masa tagihan langganan Sync; pembeli memilih salah satu saat berlangganan. */
enum class BillingPeriod { MONTHLY, YEARLY }

/**
 * Jenis produk di toko. Toko menjawab tiap jenis lewat kueri terpisah yang bisa gagal sendiri-sendiri
 * (mis. langganan tidak didukung di perangkat tertentu), jadi "paket ini masih ada di toko atau tidak"
 * hanya boleh disimpulkan dari kueri jenis yang memegang paket itu.
 */
enum class ProductKind {
    /** Sekali beli: memegang [Plan.PRO]. */
    ONE_TIME,

    /** Langganan: memegang [Plan.SYNC]. */
    SUBSCRIPTION,
}

/** Jenis produk yang memegang paket ini di toko. */
fun Plan.productKind(): ProductKind = when (this) {
    Plan.PRO -> ProductKind.ONE_TIME
    Plan.SYNC -> ProductKind.SUBSCRIPTION
}

/**
 * Penawaran satu masa langganan, dibaca dari toko. [formattedPrice] adalah harga berulang setelah
 * uji coba (mis. "Rp19.000"), tidak pernah ditanam di kode. [freeTrialDays] terisi hanya bila toko
 * mengembalikan penawaran dengan fase harga gratis yang lamanya bisa dinyatakan persis dalam hari;
 * null berarti jangan menampilkan teks uji coba.
 */
data class SubscriptionOffer(val period: BillingPeriod, val formattedPrice: String, val freeTrialDays: Int?)

/**
 * Isi kueri "apa yang dimiliki akun Play ini". [succeeded] memuat jenis produk yang kuerinya BERHASIL;
 * [purchases] hanya berisi pembelian dari jenis-jenis itu. Jenis yang tidak ada di [succeeded] gagal
 * ditanyakan, jadi tidak boleh dibaca sebagai "tidak punya".
 */
data class OwnedSnapshot(val purchases: List<OwnedPurchase>, val succeeded: Set<ProductKind>) {
    /** Semua jenis produk berhasil ditanyakan; hanya dalam keadaan ini "tidak ada pembelian" jujur. */
    val complete: Boolean get() = succeeded.containsAll(ProductKind.entries)

    companion object {
        /** Toko sama sekali tidak bisa dihubungi: tidak ada pembelian terbaca dan tidak ada kueri yang berhasil. */
        val Unavailable = OwnedSnapshot(emptyList(), emptySet())
    }
}

enum class BillingError {
    /** Toko tidak bisa dihubungi: Play Store tidak ada atau diblokir, tidak terpasang dari Play, atau jaringan mati. */
    UNAVAILABLE,

    /** Pengguna menutup layar bayar. */
    CANCELED,

    /**
     * Play Store di perangkat ini tidak mendukung jenis produk ini (mis. langganan di Play Store versi lama).
     * Bukan galat sementara dan bukan soal koneksi: mengulang tidak membantu sampai Play Store diperbarui.
     */
    NOT_SUPPORTED,

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
    /** Harga terformat sesuai mata uang dan lokal pengguna, mis. "Rp99.000". Hanya untuk produk sekali beli ([Plan.PRO]). */
    suspend fun price(plan: Plan): GatewayResult<String>

    /**
     * Penawaran langganan Sync per masa (harga dan, bila ada, uji coba), dibaca dari toko. Sukses
     * berarti minimal satu masa tersedia; masa yang tidak ada di toko tidak muncul di daftar.
     */
    suspend fun subscriptionOffers(): GatewayResult<List<SubscriptionOffer>>

    /**
     * Membuka layar bayar toko lalu menunggu hasilnya. [activity] dipanggil saat dibutuhkan; null
     * (layar sudah tertutup) dilaporkan sebagai [BillingError.UNAVAILABLE]. Hasil sukses berisi
     * pembelian yang baru terjadi. [period] wajib untuk produk langganan ([Plan.SYNC]) dan diabaikan
     * untuk produk sekali beli; langganan tanpa [period] gagal sebagai [BillingError.UNKNOWN].
     */
    suspend fun purchase(plan: Plan, period: BillingPeriod?, activity: () -> Activity?): GatewayResult<List<OwnedPurchase>>

    /**
     * Semua pembelian yang masih sah milik akun Play perangkat ini (sekali beli dan langganan); dasar
     * "pulihkan pembelian". Tiap jenis produk ditanyakan sendiri dan hasilnya melaporkan jenis mana yang
     * berhasil, supaya kegagalan satu jenis tidak dibaca sebagai "tidak punya" untuk jenis lain.
     */
    suspend fun queryOwned(): OwnedSnapshot

    /** Toko mengembalikan uang pembelian yang tidak di-acknowledge dalam 3 hari, jadi wajib dipanggil. */
    suspend fun acknowledge(purchase: OwnedPurchase): GatewayResult<Unit>

    /**
     * Isyarat bahwa toko melaporkan pembelian yang BUKAN hasil [purchase] yang sedang ditunggu: pembayaran
     * tertunda yang kemudian lunas, atau pembelian dari luar aplikasi (mis. kode promo di Play Store).
     * Isinya hanya isyarat; keadaan sebenarnya dibaca ulang lewat [queryOwned]. Bawaan: tidak pernah
     * mengisyaratkan apa pun.
     */
    val externalUpdates: Flow<Unit> get() = emptyFlow()
}
