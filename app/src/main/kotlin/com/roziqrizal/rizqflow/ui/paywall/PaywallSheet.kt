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
import com.roziqrizal.rizqflow.billing.BuyOutcome
import com.roziqrizal.rizqflow.billing.RestoreOutcome
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
        // Sync (fase 2) tidak pernah memicu paywall Pro ini.
        Feature.ENCRYPTED_SYNC, Feature.SHARED_FAMILY_ROOM -> R.string.pro_benefit_unlimited_rooms
    }

/** [trigger] didahulukan, lalu diisi dari [DEFAULT_HIGHLIGHTS] sampai tiga butir, tanpa duplikat. */
private fun highlightsFor(trigger: Feature?): List<Feature> {
    val ordered = if (trigger != null) listOf(trigger) + DEFAULT_HIGHLIGHTS.filter { it != trigger } else DEFAULT_HIGHLIGHTS
    return ordered.take(3)
}

/**
 * S21 Rizqflow Pro: bottom sheet paywall, bukan layar penuh (docs/ui-flow.md). [trigger] adalah
 * fitur yang memicu, ditampilkan lebih dulu di antara tiga manfaat; null memakai tiga manfaat umum
 * (dibuka dari menu Lainnya). [priceText] adalah harga yang sudah diformat Google Play (dibaca dari
 * Play Console, tidak pernah ditanam di kode); null bila toko belum terhubung. [busy] mengunci
 * tombol selama layar bayar atau pemulihan berjalan supaya tidak terpencet dua kali.
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

/** Pesan jujur untuk hasil menekan Beli; dipakai pemanggil lewat snackbar. */
@StringRes
fun buyMessage(outcome: BuyOutcome): Int = when (outcome) {
    BuyOutcome.GRANTED -> R.string.pro_buy_granted
    BuyOutcome.PENDING -> R.string.pro_buy_pending
    BuyOutcome.ALREADY_OWNED -> R.string.pro_buy_already_owned
    BuyOutcome.UNAVAILABLE -> R.string.pro_purchase_unavailable
    BuyOutcome.CANCELED, BuyOutcome.FAILED -> R.string.pro_buy_failed
}

/** Pesan jujur untuk hasil Pulihkan pembelian. */
@StringRes
fun restoreMessage(outcome: RestoreOutcome): Int = when (outcome) {
    RestoreOutcome.RESTORED -> R.string.pro_restore_done
    RestoreOutcome.NOTHING_FOUND -> R.string.pro_restore_nothing
    RestoreOutcome.PENDING -> R.string.pro_restore_pending
    RestoreOutcome.UNAVAILABLE -> R.string.pro_purchase_unavailable
}
