<?php

namespace App\Support;

use App\Models\User;
use Illuminate\Support\Carbon;
use Illuminate\Support\Collection;

/**
 * Bandingkan dua periode berdampingan (Pro, web-only): logika bersama dipakai oleh
 * App\Livewire\BandingkanPeriode (tampilan di halaman) dan App\Http\Controllers\ReportPdfController
 * (ekspor PDF), supaya kedua jalur menghasilkan angka yang sama persis. Transfer dan transaksi
 * pinjaman tidak pernah ikut terhitung karena keduanya tidak pernah masuk tabel transactions di
 * web (lihat TransaksiDetail dan UtangPiutang) — tidak perlu penyaringan tambahan seperti di :domain.
 */
class PeriodComparator
{
    /** @return array{start: Carbon, end: Carbon} */
    public static function range(string $granularity, string $period): array
    {
        $start = $granularity === 'bulan' ? Carbon::createFromFormat('Y-m', $period)->startOfMonth() : Carbon::createFromFormat('Y', $period)->startOfYear();
        $end = $granularity === 'bulan' ? $start->copy()->endOfMonth() : $start->copy()->endOfYear();

        return ['start' => $start, 'end' => $end];
    }

    public static function periodLabel(string $granularity, string $period): string
    {
        $range = self::range($granularity, $period);

        return $granularity === 'bulan' ? $range['start']->translatedFormat('F Y') : $range['start']->format('Y');
    }

    /** @return array{income: int, expense: int, remainder: int, rooms: Collection, categories: Collection} */
    public static function summaryOf(User $user, string $granularity, string $period): array
    {
        $range = self::range($granularity, $period);
        // Batas atas lewat endOfDay(): occurred_on tersimpan dengan waktu "00:00:00", dan batas
        // atas berupa tanggal polos membandingkannya sebagai teks sehingga transaksi di hari
        // terakhir periode tersingkir diam-diam.
        $query = fn () => $user->transactions()->whereBetween('occurred_on', [$range['start']->toDateString(), $range['end']->copy()->endOfDay()]);

        $income = (int) $query()->where('type', 'pemasukan')->sum('amount_minor');

        $expenseRows = $query()->where('type', 'pengeluaran')->whereNotNull('room_id')->get();
        $expense = (int) $expenseRows->sum('amount_minor');

        $rooms = $expenseRows->groupBy('room_id')
            ->map(fn ($rows, $roomId) => ['room_id' => (int) $roomId, 'name' => $rows->first()->room?->name ?? '—', 'amount_minor' => (int) $rows->sum('amount_minor')])
            ->values();

        $categories = $expenseRows->whereNotNull('category_id')->groupBy('category_id')
            ->map(fn ($rows, $categoryId) => ['category_id' => (int) $categoryId, 'name' => $rows->first()->category ?? '—', 'amount_minor' => (int) $rows->sum('amount_minor')])
            ->values();

        return ['income' => $income, 'expense' => $expense, 'remainder' => $income - $expense, 'rooms' => $rooms, 'categories' => $categories];
    }

    public static function combineRooms(Collection $roomsA, Collection $roomsB): Collection
    {
        $byId = [];
        foreach ($roomsA as $row) {
            $byId[$row['room_id']] = ['name' => $row['name'], 'a' => $row['amount_minor'], 'b' => 0];
        }
        foreach ($roomsB as $row) {
            $byId[$row['room_id']] = $byId[$row['room_id']] ?? ['name' => $row['name'], 'a' => 0, 'b' => 0];
            $byId[$row['room_id']]['b'] = $row['amount_minor'];
        }

        return collect($byId)->sortByDesc(fn ($r) => max($r['a'], $r['b']))->values();
    }

    public static function combineCategories(Collection $categoriesA, Collection $categoriesB): Collection
    {
        $byId = [];
        foreach ($categoriesA as $row) {
            $byId[$row['category_id']] = ['name' => $row['name'], 'a' => $row['amount_minor'], 'b' => 0];
        }
        foreach ($categoriesB as $row) {
            $byId[$row['category_id']] = $byId[$row['category_id']] ?? ['name' => $row['name'], 'a' => 0, 'b' => 0];
            $byId[$row['category_id']]['b'] = $row['amount_minor'];
        }

        return collect($byId)->sortByDesc(fn ($r) => $r['a'] + $r['b'])->take(5)->values();
    }

    /**
     * @return array{a: array, b: array, rooms: Collection, categories: Collection, periodLabelA: string, periodLabelB: string}
     */
    public static function compare(User $user, string $granularity, string $periodA, string $periodB): array
    {
        $a = self::summaryOf($user, $granularity, $periodA);
        $b = self::summaryOf($user, $granularity, $periodB);

        return [
            'a' => $a,
            'b' => $b,
            'rooms' => self::combineRooms($a['rooms'], $b['rooms']),
            'categories' => self::combineCategories($a['categories'], $b['categories']),
            'periodLabelA' => self::periodLabel($granularity, $periodA),
            'periodLabelB' => self::periodLabel($granularity, $periodB),
        ];
    }
}
