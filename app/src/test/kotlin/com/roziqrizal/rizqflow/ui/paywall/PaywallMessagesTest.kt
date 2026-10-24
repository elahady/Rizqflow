package com.roziqrizal.rizqflow.ui.paywall

import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.billing.BuyOutcome
import com.roziqrizal.rizqflow.billing.FakeBillingGateway
import com.roziqrizal.rizqflow.billing.FakePlanStorage
import com.roziqrizal.rizqflow.billing.GatewayResult
import com.roziqrizal.rizqflow.billing.OwnedPurchase
import com.roziqrizal.rizqflow.billing.ProductKind
import com.roziqrizal.rizqflow.billing.PurchaseController
import com.roziqrizal.rizqflow.billing.PurchaseState
import com.roziqrizal.rizqflow.billing.PurchaseStore
import com.roziqrizal.rizqflow.billing.RestoreOutcome
import com.roziqrizal.rizqflow.billing.RestoreReport
import com.roziqrizal.rizqflow.domain.entitlement.Feature
import com.roziqrizal.rizqflow.domain.entitlement.Plan
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pemilihan varian S21 dan pesan hasil beli/pulihkan per paket: Pro tidak berubah, Sync punya kabar sendiri. */
class PaywallMessagesTest {

    private fun report(
        outcome: RestoreOutcome,
        granted: Set<Plan> = emptySet(),
        pending: Set<Plan> = emptySet(),
        unverified: Set<Plan> = emptySet(),
    ) = RestoreReport(outcome, granted, pending, unverified)

    private fun purchase(plan: Plan, state: PurchaseState = PurchaseState.PURCHASED) = OwnedPurchase(plan, "token-${plan.name}", state, acknowledged = true)

    /** Hasil Pulihkan yang sebenarnya, dari toko palsu dan [PurchaseController], bukan disusun tangan. */
    private fun restoreFrom(owned: List<OwnedPurchase>, failedKinds: Set<ProductKind> = emptySet()): RestoreReport = runBlocking {
        val gateway = FakeBillingGateway().apply {
            ownedResult = GatewayResult.Ok(owned)
            this.failedKinds = failedKinds
        }
        PurchaseController(PurchaseStore(FakePlanStorage()), gateway, revokeMissing = true).restoreReport()
    }

    @Test
    fun `varian paywall mengikuti paket yang dibutuhkan fitur pemicu`() {
        assertEquals(Plan.SYNC, paywallPlanFor(Feature.ENCRYPTED_SYNC))
        assertEquals(Plan.SYNC, paywallPlanFor(Feature.SHARED_FAMILY_ROOM))
        Feature.entries.filter { it.requiredPlan == Plan.PRO }.forEach {
            assertEquals(Plan.PRO, paywallPlanFor(it), "pemicu ${it.name}")
        }
    }

    @Test
    fun `tanpa pemicu (baris Rizqflow Pro di menu Lainnya) variannya Pro`() {
        assertEquals(Plan.PRO, paywallPlanFor(null))
    }

    @Test
    fun `pesan beli Pro tetap sama seperti sebelum ada Sync`() {
        assertEquals(R.string.pro_buy_granted, buyMessage(BuyOutcome.GRANTED, Plan.PRO))
        assertEquals(R.string.pro_buy_pending, buyMessage(BuyOutcome.PENDING, Plan.PRO))
        assertEquals(R.string.pro_buy_already_owned, buyMessage(BuyOutcome.ALREADY_OWNED, Plan.PRO))
    }

    @Test
    fun `pesan beli Sync menyebut Sync, kegagalan toko memakai kabar yang sama`() {
        assertEquals(R.string.sync_buy_granted, buyMessage(BuyOutcome.GRANTED, Plan.SYNC))
        assertEquals(R.string.sync_buy_pending, buyMessage(BuyOutcome.PENDING, Plan.SYNC))
        assertEquals(R.string.sync_buy_already_owned, buyMessage(BuyOutcome.ALREADY_OWNED, Plan.SYNC))
        for (plan in Plan.entries) {
            assertEquals(R.string.pro_purchase_unavailable, buyMessage(BuyOutcome.UNAVAILABLE, plan))
            assertEquals(R.string.pro_buy_failed, buyMessage(BuyOutcome.FAILED, plan))
            assertEquals(R.string.pro_buy_failed, buyMessage(BuyOutcome.CANCELED, plan))
        }
    }

