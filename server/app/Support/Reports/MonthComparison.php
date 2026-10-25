<?php

namespace App\Support\Reports;

/**
 * Perbandingan satu angka antara periode ini ([current]) dan pembanding ([previous]), mis. tahun
 * ini vs tahun lalu. [percentBp] null bila pembanding nol (tidak ada dasar persentase yang bermakna).
 */
final class MonthComparison
{
    public function __construct(
        public readonly int $current,
        public readonly int $previous,
    ) {}

    public function delta(): int
    {
        return $this->current - $this->previous;
    }

    public function percentBp(): ?int
    {
        if ($this->previous <= 0) {
            return null;
        }

        return BasisPoints::of($this->delta(), $this->previous);
    }
}
