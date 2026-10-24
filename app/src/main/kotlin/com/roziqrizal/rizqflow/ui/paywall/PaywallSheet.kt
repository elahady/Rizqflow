package com.roziqrizal.rizqflow.ui.paywall

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.entitlement.Feature
import com.roziqrizal.rizqflow.domain.entitlement.Plan
import com.roziqrizal.rizqflow.billing.BuyOutcome
import com.roziqrizal.rizqflow.billing.RestoreOutcome
import com.roziqrizal.rizqflow.billing.RestoreReport
import com.roziqrizal.rizqflow.ui.RizqflowIcons
import com.roziqrizal.rizqflow.ui.theme.rizqflow
import com.roziqrizal.rizqflow.ui.theme.spacing

/** Tiga manfaat bawaan (sama seperti mockup docs/ui-flow.md S21), dipakai saat tidak ada aksi pemicu spesifik. */
private val DEFAULT_HIGHLIGHTS = listOf(Feature.UNLIMITED_ROOMS, Feature.ADVANCED_ALLOCATION_RULES, Feature.MULTI_ZAKAT_PROFILE)

@get:StringRes
private val Feature.benefitText: Int
    get() = when (this) {
        Feature.UNLIMITED_ROOMS -> R.string.pro_benefit_unlimited_rooms
        Feature.ADVANCED_ALLOCATION_RULES -> R.string.pro_benefit_advanced_rules
        Feature.ROLE_SYSTEMS -> R.string.pro_benefit_role_systems
        Feature.MULTI_ZAKAT_PROFILE -> R.string.pro_benefit_multi_zakat
        Feature.AUTOMATIC_GOLD_PRICE -> R.string.pro_benefit_gold_price
        Feature.REPORTS_AND_INSIGHTS -> R.string.pro_benefit_reports
        Feature.MULTI_CURRENCY -> R.string.pro_benefit_multi_currency
        Feature.HOME_WIDGET -> R.string.pro_benefit_widget
        Feature.THEMES -> R.string.pro_benefit_themes
        Feature.NOTIFICATION_CAPTURE -> R.string.pro_benefit_notification_capture
        // Fitur Sync memicu varian Sync ([SyncPaywallSheet], lihat [paywallPlanFor]), bukan sheet Pro ini.
        Feature.ENCRYPTED_SYNC, Feature.SHARED_FAMILY_ROOM -> R.string.pro_benefit_unlimited_rooms
    }

/**
 * Varian S21 yang dibuka untuk [trigger]: dipilih dari paket yang dibutuhkan fitur pemicunya (satu-satunya
 * tempat yang memutuskan). Tanpa pemicu (dibuka dari baris "Rizqflow Pro" di menu Lainnya) berarti Pro.
 */
fun paywallPlanFor(trigger: Feature?): Plan = trigger?.requiredPlan ?: Plan.PRO

/**
 * [trigger] didahulukan, lalu diisi dari [DEFAULT_HIGHLIGHTS] sampai tiga butir, tanpa duplikat. Hanya
 * fitur Pro yang boleh didahulukan: fitur Sync punya paywall sendiri dan tidak punya manfaat di sheet ini.
 */
private fun highlightsFor(trigger: Feature?): List<Feature> {
    val proTrigger = trigger?.takeIf { it.requiredPlan == Plan.PRO }
    val ordered = if (proTrigger != null) listOf(proTrigger) + DEFAULT_HIGHLIGHTS.filter { it != proTrigger } else DEFAULT_HIGHLIGHTS
    return ordered.take(3)
}

/**
 * S21 Rizqflow Pro: bottom sheet paywall, bukan layar penuh (docs/ui-flow.md). [trigger] adalah
 * fitur yang memicu, ditampilkan lebih dulu di antara tiga manfaat; null memakai tiga manfaat umum
 * (dibuka dari menu Lainnya). [priceText] adalah harga yang sudah diformat Google Play (dibaca dari
 * Play Console, tidak pernah ditanam di kode); null bila toko belum terhubung. [busy] mengunci
 * tombol selama layar bayar atau pemulihan berjalan supaya tidak terpencet dua kali. Varian Sync
 * (langganan) ada di [SyncPaywallSheet]; [paywallPlanFor] memilih salah satunya.
 */