    @Test
    fun `fitur tidak didukung perangkat menyuruh memperbarui Google Play, bukan menyalahkan koneksi`() {
        for (plan in Plan.entries) {
            assertEquals(R.string.purchase_not_supported, buyMessage(BuyOutcome.NOT_SUPPORTED, plan))
        }
    }

    @Test
    fun `langganan yang dimiliki tetapi tidak aktif punya kabar sendiri, Pro tetap kabar gagal biasa`() {
        assertEquals(R.string.sync_buy_owned_inactive, buyMessage(BuyOutcome.OWNED_INACTIVE, Plan.SYNC))
        assertEquals(R.string.pro_buy_failed, buyMessage(BuyOutcome.OWNED_INACTIVE, Plan.PRO))
    }

    @Test
    fun `pulihkan dari paywall Pro tetap berbunyi sama bila Pro memang aktif`() {
        val restored = report(RestoreOutcome.RESTORED, granted = setOf(Plan.PRO))

        assertEquals(R.string.pro_restore_done, restoreMessage(restored, Plan.PRO))
        assertEquals(R.string.pro_restore_nothing, restoreMessage(report(RestoreOutcome.NOTHING_FOUND), Plan.PRO))
        assertEquals(R.string.pro_restore_pending, restoreMessage(report(RestoreOutcome.PENDING, pending = setOf(Plan.PRO)), Plan.PRO))
        assertEquals(R.string.pro_purchase_unavailable, restoreMessage(report(RestoreOutcome.UNAVAILABLE, unverified = Plan.entries.toSet()), Plan.PRO))
    }

    @Test
    fun `pulihkan dari paywall Sync menyebut Sync`() {
        val restored = report(RestoreOutcome.RESTORED, granted = setOf(Plan.SYNC))

        assertEquals(R.string.sync_restore_done, restoreMessage(restored, Plan.SYNC))
        assertEquals(R.string.pro_restore_nothing, restoreMessage(report(RestoreOutcome.NOTHING_FOUND), Plan.SYNC))
        assertEquals(R.string.sync_restore_pending, restoreMessage(report(RestoreOutcome.PENDING, pending = setOf(Plan.SYNC)), Plan.SYNC))
        assertEquals(R.string.pro_purchase_unavailable, restoreMessage(report(RestoreOutcome.UNAVAILABLE, unverified = Plan.entries.toSet()), Plan.SYNC))
    }

    @Test
    fun `pulihkan yang hanya membuka paket lain tidak mengaku paket di layar aktif`() {
        // Dari paywall Sync, yang terpulihkan hanya Pro dan kueri langganan berhasil: Sync memang belum aktif.
        assertEquals(R.string.restore_done_sync_inactive, restoreMessage(report(RestoreOutcome.RESTORED, granted = setOf(Plan.PRO)), Plan.SYNC))
        // Dari paywall Pro, yang terpulihkan hanya Sync dan kueri sekali beli berhasil: Pro memang belum aktif.
        assertEquals(R.string.restore_done_pro_inactive, restoreMessage(report(RestoreOutcome.RESTORED, granted = setOf(Plan.SYNC)), Plan.PRO))
    }

