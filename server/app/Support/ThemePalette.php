<?php

namespace App\Support;

/**
 * Palet warna aksen web (porting `ThemePalette` di Android, `ui/theme/ThemePalette.kt`). [Sage] adalah
 * identitas bawaan dari docs/design/tokens.css dan selalu gratis; sisanya palet Pro
 * (`Feature::Themes`). Hanya keluarga warna utama yang berganti (lihat `--rf-primary*` di
 * public/css/rizqflow.css, blok `:root[data-palette=...]`); latar, teks, status, dan warna ruang tetap
 * sama supaya status dan ruang tidak berubah makna antar palet.
 *
 * [swatch] adalah warna utama versi terang, sama dengan `--rf-primary` di CSS. Tes
 * `ThemePaletteContrastTest` memeriksa keduanya tetap sama dan kontras teks memenuhi 4,5:1.
 */
enum ThemePalette: string
{
    case Sage = 'sage';
    case Laut = 'laut';
    case Pasir = 'pasir';
    case Anggur = 'anggur';

    public function isPro(): bool
    {
        return $this !== self::Sage;
    }

    /** Warna utama versi terang (sama dengan `--rf-primary` palet ini di CSS). */
    public function swatch(): string
    {
        return match ($this) {
            self::Sage => '#4d6359',
            self::Laut => '#2c5d7c',
            self::Pasir => '#7a5a2a',
            self::Anggur => '#664466',
        };
    }

    public function label(): string
    {
        return match ($this) {
            self::Sage => 'Sage',
            self::Laut => 'Laut',
            self::Pasir => 'Pasir',
            self::Anggur => 'Anggur',
        };
    }

    /**
     * Palet yang benar-benar berlaku: palet Pro yang tersimpan kembali ke Sage bila paket tidak lagi
     * membukanya. Pilihan tersimpan tidak dihapus, jadi memasang Pro lagi langsung mengembalikannya.
     */
    public function effective(Entitlements $entitlements): self
    {
        if (! $this->isPro() || $entitlements->isEnabled(Feature::Themes)) {
            return $this;
        }

        return self::Sage;
    }
}
