package com.roziqrizal.rizqflow.billing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Pemilihan penawaran langganan Sync dari jawaban toko, tanpa Google Play ([chooseOffer], [daysOf]). */
class SubscriptionOffersTest {

    private fun free(period: String) = StorePhase(priceMicros = 0, formattedPrice = "Gratis", billingPeriod = period, recurring = false)

    private fun regular(price: String, period: String) = StorePhase(priceMicros = 1_000_000, formattedPrice = price, billingPeriod = period, recurring = true)

    private fun offer(basePlan: String, offerId: String?, vararg phases: StorePhase) =
        StoreOffer(basePlanId = basePlan, offerId = offerId, offerToken = "token-$basePlan-${offerId ?: "polos"}", phases = phases.toList())

    // Bentuk yang dibuat di Play Console: base plan polos dan satu penawaran uji coba gratis untuk pelanggan baru.
    private val monthlyPlain = offer("monthly", null, regular("Rp19.000", "P1M"))
    private val monthlyTrial = offer("monthly", "trial", free("P7D"), regular("Rp19.000", "P1M"))
    private val yearlyPlain = offer("yearly", null, regular("Rp149.000", "P1Y"))
    private val yearlyTrial = offer("yearly", "trial", free("P1W"), regular("Rp149.000", "P1Y"))

    @Test
    fun `utamakan penawaran uji coba gratis, harga yang tampil harga berulang bukan fase gratis`() {
        val chosen = assertNotNull(chooseOffer(listOf(monthlyPlain, monthlyTrial, yearlyPlain, yearlyTrial), BillingPeriod.MONTHLY))

        assertEquals("token-monthly-trial", chosen.token)
        assertEquals(SubscriptionOffer(BillingPeriod.MONTHLY, "Rp19.000", freeTrialDays = 7), chosen.offer)
    }

    @Test
    fun `uji coba dalam minggu dibaca sebagai hari`() {
        val chosen = assertNotNull(chooseOffer(listOf(yearlyPlain, yearlyTrial), BillingPeriod.YEARLY))

        assertEquals("token-yearly-trial", chosen.token)
        assertEquals(SubscriptionOffer(BillingPeriod.YEARLY, "Rp149.000", freeTrialDays = 7), chosen.offer)
    }

    @Test
    fun `tanpa penawaran uji coba (pelanggan lama) pakai base plan polos tanpa teks uji coba`() {
        val chosen = assertNotNull(chooseOffer(listOf(monthlyPlain, yearlyPlain), BillingPeriod.MONTHLY))

        assertEquals("token-monthly-polos", chosen.token)
        assertEquals(SubscriptionOffer(BillingPeriod.MONTHLY, "Rp19.000", freeTrialDays = null), chosen.offer)
    }

    @Test
    fun `urutan penawaran dari toko tidak memengaruhi pilihan`() {
        val chosen = assertNotNull(chooseOffer(listOf(monthlyTrial, monthlyPlain), BillingPeriod.MONTHLY))

        assertEquals("token-monthly-trial", chosen.token)
    }

    @Test
    fun `penawaran diskon berbayar bukan uji coba, base plan polos tetap yang dipilih`() {
        val discount = offer("monthly", "diskon", StorePhase(500_000, "Rp9.500", "P1M", recurring = false), regular("Rp19.000", "P1M"))

        val chosen = assertNotNull(chooseOffer(listOf(discount, monthlyPlain), BillingPeriod.MONTHLY))

        assertEquals("token-monthly-polos", chosen.token)
        assertNull(chosen.offer.freeTrialDays)
    }

    @Test
    fun `tanpa uji coba dan tanpa base plan polos tidak ada yang dipilih`() {
        val discount = offer("monthly", "diskon", StorePhase(500_000, "Rp9.500", "P1M", recurring = false), regular("Rp19.000", "P1M"))

        assertNull(chooseOffer(listOf(discount), BillingPeriod.MONTHLY))
    }

    @Test
    fun `tiap masa hanya memakai penawaran base plan miliknya`() {
        val all = listOf(monthlyTrial, yearlyPlain)

        assertEquals("Rp19.000", chooseOffer(all, BillingPeriod.MONTHLY)?.offer?.formattedPrice)
        assertEquals("Rp149.000", chooseOffer(all, BillingPeriod.YEARLY)?.offer?.formattedPrice)
        assertEquals(7, chooseOffer(all, BillingPeriod.MONTHLY)?.offer?.freeTrialDays)
        assertNull(chooseOffer(all, BillingPeriod.YEARLY)?.offer?.freeTrialDays)
    }

    @Test
    fun `masa yang tidak ada di toko tidak tersedia`() {
        assertNull(chooseOffer(listOf(monthlyPlain), BillingPeriod.YEARLY))
        assertNull(chooseOffer(emptyList(), BillingPeriod.MONTHLY))
    }

    @Test
    fun `uji coba yang lamanya tidak bisa dinyatakan dalam hari tetap dipilih tapi tanpa teks lama`() {
        val monthTrial = offer("monthly", "trial", free("P1M"), regular("Rp19.000", "P1M"))

        val chosen = assertNotNull(chooseOffer(listOf(monthlyPlain, monthTrial), BillingPeriod.MONTHLY))

        assertEquals("token-monthly-trial", chosen.token)
        assertNull(chosen.offer.freeTrialDays)
    }

    @Test
    fun `penawaran tanpa fase harga tidak dipilih`() {
        assertNull(chooseOffer(listOf(offer("monthly", null)), BillingPeriod.MONTHLY))
    }

    @Test
    fun `harga yang tampil adalah fase berulang terakhir, bukan fase pertama`() {
        val twoPhases = offer("monthly", null, StorePhase(1_000_000, "Rp1.000", "P1M", recurring = false), regular("Rp19.000", "P1M"))

        assertEquals("Rp19.000", chooseOffer(listOf(twoPhases), BillingPeriod.MONTHLY)?.offer?.formattedPrice)
    }

    @Test
    fun `ID base plan harus sama dengan yang dibuat di Play Console`() {
        assertEquals("monthly", BillingPeriod.MONTHLY.basePlanId())
        assertEquals("yearly", BillingPeriod.YEARLY.basePlanId())
    }

    @Test
    fun `durasi ISO 8601 dibaca dalam hari bila bisa persis`() {
        assertEquals(7, daysOf("P7D"))
        assertEquals(7, daysOf("P1W"))
        assertEquals(14, daysOf("P2W"))
        assertEquals(3, daysOf("P3D"))
        assertEquals(10, daysOf("P1W3D"))
    }

    @Test
    fun `durasi bulan, tahun, kosong, atau rusak tidak ditebak`() {
        assertNull(daysOf("P1M"))
        assertNull(daysOf("P1Y"))
        assertNull(daysOf("P1M3D"))
        assertNull(daysOf("P0D"))
        assertNull(daysOf("P"))
        assertNull(daysOf(""))
        assertNull(daysOf("7D"))
        assertNull(daysOf("tujuh hari"))
    }
}
