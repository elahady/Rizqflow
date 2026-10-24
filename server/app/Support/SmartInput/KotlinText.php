<?php

namespace App\Support\SmartInput;

/**
 * Semantik string Kotlin/JVM yang dipakai SmartInput.kt, supaya port PHP berperilaku identik
 * walau masukannya bukan ASCII: String di JVM berupa UTF-16 (panjang dihitung per unit UTF-16),
 * Char.isWhitespace() mencakup spasi Unicode, dan Char.isDigit() menerima semua angka desimal
 * Unicode ("٣" sama dengan "3"). Teks di PHP selalu UTF-8.
 *
 * @internal Hanya untuk kelas-kelas di App\Support\SmartInput.
 */
final class KotlinText
{
    /** Char.isWhitespace() di JVM: kategori Zs/Zl/Zp (termasuk spasi tak-putus) ditambah \t..\r dan \x1C..\x1F. */
    private const WHITESPACE = '[\p{Zs}\p{Zl}\p{Zp}\t\n\x{0B}\f\r\x{1C}-\x{1F}]';

    /** Karakter di luar BMP: satu karakter tetapi dua unit UTF-16 (surrogate pair) di Kotlin. */
    private const SUPPLEMENTARY = '/[\x{10000}-\x{10FFFF}]/u';

    /** String.trim() Kotlin. */
    public static function trim(string $text): string
    {
        return preg_replace('/^'.self::WHITESPACE.'+|'.self::WHITESPACE.'+$/uD', '', $text) ?? $text;
    }

    /** CharSequence.isBlank() Kotlin: kosong atau seluruhnya spasi. */
    public static function isBlank(string $text): bool
    {
        return preg_match('/^'.self::WHITESPACE.'*$/uD', $text) === 1;
    }

    /** String.length Kotlin: jumlah unit UTF-16, bukan jumlah karakter atau byte. */
    public static function length(string $text): int
    {
        return mb_strlen($text, 'UTF-8') + (int) preg_match_all(self::SUPPLEMENTARY, $text);
    }

    /**
     * String.take(n) Kotlin: paling banyak $n unit UTF-16 dari depan. Kotlin bisa memotong di tengah
     * surrogate pair; UTF-8 tidak bisa menyimpan separuh karakter, jadi karakter itu dibuang utuh.
     */
    public static function take(string $text, int $n): string
    {
        $units = 0;
        $out = '';
        foreach (mb_str_split($text, 1, 'UTF-8') as $char) {
            $units += strlen($char) === 4 ? 2 : 1;
            if ($units > $n) {
                break;
            }
            $out .= $char;
        }

        return $out;
    }

    /**
     * Nilai teks 1-2 angka bila semua karakternya Char.isDigit() (angka desimal Unicode di BMP),
     * seperti `all(Char::isDigit) && length in 1..2` lalu toInt() di Kotlin; selain itu null.
     */
    public static function smallNumber(string $text): ?int
    {
        if (preg_match('/^[0-9]{1,2}$/D', $text) === 1) {
            return (int) $text;
        }
        if (preg_match('/^\p{Nd}{1,2}$/uD', $text) !== 1 || preg_match(self::SUPPLEMENTARY, $text) === 1) {
            return null;
        }

        $value = 0;
        foreach (mb_str_split($text, 1, 'UTF-8') as $char) {
            $value = $value * 10 + self::digitValue(mb_ord($char, 'UTF-8'));
        }

        return $value;
    }

    /**
     * Nilai satu angka desimal Unicode. Unicode menjamin angka desimal selalu berupa deret 0..9
     * yang bersambung, jadi nilainya adalah jaraknya dari awal deret (modulo 10 untuk deret yang
     * bersambung lebih dari sepuluh).
     */
    private static function digitValue(int $codePoint): int
    {
        $start = $codePoint;
        while ($start > 0 && preg_match('/^\p{Nd}$/u', (string) mb_chr($start - 1, 'UTF-8')) === 1) {
            $start--;
        }

        return ($codePoint - $start) % 10;
    }
}
