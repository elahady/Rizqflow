<?php

namespace App\Support\SmartInput;

use App\Models\Category;
use App\Models\User;
use Carbon\CarbonImmutable;
use Carbon\CarbonInterface;

/**
 * Bahan penguraian: pilihan yang ada di Catat (akun dan ruang aktif berurutan, pos per ruang),
 * riwayat pengeluaran (terbaru dulu), dan hari ini. Porting SmartInputContext beserta
 * SmartInputService.contextFor di domain/.../smartinput/SmartInput.kt.
 */
final class SmartInputContext
{
    /** Jendela riwayat pengeluaran, sama dengan HISTORY_DAYS di SmartInputService. */
    public const HISTORY_DAYS = 90;

    /** Pengaman ukuran query per ketikan; Kotlin tidak membatasi jumlah di dalam jendela 90 hari. */
    public const HISTORY_LIMIT = 300;

    /** @var list<SmartAccount> */
    public readonly array $accounts;

    /** @var list<SmartRoom> */
    public readonly array $rooms;

    /** @var array<int, list<SmartCategory>> pos per id ruang */
    public readonly array $categories;

    /** @var list<HistoryHint> terbaru dulu */
    public readonly array $history;

    /** Hari ini (awal hari); dasar tanggal relatif seperti "kemarin". */
    public readonly CarbonImmutable $today;

    /**
     * @param  array<int, SmartAccount>  $accounts  berurutan seperti pilihan Catat
     * @param  array<int, SmartRoom>  $rooms  berurutan menurut prioritas
     * @param  array<int, array<int, SmartCategory>>  $categories  dikunci id ruang
     * @param  array<int, HistoryHint>  $history  terbaru dulu
     */
    public function __construct(array $accounts, array $rooms, array $categories, array $history, CarbonInterface $today)
    {
        $this->accounts = array_values($accounts);
        $this->rooms = array_values($rooms);
        $this->categories = array_map(array_values(...), $categories);
        $this->history = array_values($history);
        $this->today = CarbonImmutable::instance($today)->startOfDay();
    }

    /**
     * Konteks dari data milik $user, mengikuti CatatContextLoader dan SmartInputService di Android:
     * akun dan ruang yang belum diarsipkan (urut sort_order), pos tak terarsip tiap ruang aktif (pos
     * sistem ikut), dan riwayat pengeluaran bercatatan yang punya ruang dan pos dalam 90 hari sampai
     * $today. Seperti Kotlin, riwayat tidak disaring ruang/pos aktif: tebakan dari riwayat bisa
     * menunjuk ruang atau pos yang sudah diarsipkan, jadi periksa lewat room()/category() sebelum dipakai.
     */
    public static function forUser(User $user, CarbonInterface $today): self
    {
        $today = CarbonImmutable::instance($today)->startOfDay();

        $accounts = $user->accounts()->reorder()
            ->where('archived', false)
            ->orderBy('sort_order')->orderBy('id')
            ->get(['id', 'name', 'type'])
            ->map(fn ($a) => new SmartAccount((int) $a->id, (string) $a->name, (string) $a->type))
            ->all();

        $rooms = $user->rooms()->reorder()
            ->where('archived', false)
            ->orderBy('sort_order')->orderBy('id')
            ->get(['id', 'name', 'type'])
            ->map(fn ($r) => new SmartRoom((int) $r->id, (string) $r->name, (string) $r->type))
            ->all();

        // Setiap ruang aktif punya entri walau belum ada posnya, seperti `associate` di CatatContextLoader.
        $categories = array_fill_keys(array_map(fn (SmartRoom $r) => $r->id, $rooms), []);
        if ($categories !== []) {
            Category::query()
                ->whereIn('room_id', array_keys($categories))
                ->where('archived', false)
                ->orderBy('sort_order')->orderBy('id')
                ->get(['id', 'room_id', 'name', 'is_system', 'archived'])
                ->each(function ($c) use (&$categories) {
                    $categories[(int) $c->room_id][] = new SmartCategory((int) $c->id, (int) $c->room_id, (string) $c->name, (bool) $c->is_system, (bool) $c->archived);
                });
        }

        // Batas atas "< besok" (bukan "<= hari ini"): di SQLite occurred_on tersimpan "Y-m-d 00:00:00",
        // yang lebih besar dari "Y-m-d" bila dibandingkan sebagai teks. Urutan id menggantikan
        // created_at di Kotlin (sama-sama urutan pencatatan, dan id selalu unik).
        $history = $user->transactions()->reorder()
            ->where('type', 'pengeluaran')
            ->whereNotNull('room_id')
            ->whereNotNull('category_id')
            ->whereNotNull('note')
            ->where('note', '!=', '')
            ->where('occurred_on', '>=', $today->subDays(self::HISTORY_DAYS)->toDateString())
            ->where('occurred_on', '<', $today->addDay()->toDateString())
            ->orderByDesc('occurred_on')->orderByDesc('id')
            ->limit(self::HISTORY_LIMIT)
            ->get(['id', 'note', 'room_id', 'category_id'])
            ->reject(fn ($t) => KotlinText::isBlank((string) $t->note))
            ->map(fn ($t) => new HistoryHint((string) $t->note, (int) $t->room_id, (int) $t->category_id))
            ->values()
            ->all();

        return new self($accounts, $rooms, $categories, $history, $today);
    }

    /** @return list<SmartCategory> */
    public function categoriesOf(int $roomId): array
    {
        return $this->categories[$roomId] ?? [];
    }

    /** Akun aktif ber-id $id, atau null (mis. id dari luar konteks). */
    public function account(?int $id): ?SmartAccount
    {
        foreach ($this->accounts as $account) {
            if ($account->id === $id) {
                return $account;
            }
        }

        return null;
    }

    /** Ruang aktif ber-id $id, atau null (mis. tebakan riwayat yang ruangnya sudah diarsipkan). */
    public function room(?int $id): ?SmartRoom
    {
        foreach ($this->rooms as $room) {
            if ($room->id === $id) {
                return $room;
            }
        }

        return null;
    }

    /** Pos $categoryId bila ada di antara pos ruang $roomId dalam konteks ini, seperti chip pos di SmartInputSheet. */
    public function category(?int $roomId, ?int $categoryId): ?SmartCategory
    {
        if ($roomId === null) {
            return null;
        }
        foreach ($this->categoriesOf($roomId) as $category) {
            if ($category->id === $categoryId) {
                return $category;
            }
        }

        return null;
    }
}
