package com.roziqrizal.rizqflow.ui.theme

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.entitlement.Entitlements
import com.roziqrizal.rizqflow.domain.entitlement.Feature

/**
 * Layar Tampilan: mode Otomatis (ikuti sistem), Terang, atau Gelap (Tahap 6, gratis) dan warna
 * aksen (Tahap 7, palet tambahan Pro). Memilih palet Pro tanpa Pro membuka paywall [onOpenPaywall],
 * bukan sekadar menolak; pilihan palet yang sudah tersimpan tidak hilang bila paket turun.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemeSettingsScreen(preference: ThemePreference, entitlements: Entitlements, onOpenPaywall: () -> Unit, onClose: () -> Unit) {
    val spacing = MaterialTheme.spacing
    val mode by preference.mode.collectAsState()
    val storedPalette by preference.palette.collectAsState()
    val palette = storedPalette.effective(entitlements)
    BackHandler(onBack = onClose)

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        TextButton(onClick = onClose, modifier = Modifier.padding(start = spacing.s3, top = spacing.s2)) { Text(stringResource(R.string.onb_back)) }
        Column(modifier = Modifier.padding(horizontal = spacing.s5).padding(bottom = spacing.s5)) {
            Text(stringResource(R.string.tampilan_title), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.tampilan_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.s2),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.s2), modifier = Modifier.padding(top = spacing.s4)) {
                FilterChip(
                    selected = mode == ThemeMode.SYSTEM,
                    onClick = { preference.setMode(ThemeMode.SYSTEM) },
                    label = { Text(stringResource(R.string.tampilan_system)) },
                    colors = rizqflowFilterChipColors(),
                )
                FilterChip(
                    selected = mode == ThemeMode.LIGHT,
                    onClick = { preference.setMode(ThemeMode.LIGHT) },
                    label = { Text(stringResource(R.string.tampilan_light)) },
                    colors = rizqflowFilterChipColors(),
                )
                FilterChip(
                    selected = mode == ThemeMode.DARK,
                    onClick = { preference.setMode(ThemeMode.DARK) },
                    label = { Text(stringResource(R.string.tampilan_dark)) },
                    colors = rizqflowFilterChipColors(),
                )
            }

            Text(
                stringResource(R.string.tampilan_palette_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = spacing.s5),
            )
            Text(
                stringResource(R.string.tampilan_palette_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.s2),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.s2),
                verticalArrangement = Arrangement.spacedBy(spacing.s2),
                modifier = Modifier.padding(top = spacing.s3),
            ) {
                ThemePalette.entries.forEach { option ->
                    val locked = option.isPro && !entitlements.isEnabled(Feature.THEMES)
                    val name = stringResource(option.nameRes)
                    FilterChip(
                        selected = palette == option,
                        onClick = { if (locked) onOpenPaywall() else preference.setPalette(option) },
                        label = { Text(if (locked) stringResource(R.string.tampilan_palette_pro, name) else name) },
                        leadingIcon = { Box(Modifier.size(16.dp).background(option.swatch, CircleShape)) },
                        colors = rizqflowFilterChipColors(),
                    )
                }
            }
        }
    }
}

private val ThemePalette.nameRes: Int
    get() = when (this) {
        ThemePalette.SAGE -> R.string.palette_sage
        ThemePalette.LAUT -> R.string.palette_laut
        ThemePalette.PASIR -> R.string.palette_pasir
        ThemePalette.ANGGUR -> R.string.palette_anggur
    }
