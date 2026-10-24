<?php

namespace App\Support;

/**
 * Format rupiah untuk tampilan. Nilai selalu bilangan bulat (lihat docs/konsep.md:
 * "Uang = bilangan bulat dalam satuan terkecil") — tidak ada pembagian desimal di sini.
 */
class Rupiah
{
    /** Tanda minus tipografis (U+2212) untuk tampilan, sama seperti mockup web. */
    private const MINUS = "\u{2212}";

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

    /** Angka saja tanpa "Rp", mis. untuk isian nominal: 18000000 → "18.000.000" (negatif memakai "-"). */
    public static function plain(int $minor): string
    {
        return number_format($minor, 0, ',', '.');
    }

    /**
     * Nominal bertanda menurut jenis transaksi: pengeluaran "−Rp 187.500" (tanda minus U+2212),
     * pemasukan "+Rp 18.000.000", jenis lain (transfer) tanpa tanda. Tanda hanya dari jenis, jadi
     * nilainya selalu diambil mutlak.
     */
    public static function signed(int $minor, string $type): string
    {
        $text = self::format(abs($minor));

        return match ($type) {
            'pengeluaran' => self::MINUS.$text,
            'pemasukan' => '+'.$text,
            default => $text,
        };
    }

    /**
     * Ringkas untuk grafik dan label sempit, dibulatkan setengah ke atas dengan aritmetika bulat:
     * >= 1 miliar "Rp 1,2 M" dan >= 1 juta "Rp 1,09 jt" (maks. 2 desimal), >= 1.000 "Rp 187,5 rb"
     * (maks. 1 desimal), di bawahnya format biasa. Nol di belakang koma dibuang; negatif diawali "−".
     * Bila pembulatan mencapai 1.000 satuan, naik ke satuan berikutnya (999.950 → "Rp 1 jt").
     */
    public static function compact(int $minor): string
    {
        $sign = $minor < 0 ? self::MINUS : '';
        $abs = abs($minor);

        // [batas bawah, pembagi satuan, jumlah desimal, akhiran], dari satuan terbesar.
        $units = [
            [1_000_000_000, 1_000_000_000, 2, 'M'],
            [1_000_000, 1_000_000, 2, 'jt'],
            [1_000, 1_000, 1, 'rb'],
        ];

        foreach ($units as $index => [$threshold, $unit, $decimals, $suffix]) {
            if ($abs < $threshold) {
                continue;
            }

            $scale = 10 ** $decimals;
            $step = intdiv($unit, $scale);
            $scaled = intdiv($abs, $step) + ($abs % $step * 2 >= $step ? 1 : 0);
            $whole = intdiv($scaled, $scale);

            // 999.950 → 1000,0 rb: pakai satuan di atasnya (yang selalu lebih dulu di daftar).
            if ($whole >= 1_000 && $index > 0) {
                [, $unit, $decimals, $suffix] = $units[$index - 1];
                $scale = 10 ** $decimals;
                $step = intdiv($unit, $scale);
                $scaled = intdiv($abs, $step) + ($abs % $step * 2 >= $step ? 1 : 0);
                $whole = intdiv($scaled, $scale);
            }

            $fraction = rtrim(str_pad((string) ($scaled % $scale), $decimals, '0', STR_PAD_LEFT), '0');

            return $sign.'Rp '.number_format($whole, 0, ',', '.').($fraction === '' ? '' : ','.$fraction).' '.$suffix;
        }

        return $sign.self::format($abs);
    }
}
