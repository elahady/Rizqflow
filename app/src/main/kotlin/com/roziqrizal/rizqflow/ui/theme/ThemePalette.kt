package com.roziqrizal.rizqflow.ui.theme

import androidx.compose.ui.graphics.Color
import com.roziqrizal.rizqflow.domain.entitlement.Entitlements
import com.roziqrizal.rizqflow.domain.entitlement.Feature

/**
 * Warna aksen aplikasi (tombol, chip terpilih, penanda). [SAGE] adalah identitas bawaan dari
 * docs/design/tokens.css dan selalu gratis; sisanya palet tambahan berbayar (Pro, `Feature.THEMES`).
 * Hanya keluarga warna utama yang berganti; latar, teks, status, dan warna identitas ruang tetap
 * sama supaya status dan ruang tidak berubah makna antar palet.
 *
 * [swatch] adalah warna contoh di layar Tampilan (utama versi terang).
 */
enum class ThemePalette(val isPro: Boolean, val swatch: Color) {
    SAGE(isPro = false, swatch = Color(0xFF4D6359)),
    LAUT(isPro = true, swatch = Color(0xFF2C5D7C)),
    PASIR(isPro = true, swatch = Color(0xFF7A5A2A)),
    ANGGUR(isPro = true, swatch = Color(0xFF664466)),
}

/**
 * Palet yang benar-benar berlaku: palet Pro yang tersimpan kembali ke [ThemePalette.SAGE] bila paket
 * tidak lagi membukanya (turun paket tidak menghapus pilihan, hanya membuatnya diam, seperti fitur
 * Pro lain; memasang Pro lagi langsung mengembalikannya). Satu-satunya tempat yang memutuskan ini.
 */
fun ThemePalette.effective(entitlements: Entitlements): ThemePalette =
    if (!isPro || entitlements.isEnabled(Feature.THEMES)) this else ThemePalette.SAGE
