<?php

namespace App\Livewire;

use App\Support\Feature;
use Illuminate\Contracts\View\View;
use Illuminate\Support\Carbon;
use Illuminate\Support\Collection;
use Livewire\Component;

/**
 * Bandingkan dua periode berdampingan (Pro, web-only): bulan atau tahun ini vs lalu, atau periode
 * lain pilihan pengguna — lebih bebas dari kartu "vs bulan lalu" yang sudah ada di Laporan mobile
 * (S36, selalu bulan berjalan vs bulan sebelumnya). Transfer dan transaksi pinjaman tidak pernah
 * ikut terhitung karena keduanya tidak pernah masuk tabel transactions di web (lihat TransaksiDetail
 * dan UtangPiutang) — tidak perlu penyaringan tambahan seperti di :domain.
 */
class BandingkanPeriode extends Component
{
    public string $granularity = 'bulan';

    public string $periodA = '';

    public string $periodB = '';

    public function mount(): void
    {
        $this->resetToDefaultPeriods();
    }

    public function updatedGranularity(): void
    {
        $this->resetToDefaultPeriods();
    }

    private function resetToDefaultPeriods(): void
    {
        $today = Carbon::today();
        $this->periodA = $this->granularity === 'bulan' ? $today->format('Y-m') : $today->format('Y');
        $this->periodB = $this->granularity === 'bulan' ? $today->copy()->subMonth()->format('Y-m') : $today->copy()->subYear()->format('Y');
    }

    /** @return array{start: Carbon, end: Carbon} */
    private function range(string $period): array
    {
        $start = $this->granularity === 'bulan' ? Carbon::createFromFormat('Y-m', $period)->startOfMonth() : Carbon::createFromFormat('Y', $period)->startOfYear();
        $end = $this->granularity === 'bulan' ? $start->copy()->endOfMonth() : $start->copy()->endOfYear();

        return ['start' => $start, 'end' => $end];
    }

    /**
     * @return array{income: int, expense: int, remainder: int, rooms: Collection, categories: Collection}
     */
    private function summaryOf(string $period)
    {
        $user = auth()->user();
        $range = $this->range($period);
        $query = fn () => $user->transactions()->whereBetween('occurred_on', [$range['start']->toDateString(), $range['end']->toDateString()]);

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

    private function combineRooms($roomsA, $roomsB)
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

    private function combineCategories($categoriesA, $categoriesB)
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

    public function render(): View
    {
        $user = auth()->user();
        $canUseReports = $user->entitlements()->isEnabled(Feature::ReportsAndInsights);

        $a = $canUseReports ? $this->summaryOf($this->periodA) : null;
        $b = $canUseReports ? $this->summaryOf($this->periodB) : null;

        return view('livewire.bandingkan-periode', [
            'canUseReports' => $canUseReports,
            'a' => $a,
            'b' => $b,
            'rooms' => $a && $b ? $this->combineRooms($a['rooms'], $b['rooms']) : collect(),
            'categories' => $a && $b ? $this->combineCategories($a['categories'], $b['categories']) : collect(),
            'periodLabelA' => $this->periodLabel($this->periodA),
            'periodLabelB' => $this->periodLabel($this->periodB),
        ])->layout('components.layout', ['title' => 'Bandingkan periode — Rizqflow']);
    }

    private function periodLabel(string $period): string
    {
        $range = $this->range($period);

        return $this->granularity === 'bulan' ? $range['start']->translatedFormat('F Y') : $range['start']->format('Y');
    }
}
