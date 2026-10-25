<?php

namespace App\Support\Reports;

/** Pemasukan dan pengeluaran satu bulan. [month] berformat Y-m (mis. 2026-03). */
final class MonthTotals
{
    public function __construct(
        public readonly string $month,
        public readonly int $income,
        public readonly int $expense,
    ) {}

    /** Bulan ini ada pemasukan atau pengeluaran sama sekali. */
    public function hasData(): bool
    {
        return $this->income > 0 || $this->expense > 0;
    }
}
