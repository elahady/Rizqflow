package com.roziqrizal.rizqflow.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.SegmentedButtonColors
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Jarak (dp, tidak ikut ukuran font), dari docs/design/tokens.css. */
@Immutable
data class Spacing(
    val s1: Dp = 4.dp,
    val s2: Dp = 8.dp,
    val s3: Dp = 12.dp,
    val s4: Dp = 16.dp,
    val s5: Dp = 24.dp,
    val s6: Dp = 32.dp,
)

internal val LocalSpacing = staticCompositionLocalOf { Spacing() }

/** Bentuk: kecil 8, sedang 16, besar 20 dp. */
val RizqflowShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
)

/**
 * Otomatis (bawaan) mengikuti mode gelap sistem; layar Tampilan bisa memaksa terang atau gelap lewat
 * [ThemeMode]. [palette] harus sudah melewati [effective] (pemanggil memeriksa paket, bukan di sini).
 */
@Composable
fun RizqflowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    palette: ThemePalette = ThemePalette.SAGE,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalRizqflowColors provides extrasFor(palette, darkTheme),
        LocalSpacing provides Spacing(),
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) darkSchemeFor(palette) else lightSchemeFor(palette),
            typography = RizqflowTypography,
            shapes = RizqflowShapes,
            content = content,
        )
    }
}

/** Menerjemahkan pilihan layar Tampilan ke gelap/terang sungguhan; [ThemeMode.SYSTEM] mengikuti sistem. */
@Composable
fun ThemeMode.resolveDarkTheme(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** Warna identitas ruang dan status: `MaterialTheme.rizqflow.room(1)`. */
val MaterialTheme.rizqflow: RizqflowExtraColors
    @Composable
    @ReadOnlyComposable
    get() = LocalRizqflowColors.current

val MaterialTheme.spacing: Spacing
    @Composable
    @ReadOnlyComposable
    get() = LocalSpacing.current

/**
 * Membatasi skala font pengguna untuk isi [content]. Angka hero sudah besar sehingga dibatasi
 * 1,25x, dan label navigasi 1,3x, supaya tidak pecah di layar sempit (docs/design/README.md).
 * Teks lain tetap mengikuti ukuran font pengguna penuh.
 */
@Composable
fun CappedFontScale(max: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, fontScale = minOf(density.fontScale, max)),
        content = content,
    )
}

/** Tombol tonal hijau seperti prototipe (bawaan Material memakai secondaryContainer yang biru). */
@Composable
fun rizqflowTonalButtonColors(): ButtonColors = ButtonDefaults.filledTonalButtonColors(
    containerColor = MaterialTheme.colorScheme.primaryFixed,
    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
)

const val HERO_MAX_FONT_SCALE = 1.25f
const val NAV_LABEL_MAX_FONT_SCALE = 1.3f

/** Chip terpilih berwarna hijau muda seperti prototipe, bukan biru bawaan secondaryContainer. */
@Composable
fun rizqflowFilterChipColors(): SelectableChipColors = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryFixed,
    selectedLabelColor = MaterialTheme.colorScheme.onSurface,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onSurface,
)

/** Tab segmen (Pemasukan, Pengeluaran, Transfer) dengan penanda terpilih hijau muda. */
@Composable
fun rizqflowSegmentedColors(): SegmentedButtonColors = SegmentedButtonDefaults.colors(
    activeContainerColor = MaterialTheme.colorScheme.primaryFixed,
    activeContentColor = MaterialTheme.colorScheme.onSurface,
    activeBorderColor = MaterialTheme.colorScheme.primary,
)
