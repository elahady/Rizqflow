<?php

namespace App\Support\Reports;

/**
 * Bagian per seluruh dalam basis poin (1 bp = 0,01%), dibulatkan ke nol seperti pembagian Long di
 * Kotlin (port dari `ReportInsights.basisPoints` dan `MonthComparison.percentBp` di :domain).
 * Hanya bilangan bulat: hasil bagi dan sisanya dihitung terpisah supaya tidak meluap saat dikali
 * 10.000. [whole] wajib positif; pemanggil memeriksa itu sebelum memanggil.
 */
final class BasisPoints
{
    public static function of(int $part, int $whole): int
    {
        $quotient = intdiv($part, $whole);
        $remainder = $part % $whole;

        return $quotient * 10_000 + intdiv($remainder * 10_000, $whole);
    }
}
