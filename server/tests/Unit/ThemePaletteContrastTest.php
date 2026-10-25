<?php

namespace Tests\Unit;

use App\Support\Entitlements;
use App\Support\Feature;
use App\Support\Plan;
use App\Support\ThemePalette;
use PHPUnit\Framework\TestCase;

/**
 * Porting dari ThemePaletteTest di Android. Kontras dihitung dari nilai sungguhan di
 * public/css/rizqflow.css (bukan salinan di tes), jadi tes ini menangkap kalau CSS berubah tanpa
 * menjaga kontras. Juga memastikan swatch di enum sama dengan warna utama di CSS, dan aturan
 * "palet Pro kembali ke Sage bila paket turun" (ThemePalette::effective).
 */
class ThemePaletteContrastTest extends TestCase
{
    private static ?string $css = null;

    private static function css(): string
    {
        return self::$css ??= file_get_contents(dirname(__DIR__, 2).'/public/css/rizqflow.css');
    }

    /**
     * Deklarasi warna hex dari blok selektor tertentu. Hanya nilai hex yang diambil; var() dan nilai
     * lain tidak dipakai untuk kontras.
     *
     * @return array<string, string>
     */
    private function declarations(string $selector): array
    {
        if (! preg_match('/'.preg_quote($selector, '/').'\s*\{([^}]*)\}/', self::css(), $block)) {
            return [];
        }

        preg_match_all('/(--rf-[a-z0-9-]+)\s*:\s*(#[0-9a-fA-F]{6})\s*;/', $block[1], $matches, PREG_SET_ORDER);

        $values = [];
        foreach ($matches as $match) {
            $values[$match[1]] = strtolower($match[2]);
        }

        return $values;
    }

    /**
     * Token yang berlaku untuk satu palet dan satu mode, dengan urutan kaskade yang sama seperti
     * browser: dasar mode dulu, lalu override palet.
     *
     * @return array<string, string>
     */
    private function effectiveTokens(ThemePalette $palette, string $mode): array
    {
        $name = $palette->value;

        return match ($mode) {
            'terang' => array_merge(
                $this->declarations(':root'),
                $this->declarations(':root[data-palette="'.$name.'"]'),
            ),
            'gelap-sistem' => array_merge(
                $this->declarations(':root:not([data-theme="light"])'),
                $this->declarations(':root[data-palette="'.$name.'"]:not([data-theme="light"])'),
            ),
            'gelap-pilihan' => array_merge(
                $this->declarations(':root[data-theme="dark"]'),
                $this->declarations(':root[data-palette="'.$name.'"][data-theme="dark"]'),
            ),
        };
    }

    /** Luminans relatif WCAG dari warna hex. */
    private function luminance(string $hex): float
    {
        $channels = [];
        foreach ([0, 2, 4] as $offset) {
            $value = hexdec(substr(ltrim($hex, '#'), $offset, 2)) / 255;
            $channels[] = $value <= 0.03928 ? $value / 12.92 : (($value + 0.055) / 1.055) ** 2.4;
        }

        return 0.2126 * $channels[0] + 0.7152 * $channels[1] + 0.0722 * $channels[2];
    }

    private function contrast(string $a, string $b): float
    {
        $x = $this->luminance($a);
        $y = $this->luminance($b);

        return (max($x, $y) + 0.05) / (min($x, $y) + 0.05);
    }

    public function test_every_palette_has_a_css_block_with_its_own_primary_in_both_modes(): void
    {
        foreach (['laut', 'pasir', 'anggur'] as $name) {
            $this->assertNotEmpty($this->declarations(':root[data-palette="'.$name.'"]'), "$name terang");
            $this->assertNotEmpty($this->declarations(':root[data-palette="'.$name.'"]:not([data-theme="light"])'), "$name gelap sistem");
            $this->assertNotEmpty($this->declarations(':root[data-palette="'.$name.'"][data-theme="dark"]'), "$name gelap pilihan");
        }
    }

    public function test_css_primary_matches_the_swatch_shown_in_profile(): void
    {
        foreach (ThemePalette::cases() as $palette) {
            $light = $this->effectiveTokens($palette, 'terang');

            $this->assertSame(strtolower($palette->swatch()), $light['--rf-primary'], $palette->label());
        }
    }

    public function test_text_on_accent_and_accent_on_surface_meet_4_5_to_1_in_every_palette_and_mode(): void
    {
        foreach (ThemePalette::cases() as $palette) {
            foreach (['terang', 'gelap-sistem', 'gelap-pilihan'] as $mode) {
                $label = "{$palette->label()} $mode";
                $t = $this->effectiveTokens($palette, $mode);

                $this->assertGreaterThanOrEqual(4.5, $this->contrast($t['--rf-on-primary'], $t['--rf-primary']), "$label: on-primary di atas primary");
                $this->assertGreaterThanOrEqual(4.5, $this->contrast($t['--rf-on-primary-container'], $t['--rf-primary-container']), "$label: on-primary-container di atas primary-container");
                $this->assertGreaterThanOrEqual(4.5, $this->contrast($t['--rf-on-surface'], $t['--rf-primary-fixed']), "$label: label chip terpilih di atas primary-fixed");
                $this->assertGreaterThanOrEqual(4.5, $this->contrast($t['--rf-primary'], $t['--rf-surface']), "$label: warna utama di atas latar");
            }
        }
    }

    public function test_palette_only_overrides_the_accent_family_and_leaves_status_and_room_colors_alone(): void
    {
        $base = $this->declarations(':root');
        $laut = $this->effectiveTokens(ThemePalette::Laut, 'terang');

        $this->assertSame($base['--rf-surface'], $laut['--rf-surface']);
        $this->assertSame($base['--rf-status-critical'], $laut['--rf-status-critical']);
        $this->assertSame($base['--rf-room-1'], $laut['--rf-room-1']);
        $this->assertNotSame($base['--rf-primary'], $laut['--rf-primary']);
    }

    public function test_sage_is_always_free_and_only_the_other_palettes_are_pro(): void
    {
        $this->assertFalse(ThemePalette::Sage->isPro());
        $this->assertSame([ThemePalette::Laut, ThemePalette::Pasir, ThemePalette::Anggur], array_values(array_filter(
            ThemePalette::cases(),
            fn (ThemePalette $palette) => $palette->isPro(),
        )));
    }

    public function test_pro_palette_falls_back_to_sage_without_pro_and_comes_back_with_pro(): void
    {
        $free = new Entitlements([]);
        $pro = new Entitlements([Plan::Pro]);

        $this->assertFalse($free->isEnabled(Feature::Themes));
        $this->assertSame(ThemePalette::Sage, ThemePalette::Laut->effective($free));
        $this->assertSame(ThemePalette::Laut, ThemePalette::Laut->effective($pro));
        $this->assertSame(ThemePalette::Sage, ThemePalette::Sage->effective($free));
        $this->assertSame(ThemePalette::Sage, ThemePalette::Sage->effective($pro));
    }
}
