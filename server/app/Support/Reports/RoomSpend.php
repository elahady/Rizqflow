<?php

namespace App\Support\Reports;

/** Pengeluaran satu ruang dalam satu periode laporan. */
final class RoomSpend
{
    public function __construct(
        public readonly int $roomId,
        public readonly string $roomName,
        public readonly int $amount,
    ) {}
}
