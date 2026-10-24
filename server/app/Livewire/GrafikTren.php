<?php

namespace App\Livewire;

use App\Models\Room;
use App\Support\Feature;
use Illuminate\Contracts\View\View;
use Illuminate\Support\Carbon;
use Livewire\Component;

/**
 * Grafik tren (Pro): pemasukan dan pengeluaran multi-bulan/tahun, per ruang dan kategori. Pemasukan
 * selalu total seluruh akun (web belum punya alokasi pemasukan per ruang, lihat TransaksiDetail);
 * filter ruang/kategori hanya menyaring sisi pengeluaran. Dirender dengan Chart.js (CDN) — datanya
 * dikirim lewat browser event `trend-chart-data` (lihat grafik-tren.blade.php) supaya canvas aman
 * dari morph Livewire saat filter berubah.
 */
class GrafikTren extends Component
{
    public int $months = 12;

    public ?int $roomId = null;

    public ?int $categoryId = null;

    public function updatedRoomId(): void
    {
        $this->categoryId = null;
    }

    /** @return array{labels: list<string>, income: list<int>, expense: list<int>} */
    private function series(): array
    {
        $user = auth()->user();
        $today = Carbon::today()->startOfMonth();
        $start = $today->copy()->subMonths($this->months - 1);

        // Batas atas lewat endOfDay(): occurred_on tersimpan dengan waktu "00:00:00", dan
        // whereBetween dengan string tanggal polos sebagai batas atas membandingkannya sebagai teks
        // ("...00:00:00" > tanggal polosnya), jadi transaksi di hari terakhir rentang tersingkir
        // diam-diam tanpa endOfDay() di sini.
        $rangeEnd = $today->copy()->endOfMonth()->endOfDay();

        $incomeByMonth = $user->transactions()
            ->where('type', 'pemasukan')
            ->whereBetween('occurred_on', [$start->toDateString(), $rangeEnd])
            ->get()
            ->groupBy(fn ($t) => $t->occurred_on->format('Y-m'))
            ->map(fn ($rows) => (int) $rows->sum('amount_minor'));

        $expenseQuery = $user->transactions()
            ->where('type', 'pengeluaran')
            ->whereBetween('occurred_on', [$start->toDateString(), $rangeEnd]);

        if ($this->roomId) {
            $expenseQuery->where('room_id', $this->roomId);
        }
        if ($this->categoryId) {
            $expenseQuery->where('category_id', $this->categoryId);
        }

        $expenseByMonth = $expenseQuery->get()
            ->groupBy(fn ($t) => $t->occurred_on->format('Y-m'))
            ->map(fn ($rows) => (int) $rows->sum('amount_minor'));

        $labels = [];
        $income = [];
        $expense = [];
        for ($i = 0; $i < $this->months; $i++) {
            $month = $start->copy()->addMonths($i);
            $key = $month->format('Y-m');
            $labels[] = $month->translatedFormat('M Y');
            $income[] = $incomeByMonth[$key] ?? 0;
            $expense[] = $expenseByMonth[$key] ?? 0;
        }

        return ['labels' => $labels, 'income' => $income, 'expense' => $expense];
    }

    public function render(): View
    {
        $user = auth()->user();
        $canUseReports = $user->entitlements()->isEnabled(Feature::ReportsAndInsights);

        $rooms = $user->rooms()->active()->orderBy('sort_order')->get();
        $categories = $this->roomId ? (Room::find($this->roomId)?->categories()->where('archived', false)->orderBy('sort_order')->get() ?? collect()) : collect();

        // Satu hitungan dipakai dua kali: dikirim ke grafik, dan jadi tabel kembaran di bawahnya.
        $series = $canUseReports ? $this->series() : null;
        if ($series) {
            $this->dispatch('trend-chart-data', series: $series);
        }

        return view('livewire.grafik-tren', [
            'canUseReports' => $canUseReports,
            'rooms' => $rooms,
            'categories' => $categories,
            'series' => $series,
        ])->layout('components.layout', ['title' => 'Grafik tren — Rizqflow']);
    }
}