    @Test
    fun `kueri paket ini gagal tidak dibaca sebagai tidak punya walau paket lain terpulihkan`() {
        // Pro terpulihkan, kueri langganan gagal: dari paywall Sync, jangan bilang "Sync belum aktif di akun ini".
        val proOnly = report(RestoreOutcome.RESTORED, granted = setOf(Plan.PRO), unverified = setOf(Plan.SYNC))
        assertEquals(R.string.sync_restore_unverified, restoreMessage(proOnly, Plan.SYNC))
        assertEquals(R.string.pro_restore_done, restoreMessage(proOnly, Plan.PRO))

        // Simetris: Sync terpulihkan, kueri sekali beli gagal.
        val syncOnly = report(RestoreOutcome.RESTORED, granted = setOf(Plan.SYNC), unverified = setOf(Plan.PRO))
        assertEquals(R.string.pro_restore_unverified, restoreMessage(syncOnly, Plan.PRO))
        assertEquals(R.string.sync_restore_done, restoreMessage(syncOnly, Plan.SYNC))
    }

    @Test
    fun `pembayaran tertunda milik paket lain tidak tampil sebagai kabar paket di layar`() {
        // Hanya Pro yang tertunda, ditekan dari paywall Sync: Sync tidak sedang menunggu apa pun.
        val proPending = report(RestoreOutcome.PENDING, pending = setOf(Plan.PRO))
        assertEquals(R.string.sync_restore_not_active, restoreMessage(proPending, Plan.SYNC))
        assertEquals(R.string.pro_restore_pending, restoreMessage(proPending, Plan.PRO))

        // Hanya Sync yang tertunda, ditekan dari paywall Pro.
        val syncPending = report(RestoreOutcome.PENDING, pending = setOf(Plan.SYNC))
        assertEquals(R.string.pro_restore_not_active, restoreMessage(syncPending, Plan.PRO))
        assertEquals(R.string.sync_restore_pending, restoreMessage(syncPending, Plan.SYNC))
    }

    @Test
    fun `kueri sebagian gagal dan tidak ada yang terbuka, tiap layar hanya bicara soal paketnya`() {
        // Kueri langganan gagal, kueri sekali beli sukses dan kosong.
        val syncUnverified = report(RestoreOutcome.UNAVAILABLE, unverified = setOf(Plan.SYNC))

        assertEquals(R.string.sync_restore_unverified, restoreMessage(syncUnverified, Plan.SYNC))
        assertEquals(R.string.pro_restore_not_active, restoreMessage(syncUnverified, Plan.PRO))
        // Kebalikannya.
        val proUnverified = report(RestoreOutcome.UNAVAILABLE, unverified = setOf(Plan.PRO))
        assertEquals(R.string.pro_restore_unverified, restoreMessage(proUnverified, Plan.PRO))
        assertEquals(R.string.sync_restore_not_active, restoreMessage(proUnverified, Plan.SYNC))
    }

    // --- Dari pengendali sungguhan ke pesan, untuk skenario yang dilaporkan review ---

    @Test
    fun `akun memegang Pro dan Sync, kueri langganan gagal, pulihkan dari paywall Sync tidak menyangkal langganan`() {
        val report = restoreFrom(listOf(purchase(Plan.PRO), purchase(Plan.SYNC)), failedKinds = setOf(ProductKind.SUBSCRIPTION))

        assertEquals(R.string.sync_restore_unverified, restoreMessage(report, Plan.SYNC))
    }

    @Test
    fun `akun memegang Pro dan Sync, kueri sekali beli gagal, pulihkan dari paywall Pro tidak menyangkal pembelian`() {
        val report = restoreFrom(listOf(purchase(Plan.PRO), purchase(Plan.SYNC)), failedKinds = setOf(ProductKind.ONE_TIME))

        assertEquals(R.string.pro_restore_unverified, restoreMessage(report, Plan.PRO))
    }

    @Test
    fun `hanya Pro yang tertunda, pulihkan dari paywall Sync tidak menjanjikan Sync aktif otomatis`() {
        val report = restoreFrom(listOf(purchase(Plan.PRO, PurchaseState.PENDING)))

        assertEquals(RestoreOutcome.PENDING, report.outcome)
        assertEquals(R.string.sync_restore_not_active, restoreMessage(report, Plan.SYNC))
        assertEquals(R.string.pro_restore_pending, restoreMessage(report, Plan.PRO))
    }
}
