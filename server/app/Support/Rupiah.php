<?php

namespace App\Support;

/**
 * Format rupiah untuk tampilan. Nilai selalu bilangan bulat (lihat docs/konsep.md:
 * "Uang = bilangan bulat dalam satuan terkecil") — tidak ada pembagian desimal di sini.
 */
class Rupiah
{
    public static function format(int $minor): string
    {
        return 'Rp '.number_format($minor, 0, ',', '.');
    }

    /** Versi singkat untuk kartu kecil: Rp 1,2 jt / Rp 1,2 M. Lihat docs/ui-flow.md. */
    public static function short(int $minor): string
    {
        $abs = abs($minor);
        $sign = $minor < 0 ? '-' : '';

        if ($abs >= 1_000_000_000) {
            return $sign.'Rp '.rtrim(rtrim(number_format($abs / 1_000_000_000, 1, ',', ''), '0'), ',').' M';
        }

        if ($abs >= 1_000_000) {
            return $sign.'Rp '.rtrim(rtrim(number_format($abs / 1_000_000, 1, ',', ''), '0'), ',').' jt';
        }

        return self::format($minor);
    }
}
