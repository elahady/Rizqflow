package com.roziqrizal.rizqflow.ui.paywall

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.billing.BillingPeriod
import com.roziqrizal.rizqflow.billing.SubscriptionOffer
import com.roziqrizal.rizqflow.ui.RizqflowIcons
import com.roziqrizal.rizqflow.ui.theme.rizqflow
import com.roziqrizal.rizqflow.ui.theme.spacing

/**
 * Masa yang benar-benar dipilih dan dibeli. Selama toko belum menjawab ([offers] null) pilihan pengguna
 * berlaku apa adanya (gateway yang memutuskan saat membeli). Setelah toko menjawab, hanya masa yang ada di
 * jawabannya yang bisa dibeli: bila masa pilihan tidak tersedia (mis. bulanan belum aktif di Play Console),
 * pilihan pindah ke masa pertama yang tersedia, supaya tombol Berlangganan tidak membeli masa yang tidak ada.
 */
internal fun effectivePeriod(offers: List<SubscriptionOffer>?, selected: BillingPeriod): BillingPeriod =
    when {
        offers == null || offers.any { it.period == selected } -> selected
        else -> offers.firstOrNull()?.period ?: selected
    }

/**
 * S21 varian Rizqflow Sync (langganan), terpisah dari [PaywallSheet] Pro. [offers] dibaca dari toko
 * (harga per masa dan, bila toko mengembalikannya, uji coba gratis; tidak ada angka yang ditanam di
 * kode); null berarti toko belum terhubung dan tiap masa menampilkan "harga tampil dari Google Play
 * saat membeli". Teks uji coba hanya muncul untuk masa yang penawarannya punya fase gratis dengan
 * lama yang jelas ([SubscriptionOffer.freeTrialDays]). [owned] menampilkan status aktif (ikon dan
 * teks, bukan hanya warna) menggantikan pilihan masa dan tombol langganan. Masa yang dipilih awalnya
 * bulanan dan diteruskan ke [onBuy]; setelah toko menjawab, masa yang tidak ditawarkannya ditandai
 * "tidak tersedia" dan tidak bisa dipilih (pilihan pindah ke masa yang tersedia). [busy] mengunci tombol
 * selama layar bayar atau pemulihan berjalan.
 */
@Composable
fun SyncPaywallSheet(
    offers: List<SubscriptionOffer>?,
    owned: Boolean,
    busy: Boolean,
    onBuy: (BillingPeriod) -> Unit,
    onRestore: () -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    var selected by rememberSaveable { mutableStateOf(BillingPeriod.MONTHLY) }
    val chosenPeriod = effectivePeriod(offers, selected)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.s4)
            .padding(bottom = spacing.s4),
    ) {
        Text(stringResource(R.string.sync_title), style = MaterialTheme.typography.headlineSmall)
        if (owned) {
            Row(
                modifier = Modifier.padding(top = spacing.s3),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.s2),
            ) {
                Icon(RizqflowIcons.Centang, contentDescription = null, tint = MaterialTheme.rizqflow.statusGood, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.sync_status_active), style = MaterialTheme.typography.titleSmall)
            }
        }
        Column(modifier = Modifier.padding(top = spacing.s4), verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
            listOf(R.string.sync_benefit_sync, R.string.sync_benefit_family).forEach { benefit ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.s2)) {
                    Icon(RizqflowIcons.Centang, contentDescription = null, tint = MaterialTheme.rizqflow.statusGood, modifier = Modifier.size(20.dp))
                    Text(stringResource(benefit), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (!owned) {
            Column(modifier = Modifier.padding(top = spacing.s5).selectableGroup(), verticalArrangement = Arrangement.spacedBy(spacing.s1)) {
                BillingPeriod.entries.forEach { period ->
                    val offer = offers?.firstOrNull { it.period == period }
                    PeriodOption(
                        period = period,
                        offer = offer,
                        // Toko sudah menjawab tetapi masa ini tidak ada di jawabannya: tidak bisa dibeli.
                        unavailable = offers != null && offer == null,
                        selected = period == chosenPeriod,
                        enabled = !busy,
                        onSelect = { selected = period },
                    )
                }
            }
            Button(
                onClick = { onBuy(chosenPeriod) },
                enabled = !busy,
                modifier = Modifier.padding(top = spacing.s4).fillMaxWidth().height(52.dp),
            ) { Text(stringResource(R.string.sync_buy)) }
            TextButton(onClick = onRestore, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pro_restore)) }
        }
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (owned) R.string.sync_close else R.string.pro_later))
        }
        // Catatan jujur tentang apa yang dijual: satu langganan untuk keluarga, bukan bagian dari Pro,
        // dan yang tetap gratis tidak ikut terkunci (docs/monetisasi.md).
        Column(modifier = Modifier.padding(top = spacing.s2), verticalArrangement = Arrangement.spacedBy(spacing.s1)) {
            listOf(R.string.sync_note_family, R.string.sync_note_free, R.string.sync_note_manage).forEach { note ->
                Text(stringResource(note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Satu pilihan masa: tombol radio (terpilih ditandai bentuknya, bukan hanya warna), harga dari toko, dan
 * uji coba bila ada. [unavailable] berarti toko sudah menjawab tetapi tidak menawarkan masa ini: barisnya
 * dinonaktifkan dan berteks "tidak tersedia" (bukan hanya redup).
 */
@Composable
private fun PeriodOption(
    period: BillingPeriod,
    offer: SubscriptionOffer?,
    unavailable: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val selectable = enabled && !unavailable
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = selectable, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = spacing.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Baris-lah yang menangani sentuhan dan semantik pilihan; tombol radio hanya menggambar status.
        RadioButton(selected = selected, onClick = null, enabled = selectable)
        Column(modifier = Modifier.weight(1f).padding(start = spacing.s2)) {
            Text(
                stringResource(if (period == BillingPeriod.YEARLY) R.string.sync_period_yearly else R.string.sync_period_monthly),
                style = MaterialTheme.typography.titleSmall,
            )
            if (offer != null) {
                Text(
                    stringResource(if (period == BillingPeriod.YEARLY) R.string.sync_price_yearly else R.string.sync_price_monthly, offer.formattedPrice),
                    style = MaterialTheme.typography.bodyMedium,
                )
                offer.freeTrialDays?.let { days ->
                    Text(
                        stringResource(R.string.sync_trial_days, days),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    stringResource(if (unavailable) R.string.sync_period_unavailable else R.string.pro_price_unknown),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
