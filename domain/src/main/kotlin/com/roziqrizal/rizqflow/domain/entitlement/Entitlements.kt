package com.roziqrizal.rizqflow.domain.entitlement

/** Paket yang bisa dimiliki pengguna. Pro sekali bayar; Sync langganan (fase 2). */
enum class Plan { PRO, SYNC }

/**
 * Fitur yang bisa terkunci, dan paket yang membukanya (lihat docs/monetisasi.md, "Titik
 * penguncian"). Keamanan, ekspor data, backup lokal, dan dasar zakat tidak ada di sini
 * karena selalu gratis.
 */
enum class Feature(val requiredPlan: Plan) {
    UNLIMITED_ROOMS(Plan.PRO),
    ADVANCED_ALLOCATION_RULES(Plan.PRO),
    ROLE_SYSTEMS(Plan.PRO),
    MULTI_ZAKAT_PROFILE(Plan.PRO),
    AUTOMATIC_GOLD_PRICE(Plan.PRO),
    REPORTS_AND_INSIGHTS(Plan.PRO),
    MULTI_CURRENCY(Plan.PRO),
    HOME_WIDGET(Plan.PRO),
    THEMES(Plan.PRO),
    NOTIFICATION_CAPTURE(Plan.PRO),
    ENCRYPTED_SYNC(Plan.SYNC),
    SHARED_FAMILY_ROOM(Plan.SYNC),
}

/**
 * Satu-satunya tempat yang menjawab "boleh atau tidak". Layar dan logika tidak pernah memeriksa
 * paket sendiri; mereka bertanya ke sini, sehingga model harga bisa berubah tanpa membongkar kode.
 */
interface Entitlements {
    fun isEnabled(feature: Feature): Boolean

    /** Jumlah ruang maksimum, atau null bila tak terbatas. */
    val roomLimit: Int?

    fun canAddRoom(currentRoomCount: Int): Boolean = roomLimit?.let { currentRoomCount < it } ?: true
}

/**
 * Implementasi awal (stub): paket yang dimiliki diberikan dari luar. Di Tahap 7 sumbernya
 * menjadi status pembelian Google Play Billing tanpa mengubah antarmuka [Entitlements].
 * Batas gratis: 5 ruang (disetujui pemilik 2026-09-21). Akun (tunai, bank, e-wallet) tidak dibatasi
 * (diputuskan pemilik 2026-09-24; docs/monetisasi.md).
 */
class PlanEntitlements(
    private val plans: Set<Plan> = emptySet(),
    private val freeRoomLimit: Int = FREE_ROOM_LIMIT,
) : Entitlements {

    override fun isEnabled(feature: Feature): Boolean = feature.requiredPlan in plans

    override val roomLimit: Int? get() = if (isEnabled(Feature.UNLIMITED_ROOMS)) null else freeRoomLimit

    companion object {
        const val FREE_ROOM_LIMIT = 5
    }
}

/**
 * [Entitlements] yang membaca ulang [plans] setiap dipanggil, bukan potret sekali saat dibuat.
 * Dipakai di :app supaya status paket yang berubah selagi aplikasi berjalan (mis. setelah membeli
 * atau memulihkan pembelian) langsung berlaku tanpa membuka ulang ruang kerja akun.
 */
class LivePlanEntitlements(private val plans: () -> Set<Plan>) : Entitlements {
    private fun snapshot() = PlanEntitlements(plans())

    override fun isEnabled(feature: Feature): Boolean = snapshot().isEnabled(feature)

    override val roomLimit: Int? get() = snapshot().roomLimit
}