@Composable
fun PaywallSheet(trigger: Feature?, priceText: String?, busy: Boolean, onBuy: () -> Unit, onRestore: () -> Unit, onDismiss: () -> Unit) {
    val spacing = MaterialTheme.spacing
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.s4).padding(bottom = spacing.s4)) {
        Text(stringResource(R.string.pro_title), style = MaterialTheme.typography.headlineSmall)
        Column(modifier = Modifier.padding(top = spacing.s4), verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
            highlightsFor(trigger).forEach { feature ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.s2)) {
                    Icon(RizqflowIcons.Centang, contentDescription = null, tint = MaterialTheme.rizqflow.statusGood, modifier = Modifier.size(20.dp))
                    Text(stringResource(feature.benefitText), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (priceText != null) {
            Text(
                stringResource(R.string.pro_price, priceText),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = spacing.s5),
            )
        } else {
            Text(
                stringResource(R.string.pro_price_unknown),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.s5),
            )
        }
        Text(stringResource(R.string.pro_no_subscription), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onBuy, enabled = !busy, modifier = Modifier.padding(top = spacing.s4).fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.pro_buy)) }
        TextButton(onClick = onRestore, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pro_restore)) }
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pro_later)) }
        Text(
            stringResource(R.string.pro_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.s2),
        )
    }
}

/** Pesan jujur untuk hasil menekan Beli pada paket [plan]; dipakai pemanggil lewat snackbar. */
@StringRes
fun buyMessage(outcome: BuyOutcome, plan: Plan): Int = when (outcome) {
    BuyOutcome.GRANTED -> if (plan == Plan.SYNC) R.string.sync_buy_granted else R.string.pro_buy_granted
    BuyOutcome.PENDING -> if (plan == Plan.SYNC) R.string.sync_buy_pending else R.string.pro_buy_pending
    BuyOutcome.ALREADY_OWNED -> if (plan == Plan.SYNC) R.string.sync_buy_already_owned else R.string.pro_buy_already_owned
    // Hanya langganan yang bisa "dimiliki tetapi tidak aktif" (ditahan, dijeda, berakhir); Pro tidak pernah dilaporkan begitu.
    BuyOutcome.OWNED_INACTIVE -> if (plan == Plan.SYNC) R.string.sync_buy_owned_inactive else R.string.pro_buy_failed
    BuyOutcome.UNAVAILABLE -> R.string.pro_purchase_unavailable
    BuyOutcome.NOT_SUPPORTED -> R.string.purchase_not_supported
    BuyOutcome.CANCELED, BuyOutcome.FAILED -> R.string.pro_buy_failed
}

/**
 * Pesan jujur untuk hasil Pulihkan pembelian yang ditekan dari paywall paket [plan]. Pulihkan
 * memulihkan semua paket sekaligus (Pro dan Sync) dan jenis produknya ditanyakan sendiri-sendiri,
 * jadi pesannya dipilih dari rincian [report] untuk paket di layar ini, bukan dari hasil keseluruhan:
 * aktif; tertunda (hanya bila paket INI yang tertunda); belum bisa dipastikan (jenis produk paket ini
 * gagal ditanyakan, bukan "tidak punya"); dan baru "belum aktif di akun ini" bila jenis produknya
 * berhasil ditanyakan dan paketnya memang tidak ada.
 */
@StringRes
fun restoreMessage(report: RestoreReport, plan: Plan): Int {
    val sync = plan == Plan.SYNC
    return when {
        plan in report.granted -> if (sync) R.string.sync_restore_done else R.string.pro_restore_done
        plan in report.pending -> if (sync) R.string.sync_restore_pending else R.string.pro_restore_pending
        // Toko sama sekali tidak terjangkau: kabar koneksi, bukan kabar tentang satu paket.
        report.unverified.containsAll(Plan.entries) -> R.string.pro_purchase_unavailable
        plan in report.unverified -> if (sync) R.string.sync_restore_unverified else R.string.pro_restore_unverified
        // Dari sini jenis produk paket ini berhasil ditanyakan dan paketnya tidak ada di sana.
        report.granted.isNotEmpty() -> if (sync) R.string.restore_done_sync_inactive else R.string.restore_done_pro_inactive
        report.outcome == RestoreOutcome.NOTHING_FOUND -> R.string.pro_restore_nothing
        else -> if (sync) R.string.sync_restore_not_active else R.string.pro_restore_not_active
    }
}
