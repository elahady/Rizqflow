<?php

namespace App\Support\Reports;

/** Pengeluaran satu kategori (pos) dalam satu periode laporan, dengan ruangnya. */
final class CategorySpend
{
    public function __construct(
        public readonly int $categoryId,
        public readonly string $categoryName,
        public readonly int $roomId,
        public readonly string $roomName,
        public readonly int $amount,
    ) {}
}
