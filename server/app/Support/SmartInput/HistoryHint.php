<?php

namespace App\Support\SmartInput;

/** Satu pengeluaran lampau (catatan beserta ruang dan pos-nya) untuk menebak pos dari kata yang pernah dipakai. */
final class HistoryHint
{
    public function __construct(
        public readonly string $note,
        public readonly int $roomId,
        public readonly int $categoryId,
    ) {}
}
