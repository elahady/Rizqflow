<?php

namespace App\Support\SmartInput;

use App\Models\User;

/**
 * Bahan pilihan form Catat beserta pilihan terakhir, dasar isian bawaan CatatDraft. Porting
 * CatatContext di ledger/Catat.kt tanpa aturan alokasi (tidak dipakai isian).
 */
final class CatatContext
{
    /** @var list<SmartAccount> */
    public readonly array $accounts;

    /** @var list<SmartRoom> */
    public readonly array $rooms;

    /** @var array<int, list<SmartCategory>> pos per id ruang */
    public readonly array $categories;

    /**
     * @param  array<int, SmartAccount>  $accounts
     * @param  array<int, SmartRoom>  $rooms
     * @param  array<int, array<int, SmartCategory>>  $categories  dikunci id ruang
     * @param  ?int  $lastAccountId  akun transaksi terakhir yang dicatat
     * @param  ?int  $lastRoomId  ruang pengeluaran terakhir
     * @param  ?int  $lastCategoryId  pos pengeluaran terakhir
     */
    public function __construct(
        array $accounts,
        array $rooms,
        array $categories,
        public readonly ?int $lastAccountId = null,
        public readonly ?int $lastRoomId = null,
        public readonly ?int $lastCategoryId = null,
    ) {
        $this->accounts = array_values($accounts);
        $this->rooms = array_values($rooms);
        $this->categories = array_map(array_values(...), $categories);
    }

    /** Pilihan yang sama dengan $context ditambah pilihan terakhir. */
    public static function fromSmartInput(SmartInputContext $context, ?int $lastAccountId = null, ?int $lastRoomId = null, ?int $lastCategoryId = null): self
    {
        return new self($context->accounts, $context->rooms, $context->categories, $lastAccountId, $lastRoomId, $lastCategoryId);
    }

    /**
     * Pilihan dari $context ditambah pilihan terakhir milik $user, seperti CatatContextLoader: akun
     * dari transaksi terakhir yang dicatat (jenis apa pun), ruang dan pos dari pengeluaran terakhir.
     * "Terakhir dicatat" memakai id terbesar (padanan created_at di Kotlin); transaksi lama tanpa
     * akun dilewati karena di Android setiap transaksi pasti punya akun.
     */
    public static function forUser(User $user, SmartInputContext $context): self
    {
        $lastAccountId = $user->transactions()->reorder()->whereNotNull('account_id')->orderByDesc('id')->value('account_id');
        $lastExpense = $user->transactions()->reorder()->where('type', ParsedEntry::EXPENSE)->orderByDesc('id')->first(['id', 'room_id', 'category_id']);

        return self::fromSmartInput(
            $context,
            $lastAccountId !== null ? (int) $lastAccountId : null,
            $lastExpense?->room_id !== null ? (int) $lastExpense->room_id : null,
            $lastExpense?->category_id !== null ? (int) $lastExpense->category_id : null,
        );
    }

    /** @return list<SmartCategory> */
    public function categoriesOf(int $roomId): array
    {
        return $this->categories[$roomId] ?? [];
    }

    /** Pos bawaan sebuah ruang: yang terakhir dipakai bila ada di ruang itu, kalau tidak yang pertama bukan pos sistem. */
    public function defaultCategory(int $roomId): ?int
    {
        $own = $this->categoriesOf($roomId);
        foreach ($own as $category) {
            if ($category->id === $this->lastCategoryId) {
                return $category->id;
            }
        }
        foreach ($own as $category) {
            if (! $category->isSystem) {
                return $category->id;
            }
        }

        return $own[0]->id ?? null;
    }

    public function canTransfer(): bool
    {
        return count($this->accounts) >= 2;
    }

    public function hasAccount(?int $id): bool
    {
        foreach ($this->accounts as $account) {
            if ($account->id === $id) {
                return true;
            }
        }

        return false;
    }

    public function hasRoom(?int $id): bool
    {
        foreach ($this->rooms as $room) {
            if ($room->id === $id) {
                return true;
            }
        }

        return false;
    }

    public function hasCategory(int $roomId, ?int $categoryId): bool
    {
        foreach ($this->categoriesOf($roomId) as $category) {
            if ($category->id === $categoryId) {
                return true;
            }
        }

        return false;
    }
}
