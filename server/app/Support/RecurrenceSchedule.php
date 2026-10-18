<?php

namespace App\Support;

use Illuminate\Support\Carbon;

/**
 * Menghitung kemunculan transaksi berulang dari tanggal awal, tidak pernah dari kemunculan
 * sebelumnya, supaya tanggal tidak bergeser: aturan bulanan yang mulai tanggal 31 jatuh pada
 * 28/29 Februari lalu kembali ke 31 Maret, bukan tertinggal di 28. Porting dari RecurrenceSchedule
 * di :domain (lihat domain/src/main/kotlin/.../recurring/Recurring.kt) — logikanya murni, tidak
 * disentuh setelah ini meskipun model data web untuk transaksi berulang lebih sederhana (tanpa transfer).
 */
class RecurrenceSchedule
{
    /** Kemunculan pertama yang jatuh pada $date atau sesudahnya. */
    public static function firstOnOrAfter(Carbon $start, string $frequency, Carbon $date): Carbon
    {
        if ($date->lessThanOrEqualTo($start)) {
            return $start->copy();
        }

        return self::next($start, $frequency, $date->copy()->subDay());
    }

    /** Kemunculan pertama yang jatuh sesudah $after. */
    public static function next(Carbon $start, string $frequency, Carbon $after): Carbon
    {
        if ($after->lessThan($start)) {
            return $start->copy();
        }

        return match ($frequency) {
            'harian' => $after->copy()->addDay(),
            'mingguan' => self::nextWeekly($start, $after),
            'bulanan' => self::nextMonthly($start, $after),
        };
    }

    private static function nextWeekly(Carbon $start, Carbon $after): Carbon
    {
        $weeks = intdiv($start->diffInDays($after), 7);

        return $start->copy()->addDays(($weeks + 1) * 7);
    }

    private static function nextMonthly(Carbon $start, Carbon $after): Carbon
    {
        $months = $start->copy()->startOfMonth()->diffInMonths($after->copy()->startOfMonth());
        $candidate = $start->copy()->addMonthsNoOverflow($months);

        return $candidate->greaterThan($after) ? $candidate : $start->copy()->addMonthsNoOverflow($months + 1);
    }
}
