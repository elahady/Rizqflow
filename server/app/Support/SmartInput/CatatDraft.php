<?php

namespace App\Support\SmartInput;

use Carbon\CarbonImmutable;
use Carbon\CarbonInterface;
use InvalidArgumentException;

/**
 * Isian form Catat yang belum disimpan. Murni dan tak berubah: setiap with*() mengembalikan isian
 * baru, dan semua pilihan divalidasi terhadap CatatContext di sini sehingga form tidak perlu
 * memeriksa apa pun sendiri. Porting CatatDraft di ledger/Catat.kt; papan angka (digits/pressKey)
 * diganti $amountMinor karena form web memakai kolom nominal biasa.
 */
final class CatatDraft
{
    public const MODES = [ParsedEntry::INCOME, ParsedEntry::EXPENSE, ParsedEntry::TRANSFER];

    /**
     * @param  string  $mode  ParsedEntry::INCOME | ParsedEntry::EXPENSE | ParsedEntry::TRANSFER
     * @param  ?string  $source  sumber pemasukan, salah satu SmartInputParser::INCOME_SOURCES
     */
    public function __construct(
        public readonly CarbonImmutable $date,
        public readonly string $mode = ParsedEntry::INCOME,
        public readonly int $amountMinor = 0,
        public readonly ?string $source = SmartInputParser::INCOME_SOURCES[0],
        public readonly ?int $accountId = null,
        public readonly ?int $toAccountId = null,
        public readonly ?int $roomId = null,
        public readonly ?int $categoryId = null,
        public readonly string $note = '',
    ) {
        if (! in_array($mode, self::MODES, true)) {
            throw new InvalidArgumentException("Jenis Catat tidak dikenal: {$mode}");
        }
    }

    /** Isian awal: akun terakhir dipakai, ruang dan pos dari pengeluaran terakhir, tanggal hari ini. */
    public static function start(CatatContext $context, CarbonInterface $today, string $mode = ParsedEntry::INCOME): self
    {
        $account = $context->hasAccount($context->lastAccountId) ? $context->lastAccountId : null;
        $room = $context->hasRoom($context->lastRoomId) ? $context->lastRoomId : null;
        $category = $room !== null && $context->hasCategory($room, $context->lastCategoryId) ? $context->lastCategoryId : null;

        return (new self(
            date: CarbonImmutable::instance($today)->startOfDay(),
            mode: $mode,
            accountId: $account,
            roomId: $room,
            categoryId: $category,
        ))->normalized($context);
    }

    public function withAmount(int $amountMinor): self
    {
        return $this->copy(['amountMinor' => $amountMinor]);
    }

    public function withNote(string $text): self
    {
        return $this->copy(['note' => KotlinText::take($text, SmartInputParser::NOTE_MAX)]);
    }

    public function withDate(CarbonInterface $value): self
    {
        return $this->copy(['date' => CarbonImmutable::instance($value)->startOfDay()]);
    }

    public function withSource(?string $value): self
    {
        return $this->copy(['source' => $value]);
    }

    public function withMode(string $mode, CatatContext $context): self
    {
        return $this->copy(['mode' => $mode])->normalized($context);
    }

    public function withAccount(int $id, CatatContext $context): self
    {
        return $this->copy(['accountId' => $id])->normalized($context);
    }

    public function withToAccount(int $id, CatatContext $context): self
    {
        return $this->copy(['toAccountId' => $id])->normalized($context);
    }

    /** Berganti ruang mengganti pos ke bawaan ruang itu. */
    public function withRoom(int $id, CatatContext $context): self
    {
        return $id === $this->roomId ? $this : $this->copy(['roomId' => $id, 'categoryId' => $context->defaultCategory($id)]);
    }

    public function withCategory(int $id): self
    {
        return $this->copy(['categoryId' => $id]);
    }

    /** Masalah pertama yang menghalangi simpan, atau null bila sudah bisa disimpan. */
    public function issue(CatatContext $context): ?CatatIssue
    {
        if ($this->amountMinor <= 0) {
            return CatatIssue::NoAmount;
        }
        if ($this->accountId === null) {
            return CatatIssue::NoAccount;
        }

        return match ($this->mode) {
            ParsedEntry::INCOME => null,
            ParsedEntry::EXPENSE => match (true) {
                $this->roomId === null => CatatIssue::NoRoom,
                $this->categoryId === null => CatatIssue::NoCategory,
                default => null,
            },
            ParsedEntry::TRANSFER => match (true) {
                ! $context->canTransfer() => CatatIssue::TransferNeedsTwoAccounts,
                $this->toAccountId === null || $this->toAccountId === $this->accountId => CatatIssue::SameAccount,
                default => null,
            },
        };
    }

    /**
     * Bentuk larik polos, mis. untuk properti publik Livewire.
     *
     * @return array{mode: string, amount_minor: int, source: ?string, account_id: ?int, to_account_id: ?int, room_id: ?int, category_id: ?int, date: string, note: string}
     */
    public function toArray(): array
    {
        return [
            'mode' => $this->mode,
            'amount_minor' => $this->amountMinor,
            'source' => $this->source,
            'account_id' => $this->accountId,
            'to_account_id' => $this->toAccountId,
            'room_id' => $this->roomId,
            'category_id' => $this->categoryId,
            'date' => $this->date->toDateString(),
            'note' => $this->note,
        ];
    }

    /**
     * Menjaga isian tetap sah: akun harus ada, ruang dan pos harus cocok, dan tujuan transfer
     * tidak boleh sama dengan asal. Dipanggil setiap ada perubahan yang bisa merusaknya.
     */
    private function normalized(CatatContext $context): self
    {
        $ids = array_map(fn (SmartAccount $a) => $a->id, $context->accounts);
        $from = $this->accountId !== null && in_array($this->accountId, $ids, true) ? $this->accountId : ($ids[0] ?? null);
        $to = $this->toAccountId !== null && in_array($this->toAccountId, $ids, true) && $this->toAccountId !== $from ? $this->toAccountId : null;
        if ($this->mode === ParsedEntry::TRANSFER && $to === null) {
            foreach ($ids as $id) {
                if ($id !== $from) {
                    $to = $id;
                    break;
                }
            }
        }
        $room = $context->hasRoom($this->roomId) ? $this->roomId : ($context->rooms[0]->id ?? null);
        $category = $room !== null && $context->hasCategory($room, $this->categoryId)
            ? $this->categoryId
            : ($room !== null ? $context->defaultCategory($room) : null);

        return $this->copy(['accountId' => $from, 'toAccountId' => $to, 'roomId' => $room, 'categoryId' => $category]);
    }

    /** @param array<string, mixed> $changes */
    private function copy(array $changes): self
    {
        return new self(...array_merge([
            'date' => $this->date,
            'mode' => $this->mode,
            'amountMinor' => $this->amountMinor,
            'source' => $this->source,
            'accountId' => $this->accountId,
            'toAccountId' => $this->toAccountId,
            'roomId' => $this->roomId,
            'categoryId' => $this->categoryId,
            'note' => $this->note,
        ], $changes));
    }
}
