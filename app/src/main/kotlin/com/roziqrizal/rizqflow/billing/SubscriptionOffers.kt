package com.roziqrizal.rizqflow.billing

/**
 * ID base plan langganan Sync di Play Console. HARUS sama persis dengan yang dibuat di
 * Monetize with Play > Subscriptions > rizqflow_sync (lihat roadmap.md Tahap 8).
 */
internal fun BillingPeriod.basePlanId(): String = when (this) {
    BillingPeriod.MONTHLY -> "monthly"
    BillingPeriod.YEARLY -> "yearly"
}

/** Satu fase harga sebuah penawaran, diterjemahkan dari tipe Google supaya pemilihannya bisa diuji tanpa Android. */
internal data class StorePhase(
    val priceMicros: Long,
    val formattedPrice: String,
    /** Durasi ISO 8601 dari toko, mis. "P1W", "P7D", "P1M". */
    val billingPeriod: String,
    /** Fase terakhir yang berulang tanpa batas: harga yang dibayar seterusnya. */
    val recurring: Boolean,
)

/** Satu penawaran langganan dari toko (base plan polos bila [offerId] null). */
internal data class StoreOffer(
    val basePlanId: String,
    val offerId: String?,
    val offerToken: String,
    val phases: List<StorePhase>,
)

/** Penawaran yang dipilih untuk satu masa: [token] dikirim saat membuka layar bayar, [offer] ditampilkan di S21. */
internal data class ChosenOffer(val token: String, val offer: SubscriptionOffer)

/**
 * Memilih penawaran untuk [period] dari semua penawaran yang toko kembalikan untuk produk langganan
 * (toko hanya mengembalikan penawaran yang boleh dipakai pembeli ini, jadi penawaran uji coba untuk
 * pelanggan baru tidak muncul bagi yang sudah pernah mencoba). Aturannya: ambil hanya penawaran milik
 * base plan masa itu; utamakan yang punya fase harga gratis (uji coba), kalau tidak ada pakai base
 * plan polos (`offerId` null). Null bila masa itu tidak tersedia.
 *
 * Harga yang ditampilkan selalu harga berulang (fase terakhir), bukan harga fase gratis.
 */
internal fun chooseOffer(offers: List<StoreOffer>, period: BillingPeriod): ChosenOffer? {
    val ofPeriod = offers.filter { it.basePlanId == period.basePlanId() }
    val chosen = ofPeriod.firstOrNull { it.hasFreePhase() } ?: ofPeriod.firstOrNull { it.offerId == null } ?: return null
    val regular = chosen.phases.lastOrNull { it.recurring } ?: chosen.phases.lastOrNull() ?: return null
    return ChosenOffer(
        token = chosen.offerToken,
        offer = SubscriptionOffer(period, regular.formattedPrice, trialDaysOf(chosen)),
    )
}

private fun StoreOffer.hasFreePhase(): Boolean = phases.any { it.priceMicros == 0L }

private fun trialDaysOf(offer: StoreOffer): Int? =
    offer.phases.firstOrNull { it.priceMicros == 0L }?.let { daysOf(it.billingPeriod) }

private val ISO_DURATION = Regex("""^P(?:(\d+)Y)?(?:(\d+)M)?(?:(\d+)W)?(?:(\d+)D)?$""")

/**
 * Lama durasi ISO 8601 dalam hari ("P7D" dan "P1W" jadi 7). Null bila tidak bisa dinyatakan persis
 * dalam hari: bulan dan tahun panjangnya berbeda-beda, jadi tidak diperkirakan, dan teks uji coba
 * dihilangkan daripada menampilkan angka yang mungkin keliru.
 */
internal fun daysOf(isoDuration: String): Int? {
    val match = ISO_DURATION.matchEntire(isoDuration) ?: return null
    val (years, months, weeks, days) = match.destructured
    if (years.isNotEmpty() || months.isNotEmpty()) return null
    if (weeks.isEmpty() && days.isEmpty()) return null
    val total = (weeks.toIntOrNull() ?: 0) * 7 + (days.toIntOrNull() ?: 0)
    return total.takeIf { it > 0 }
}
