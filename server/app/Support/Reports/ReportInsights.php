<?php

namespace App\Support\Reports;

/**
 * Insight laporan tahunan (port dari `ReportInsights` di :domain, Tahap 7). Murni aritmetika atas
 * {@see YearlyReport}: tanpa model statistik, tanpa tebakan, sehingga setiap kalimat bisa ditelusuri
 * ke angka yang sama dengan yang tertera di laporan. Insight hanya muncul bila datanya cukup
 * bermakna; lebih baik diam daripada menyimpulkan dari satu bulan.
 *
 * Hasilnya array berkunci tetap, hanya berisi insight yang syaratnya terpenuhi, urut seperti di
 * Android. Bentuk nilainya:
 *  - tren:      ['direction' => 'up'|'down'|'flat', 'changeBp' => int]
 *  - tabungan:  ['basisPoints' => int] (positif menabung, negatif pengeluaran melebihi pemasukan)
 *  - ruang:     ['roomName' => string, 'basisPoints' => int]
 *  - kategori:  ['categoryName' => string, 'roomName' => string, 'amount' => int]
 *  - boros:     ['month' => string Y-m, 'amount' => int]
 *  - hemat:     ['month' => string Y-m, 'amount' => int]
 *  - rata_rata: ['amount' => int, 'monthsCounted' => int]
 */
final class ReportInsights
{
    /** Selisih rata-rata di bawah ini (dalam bp, 500 = 5%) dianggap datar, bukan naik atau turun. */
    public const FLAT_THRESHOLD_BP = 500;

    /** Bulan aktif terakhir yang dibandingkan, dan jumlah minimal bulan pembanding sebelumnya. */
    private const TREND_RECENT = 3;

    private const TREND_MIN_PREVIOUS = 2;

    /** @return array<string, array<string, mixed>> */
    public static function of(YearlyReport $report): array
    {
        if (! $report->hasData()) {
            return [];
        }

        $active = $report->activeExpenseMonths();
        $insights = [];

        if (($trend = self::trend($active)) !== null) {
            $insights['tren'] = $trend;
        }

        if (($savings = self::savingsRate($report)) !== null) {
            $insights['tabungan'] = $savings;
        }

        if (($room = self::topRoom($report)) !== null) {
            $insights['ruang'] = $room;
        }

        if ($report->topCategories !== []) {
            $top = $report->topCategories[0];
            $insights['kategori'] = ['categoryName' => $top->categoryName, 'roomName' => $top->roomName, 'amount' => $top->amount];
        }

        // Tertinggi dan terendah hanya bermakna bila ada pembanding: minimal dua bulan aktif.
        // Seri dimenangkan bulan yang lebih dulu, seperti maxBy/minBy di Kotlin.
        if (count($active) >= 2) {
            $highest = $active[0];
            $lowest = $active[0];
            foreach ($active as $month) {
                if ($month->expense > $highest->expense) {
                    $highest = $month;
                }
                if ($month->expense < $lowest->expense) {
                    $lowest = $month;
                }
            }
            $insights['boros'] = ['month' => $highest->month, 'amount' => $highest->expense];
            $insights['hemat'] = ['month' => $lowest->month, 'amount' => $lowest->expense];
        }

        if ($active !== []) {
            $insights['rata_rata'] = [
                'amount' => intdiv($report->expense->current, count($active)),
                'monthsCounted' => count($active),
            ];
        }

        return $insights;
    }

    /** @param  list<MonthTotals>  $active */
    private static function trend(array $active): ?array
    {
        if (count($active) < self::TREND_RECENT + self::TREND_MIN_PREVIOUS) {
            return null;
        }

        $recent = array_slice($active, -self::TREND_RECENT);
        $previous = array_slice(array_slice($active, 0, -self::TREND_RECENT), -self::TREND_RECENT);
        $recentAvg = self::averageExpense($recent);
        $previousAvg = self::averageExpense($previous);
        if ($previousAvg <= 0) {
            return null;
        }

        $changeBp = BasisPoints::of($recentAvg - $previousAvg, $previousAvg);
        $direction = match (true) {
            $changeBp > self::FLAT_THRESHOLD_BP => 'up',
            $changeBp < -self::FLAT_THRESHOLD_BP => 'down',
            default => 'flat',
        };

        return ['direction' => $direction, 'changeBp' => $changeBp];
    }

    /** @param  list<MonthTotals>  $months */
    private static function averageExpense(array $months): int
    {
        return intdiv(array_sum(array_map(fn (MonthTotals $month) => $month->expense, $months)), count($months));
    }

    private static function savingsRate(YearlyReport $report): ?array
    {
        $income = $report->income->current;
        if ($income <= 0) {
            return null;
        }

        return ['basisPoints' => BasisPoints::of($report->net()->current, $income)];
    }

    private static function topRoom(YearlyReport $report): ?array
    {
        $top = $report->roomSpend[0] ?? null;
        $total = $report->expense->current;
        if ($top === null || $total <= 0) {
            return null;
        }

        return ['roomName' => $top->roomName, 'basisPoints' => BasisPoints::of($top->amount, $total)];
    }
}
