<?php

namespace App\Support\Reports;

/**
 * Laporan satu tahun kalender (port dari `YearlyReport` di :domain). Semua angka diturunkan dari
 * transaksi, tidak ada yang disimpan. [months] selalu 12 baris, Januari sampai Desember, termasuk
 * bulan kosong. [income] dan [expense] membandingkan total tahun ini dengan tahun lalu. [roomSpend]
 * dan [topCategories] hanya pengeluaran tahun ini, terbesar dulu.
 */
final class YearlyReport
{
    /** Kategori terbesar di laporan tahunan (laporan bulanan di Android memakai 5). */
    public const MAX_CATEGORIES = 10;

    /**
     * @param  list<MonthTotals>  $months
     * @param  list<RoomSpend>  $roomSpend
     * @param  list<CategorySpend>  $topCategories
     */
    public function __construct(
        public readonly int $year,
        public readonly array $months,
        public readonly MonthComparison $income,
        public readonly MonthComparison $expense,
        public readonly array $roomSpend,
        public readonly array $topCategories,
    ) {}

    /** Pemasukan dikurangi pengeluaran, dengan pembanding tahun lalu yang sama. */
    public function net(): MonthComparison
    {
        return new MonthComparison(
            $this->income->current - $this->expense->current,
            $this->income->previous - $this->expense->previous,
        );
    }

    public function hasData(): bool
    {
        return $this->income->current > 0 || $this->expense->current > 0;
    }

    /**
     * Bulan yang punya pengeluaran: dasar rata-rata dan tren (bulan kosong tidak boleh menurunkan
     * rata-rata).
     *
     * @return list<MonthTotals>
     */
    public function activeExpenseMonths(): array
    {
        return array_values(array_filter($this->months, fn (MonthTotals $month) => $month->expense > 0));
    }
}
