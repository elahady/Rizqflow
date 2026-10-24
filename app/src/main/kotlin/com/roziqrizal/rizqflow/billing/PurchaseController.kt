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

    /**
     * Toko menjawab langganan sudah dimiliki akun Play ini, tetapi kueri langganan yang berhasil tidak
     * mengembalikannya sebagai aktif: berarti ditahan (account hold), dijeda, atau baru berakhir. Tidak ada
     * yang bisa dibuka, dan membeli lagi tidak akan berhasil; yang perlu dibereskan ada di Google Play.
     */
    OWNED_INACTIVE,

    CANCELED,
    UNAVAILABLE,

    /** Play Store di perangkat ini tidak mendukung jenis pembelian ini (mis. langganan di versi lama). */
    NOT_SUPPORTED,

    FAILED,
}

/** Hasil Pulihkan pembelian. */
enum class RestoreOutcome { RESTORED, NOTHING_FOUND, PENDING, UNAVAILABLE }

/**
 * Hasil Pulihkan pembelian beserta rincian per paket, supaya UI bisa jujur untuk paket di layar yang
 * sedang dibuka (Pulihkan membuka semua paket sekaligus, sedangkan layar S21 hanya membahas satu).
 * [granted] paket yang dibuka kali ini, [pending] paket yang pembayarannya tertunda, [unverified] paket
 * yang jenis produknya gagal ditanyakan sehingga tidak bisa dipastikan ada atau tidaknya (bukan "tidak punya").
 */
data class RestoreReport(
    val outcome: RestoreOutcome,
    val granted: Set<Plan>,
    val pending: Set<Plan>,
    val unverified: Set<Plan>,
)

/**
 * Menyambungkan toko ([BillingGateway]) ke penyimpanan paket ([PurchaseStore]). Semua keputusan
 * "kapan paket dibuka" ada di sini dan bisa diuji tanpa Android sungguhan.
 *
 * Prinsipnya: paket hanya dibuka dari pembelian berstatus [PurchaseState.PURCHASED]; pembelian
 * tertunda tidak membuka apa pun. [revokeMissing] mencabut paket yang tidak lagi ada di toko —
 * dimatikan di build debug supaya saklar "Pro (uji)" tidak tercabut tiap aplikasi dibuka. Kegagalan
 * toko (offline, Play tidak tersedia) TIDAK PERNAH mencabut apa pun: pengguna yang sudah membayar
 * tidak boleh kehilangan akses karena jaringan mati.
 *
 * Pro (sekali beli, produk INAPP) dan Sync (langganan, produk SUBS) berdiri sendiri-sendiri: tiap
 * jenis produk ditanyakan ke toko lewat kueri terpisah yang bisa gagal sendiri, dan sebuah paket
 * hanya dicabut bila kueri jenis yang memegangnya SUKSES dan paket itu tidak ada di sana. Beda
 * artinya: Pro hilang dari toko berarti pengembalian dana; Sync hilang dari toko berarti langganan
 * berakhir atau ditahan (account hold), yang perilaku normal. Langganan yang sedang masa tenggang
 * atau sudah dibatalkan tetapi belum berakhir tetap dikembalikan Play, jadi aksesnya tetap terbuka.
 */
