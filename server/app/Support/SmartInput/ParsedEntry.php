<?php

namespace App\Support\SmartInput;

use Carbon\CarbonImmutable;

/**
 * Hasil uraian SmartInputParser. $kind hanya pemasukan, pengeluaran, atau transfer (nilai kolom
 * transactions.type). Kolom yang tidak dikenali null (atau kosong untuk $note) dan diisi bawaan
 * Catat; $understood mencatat mana yang benar-benar dikenali. Porting ParsedEntry di
 * domain/.../smartinput/SmartInput.kt, ditambah $fromHistory.
 */
final class ParsedEntry
{
    public const INCOME = 'pemasukan';

    public const EXPENSE = 'pengeluaran';

    public const TRANSFER = 'transfer';

    /**
     * @param  string  $kind  self::INCOME | self::EXPENSE | self::TRANSFER
     * @param  int  $amountMinor  rupiah penuh, selalu lebih dari nol
     * @param  list<SmartField>  $understood  urut sesuai saat dikenali, tanpa duplikat
     * @param  bool  $fromHistory  ruang dan pos ($roomId, $categoryId) ditebak dari riwayat
     *                             pengeluaran ("dari catatan serupa"), bukan dari nama pos atau ruang
     *                             di kalimat. Tidak ada di Kotlin; understood tetap berisi Category.
     */
    public function __construct(
        public readonly string $kind,
        public readonly int $amountMinor,
        public readonly string $note,
        public readonly CarbonImmutable $date,
        public readonly ?int $accountId,
        public readonly ?int $toAccountId,
        public readonly ?int $roomId,
        public readonly ?int $categoryId,
        public readonly ?string $source,
        public readonly array $understood,
        public readonly bool $fromHistory = false,
    ) {}

    /** Apakah $field benar-benar dikenali dari kalimat (bukan bawaan). */
    public function has(SmartField $field): bool
    {
        return in_array($field, $this->understood, true);
    }

    /**
     * Bentuk larik polos, mis. untuk properti publik Livewire yang tidak bisa menyimpan objek ini.
     *
     * @return array{kind: string, amount_minor: int, note: string, date: string, account_id: ?int, to_account_id: ?int, room_id: ?int, category_id: ?int, source: ?string, understood: list<string>, from_history: bool}
     */
    public function toArray(): array
    {
        return [
            'kind' => $this->kind,
            'amount_minor' => $this->amountMinor,
            'note' => $this->note,
            'date' => $this->date->toDateString(),
            'account_id' => $this->accountId,
            'to_account_id' => $this->toAccountId,
            'room_id' => $this->roomId,
            'category_id' => $this->categoryId,
            'source' => $this->source,
            'understood' => array_map(fn (SmartField $f) => $f->value, $this->understood),
            'from_history' => $this->fromHistory,
        ];
    }
}
