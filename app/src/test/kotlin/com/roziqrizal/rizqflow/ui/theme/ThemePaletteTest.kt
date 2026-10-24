package com.roziqrizal.rizqflow.ui.theme

import androidx.compose.ui.graphics.Color
import com.roziqrizal.rizqflow.domain.entitlement.Plan
import com.roziqrizal.rizqflow.domain.entitlement.PlanEntitlements
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ThemePaletteTest {

    private val free = PlanEntitlements()
    private val pro = PlanEntitlements(setOf(Plan.PRO))

    // Luminans relatif WCAG dihitung sendiri dari komponen sRGB supaya tes tidak bergantung pada Android.
    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double = value.toDouble().let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val x = luminance(a)
        val y = luminance(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    @Test
    fun `Sage gratis selalu berlaku, tanpa Pro maupun dengan Pro`() {
        assertEquals(ThemePalette.SAGE, ThemePalette.SAGE.effective(free))
        assertEquals(ThemePalette.SAGE, ThemePalette.SAGE.effective(pro))
    }

    @Test
    fun `palet Pro berlaku hanya dengan Pro`() {
        ThemePalette.entries.filter { it.isPro }.forEach { palette ->
            assertEquals(ThemePalette.SAGE, palette.effective(free), "${palette.name} tanpa Pro harus kembali ke Sage")
            assertEquals(palette, palette.effective(pro), "${palette.name} dengan Pro harus berlaku")
        }
    }

    @Test
    fun `hanya Sage yang gratis`() {
        assertEquals(listOf(ThemePalette.SAGE), ThemePalette.entries.filter { !it.isPro })
    }

    @Test
    fun `setiap palet punya warna utama sendiri di kedua mode`() {
        val primaries = ThemePalette.entries.map { lightSchemeFor(it).primary }
        assertEquals(primaries.size, primaries.toSet().size)
        val darkPrimaries = ThemePalette.entries.map { darkSchemeFor(it).primary }
        assertEquals(darkPrimaries.size, darkPrimaries.toSet().size)
    }

    @Test
    fun `teks di atas warna aksen memenuhi kontras 4,5 banding 1 di semua palet dan mode`() {
        ThemePalette.entries.forEach { palette ->
            listOf(false to lightSchemeFor(palette), true to darkSchemeFor(palette)).forEach { (dark, scheme) ->
                val label = "${palette.name} ${if (dark) "gelap" else "terang"}"
                assertTrue(contrast(scheme.onPrimary, scheme.primary) >= 4.5, "$label: onPrimary di atas primary")
                assertTrue(contrast(scheme.onPrimaryContainer, scheme.primaryContainer) >= 4.5, "$label: onPrimaryContainer di atas primaryContainer")
                assertTrue(contrast(scheme.onSurface, scheme.primaryFixed) >= 4.5, "$label: label chip terpilih di atas primaryFixed")
                assertTrue(contrast(scheme.primary, scheme.surface) >= 4.5, "$label: warna utama di atas latar")
            }
        }
    }

    @Test
    fun `latar dan warna status tidak berubah antar palet`() {
        ThemePalette.entries.forEach { palette ->
            assertEquals(RizqflowLightScheme.surface, lightSchemeFor(palette).surface)
            assertEquals(RizqflowDarkScheme.onSurface, darkSchemeFor(palette).onSurface)
            val extras = extrasFor(palette, darkTheme = false)
            assertEquals(RizqflowLightExtra.statusCritical, extras.statusCritical)
            assertEquals(RizqflowLightExtra.room1, extras.room1)
        }
    }

    @Test
    fun `ruang netral mengikuti warna utama palet`() {
        assertEquals(RizqflowLightExtra.roomNeutral, extrasFor(ThemePalette.SAGE, darkTheme = false).roomNeutral)
        val laut = extrasFor(ThemePalette.LAUT, darkTheme = false)
        assertEquals(lightSchemeFor(ThemePalette.LAUT).primary, laut.roomNeutral)
        assertNotEquals(RizqflowLightExtra.roomNeutral, laut.roomNeutral)
    }
}
