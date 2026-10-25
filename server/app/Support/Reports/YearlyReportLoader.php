<?php

namespace App\Support\Reports;

use App\Models\User;
use App\Support\PeriodComparator;

/**
 * Menyusun {@see YearlyReport} untuk satu tahun (port dari `YearlyReportLoader` di :domain).
 * Tidak ada agregasi baru di sini: setiap angka berasal dari {@see PeriodComparator::summaryOf()},
 * jadi laporan tahunan selalu sama dengan halaman Bandingkan periode dan unduhan PDF-nya. Tahun ini
 * dan tahun lalu memakai summaryOf tahun, dan 12 bulan memakai summaryOf bulan, supaya bulan kosong
 * tetap muncul sebagai baris bernilai nol.
 */
final class YearlyReportLoader
{
    public static function load(User $user, int $year): YearlyReport
    {
        $current = PeriodComparator::summaryOf($user, 'tahun', (string) $year);
        $previous = PeriodComparator::summaryOf($user, 'tahun', (string) ($year - 1));

        $months = [];
        for ($month = 1; $month <= 12; $month++) {
            $period = sprintf('%04d-%02d', $year, $month);
            $summary = PeriodComparator::summaryOf($user, 'bulan', $period);
            $months[] = new MonthTotals($period, $summary['income'], $summary['expense']);
        }

        $roomSpend = collect($current['rooms'])
            ->sortByDesc('amount_minor')
            ->map(fn (array $row) => new RoomSpend($row['room_id'], $row['name'], $row['amount_minor']))
            ->values()
            ->all();

        $topCategories = collect($current['categories'])
            ->sortByDesc('amount_minor')
            ->take(YearlyReport::MAX_CATEGORIES)
            ->map(fn (array $row) => new CategorySpend($row['category_id'], $row['name'], $row['room_id'], $row['room_name'], $row['amount_minor']))
            ->values()
            ->all();

        return new YearlyReport(
            year: $year,
            months: $months,
            income: new MonthComparison($current['income'], $previous['income']),
            expense: new MonthComparison($current['expense'], $previous['expense']),
            roomSpend: $roomSpend,
            topCategories: $topCategories,
        );
    }
}
