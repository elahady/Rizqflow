package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.entitlement.Plan

/**
 * Pencatatan dan verifikasi pembelian Pro dan langganan Sync di server (Tahap 9, roadmap.md
 * "Catat dan verifikasi pembelian"): aplikasi melaporkan token pembelian Google Play, server
 * memverifikasinya ke Play lalu mengaitkan paketnya ke akun Rizqflow ini (supaya web dan Sync
 * mengenali paket yang sama). Kontrak murni tanpa Retrofit/OkHttp; implementasi sungguhan
 * (`RetrofitPurchaseRemote`) ada di `:data`.
 *
 * Ini hanya LAPORAN, bukan sumber hak akses: paket di ponsel tetap dibuka oleh Google Play lewat
 * lapisan entitlement, dan jawaban server (sukses, ditolak, atau gagal) tidak pernah mencabut atau
 * menahannya. Pemanggil yang menjaga hal itu; kontrak ini hanya membawa jawaban apa adanya.
 */
interface PurchaseRemote {
    /**
     * Melaporkan [claims] (1 sampai 10 item; pemanggil membagi kalau lebih). Jawaban sukses memuat
     * [PurchaseClaimResponse.results] sejajar dengan urutan [claims]. Bila server belum bisa
     * memverifikasi (belum dikonfigurasi) atau tidak terjangkau, hasilnya [RemoteResult.Failure] dan
     * tidak ada perubahan paket di server.
     */
    suspend fun claim(apiToken: String, claims: List<PurchaseClaim>): RemoteResult<PurchaseClaimResponse>
}

/** Satu pembelian yang dilaporkan: [productId] produk Play (mis. `rizqflow_pro`) dan token pembeliannya. */
data class PurchaseClaim(val productId: String, val purchaseToken: String)

/**
 * Jawaban klaim. [plans] adalah paket akun ini di server SETELAH klaim (hanya yang dimiliki);
 * dipakai paling jauh untuk informasi, bukan untuk membuka atau menutup paket di ponsel. Nama paket
 * yang tidak dikenal klien ini dibuang. [results] sejajar dengan urutan klaim yang dikirim.
 */
data class PurchaseClaimResponse(val plans: Set<Plan>, val results: List<PurchaseClaimResult>)

data class PurchaseClaimResult(val productId: String, val status: ClaimStatus)

/** Status satu item klaim, persis arti kontrak server. */
enum class ClaimStatus {
    /** Sah sekarang dan dikaitkan ke akun ini. */
    ACTIVE,

    /** Pembayaran tertunda; belum membuka paket di server. */
    PENDING,

    /** Terverifikasi tetapi tidak berhak sekarang (kedaluwarsa, ditahan, dijeda, dikembalikan dananya, dibatalkan). */
    INACTIVE,

    /** Token ini sudah diklaim akun Rizqflow lain. Server tidak pernah menyebut akun siapa. */
    CLAIMED_BY_OTHER,

    /** Server tidak mengenal product_id ini (mis. versi klien dan server tidak sejalan). */
    UNKNOWN_PRODUCT,

    /** Play API tidak terjangkau atau gagal sementara untuk item ini; coba lagi nanti, jangan menarik apa pun. */
    UNVERIFIABLE,

    /** Status yang belum dikenal klien ini (server lebih baru): aman, diperlakukan seperti "belum jelas, coba lagi nanti". */
    UNKNOWN,
}