class PurchaseController(
    private val store: PurchaseStore,
    private val gateway: BillingGateway,
    private val revokeMissing: Boolean,
) {
    /** Harga produk sekali beli ([Plan.PRO]) dari toko, atau null bila toko belum bisa dihubungi atau produk belum ada. */
    suspend fun price(plan: Plan = Plan.PRO): String? =
        (gateway.price(plan) as? GatewayResult.Ok)?.value

    /**
     * Penawaran langganan Sync per masa dari toko, atau null bila toko belum bisa dihubungi atau
     * produknya belum ada. Hanya masa yang tersedia di toko yang ada di daftar.
     */
    suspend fun syncOffers(): List<SubscriptionOffer>? =
        (gateway.subscriptionOffers() as? GatewayResult.Ok)?.value

    /** [period] wajib untuk langganan ([Plan.SYNC]) dan diabaikan untuk Pro. */
    suspend fun buy(plan: Plan, activity: () -> Activity?, period: BillingPeriod? = null): BuyOutcome =
        when (val result = gateway.purchase(plan, period, activity)) {
            is GatewayResult.Ok -> {
                val applied = apply(result.value)
                when {
                    plan in applied.granted -> BuyOutcome.GRANTED
                    plan in applied.pending -> BuyOutcome.PENDING
                    else -> BuyOutcome.FAILED
                }
            }

            is GatewayResult.Failure -> when (result.error) {
                BillingError.CANCELED -> BuyOutcome.CANCELED
                BillingError.UNAVAILABLE, BillingError.PRODUCT_NOT_FOUND -> BuyOutcome.UNAVAILABLE
                BillingError.NOT_SUPPORTED -> BuyOutcome.NOT_SUPPORTED
                BillingError.ALREADY_OWNED -> restoreAfterAlreadyOwned(plan)
                BillingError.UNKNOWN -> BuyOutcome.FAILED
            }
        }

    /**
     * Tombol Pulihkan pembelian: ambil semua pembelian sah dari toko (Pro dan Sync) dan buka paketnya.
     * Jujur soal kueri yang gagal: [RestoreOutcome.NOTHING_FOUND] hanya bila SEMUA kueri berhasil dan
     * kosong; kalau ada yang gagal dan tidak ada yang terbuka hasilnya [RestoreOutcome.UNAVAILABLE],
     * karena "tidak ada pembelian" tidak bisa disimpulkan dari kueri yang gagal. Pembelian tertunda
     * yang ditemukan tetap dilaporkan sebagai [RestoreOutcome.PENDING] walau kueri lain gagal.
     */
    suspend fun restore(): RestoreOutcome = restoreReport().outcome

    /** Seperti [restore], dengan rincian per paket untuk pesan yang jujur di layar paket tertentu. */
    suspend fun restoreReport(): RestoreReport {
        val owned = gateway.queryOwned()
        val applied = apply(owned.purchases)
        val outcome = when {
            applied.granted.isNotEmpty() -> RestoreOutcome.RESTORED
            applied.pending.isNotEmpty() -> RestoreOutcome.PENDING
            owned.complete -> RestoreOutcome.NOTHING_FOUND
            else -> RestoreOutcome.UNAVAILABLE
        }
        val unverified = Plan.entries.filter { it.productKind() !in owned.succeeded }.toSet()
        return RestoreReport(outcome, applied.granted, applied.pending, unverified)
    }

    /**
     * Pemulihan diam-diam tiap aplikasi dibuka: membuka paket yang sah di toko (pasang ulang, ganti
     * perangkat) dan, bila [revokeMissing], mencabut yang sudah tidak ada. Tanpa pesan ke pengguna.
     */
    suspend fun sync() {
        // Potret paket diambil SEBELUM bertanya ke toko. Paket yang dibuka buy() atau restore() selagi
        // kueri ini berjalan tidak ada di jawaban toko yang lebih lama, jadi tidak boleh ikut dicabut.
        val before = store.plans.value
        val owned = gateway.queryOwned()
        val applied = apply(owned.purchases)
        if (revokeMissing) {
            // Pembelian tertunda tidak dihitung "ada", tapi juga tidak mencabut paket yang sudah sah.
            val stillOwned = applied.granted + applied.pending
            // Hanya paket yang jenis produknya berhasil ditanyakan yang boleh dicabut: kueri yang gagal
            // tidak membuktikan apa-apa tentang paket yang dipegangnya.
            val verifiable = before.filter { it.productKind() in owned.succeeded }
            (verifiable - stillOwned).forEach { store.revoke(it) }
        }
    }

    /**
     * Membaca ulang toko tiap kali gateway mengisyaratkan pembelian yang tidak ditunggu [buy] (mis.
     * pembayaran tertunda yang baru lunas, atau kode promo ditebus di Play Store) lalu membuka dan
     * meng-acknowledge-nya lewat [sync]. Berjalan sampai dibatalkan; dipanggil dari layar yang hidup selama
     * aplikasi terbuka, supaya janji "aktif otomatis begitu lunas" benar tanpa menunggu aplikasi dibuka ulang.
     */
    suspend fun syncOnExternalUpdates() {
        gateway.externalUpdates.collect { sync() }
    }

    private suspend fun restoreAfterAlreadyOwned(plan: Plan): BuyOutcome {
        val owned = gateway.queryOwned()
        val applied = apply(owned.purchases)
        return when {
            plan in applied.granted -> BuyOutcome.ALREADY_OWNED
            plan in applied.pending -> BuyOutcome.PENDING
            // Toko bilang langganan sudah dimiliki, tetapi kueri langganan yang berhasil tidak
            // mengembalikannya sebagai aktif (ditahan, dijeda, atau baru berakhir). Pro sekali beli tidak
            // punya keadaan itu, jadi tetap dilaporkan gagal biasa.
            plan.productKind() == ProductKind.SUBSCRIPTION && ProductKind.SUBSCRIPTION in owned.succeeded -> BuyOutcome.OWNED_INACTIVE
            else -> BuyOutcome.FAILED
        }
    }

    private class Applied(val granted: Set<Plan>, val pending: Set<Plan>)

    private suspend fun apply(purchases: List<OwnedPurchase>): Applied {
        val granted = mutableSetOf<Plan>()
        val pending = mutableSetOf<Plan>()
        for (purchase in purchases) {
            when (purchase.state) {
                PurchaseState.PENDING -> pending += purchase.plan
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
