package com.roziqrizal.rizqflow.ui.paywall

import com.roziqrizal.rizqflow.billing.BillingPeriod
import com.roziqrizal.rizqflow.billing.SubscriptionOffer
import kotlin.test.Test
import kotlin.test.assertEquals

/** Masa yang dibeli dari S21 varian Sync ([effectivePeriod]): hanya masa yang benar-benar ditawarkan toko. */
class SyncPaywallPeriodTest {

    private val monthly = SubscriptionOffer(BillingPeriod.MONTHLY, "Rp19.000", freeTrialDays = 7)
    private val yearly = SubscriptionOffer(BillingPeriod.YEARLY, "Rp149.000", freeTrialDays = null)

    @Test
    fun `selama toko belum menjawab pilihan pengguna berlaku apa adanya`() {
        assertEquals(BillingPeriod.MONTHLY, effectivePeriod(null, BillingPeriod.MONTHLY))
        assertEquals(BillingPeriod.YEARLY, effectivePeriod(null, BillingPeriod.YEARLY))
    }

    @Test
    fun `masa pilihan yang ditawarkan toko tetap dipakai`() {
        assertEquals(BillingPeriod.MONTHLY, effectivePeriod(listOf(monthly, yearly), BillingPeriod.MONTHLY))
        assertEquals(BillingPeriod.YEARLY, effectivePeriod(listOf(monthly, yearly), BillingPeriod.YEARLY))
    }

    @Test
    fun `masa pilihan yang tidak ditawarkan toko pindah ke masa yang tersedia`() {
        // Hanya tahunan yang aktif di Play Console: bulanan (pilihan awal) tidak boleh dibeli.
        assertEquals(BillingPeriod.YEARLY, effectivePeriod(listOf(yearly), BillingPeriod.MONTHLY))
        // Hanya bulanan yang aktif dan pengguna sempat memilih tahunan sebelum toko menjawab.
        assertEquals(BillingPeriod.MONTHLY, effectivePeriod(listOf(monthly), BillingPeriod.YEARLY))
    }

    @Test
    fun `jawaban toko yang kosong tidak mengubah pilihan`() {
        assertEquals(BillingPeriod.MONTHLY, effectivePeriod(emptyList(), BillingPeriod.MONTHLY))
    }
}
