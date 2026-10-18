<?php

namespace App\Livewire;

use App\Support\Feature;
use Illuminate\Contracts\View\View;
use Illuminate\Support\Carbon;
use Livewire\Component;

/**
 * Heatmap kalender pencatatan setahun (Pro, web-only, gaya kontribusi GitHub): setiap kotak
 * adalah satu hari, warnanya makin gelap makin banyak transaksi dicatat hari itu (jenis apa pun).
 * Murni CSS grid, tidak perlu Chart.js — datanya kecil (hitungan per hari, bukan nominal).
 */
class HeatmapPencatatan extends Component
{
    private const DAYS = 371; // 53 minggu penuh, dimundurkan ke hari Minggu terdekat

    /** @return array{weeks: list<list<array{date: string, count: int, level: int}>>, monthLabels: list<array{index: int, label: string}>, totalDays: int, streak: int} */
    private function buildGrid(): array
    {
        $user = auth()->user();
        $today = Carbon::today();
        $start = $today->copy()->subDays(self::DAYS - 1)->startOfWeek(Carbon::SUNDAY);
        // Digenapkan ke akhir minggu (Sabtu) supaya array_chunk selalu menghasilkan baris 7 hari;
        // hari sesudah $today ditandai inFuture dan dirender kosong, tidak dihitung sebagai pencatatan.
        $gridEnd = $today->copy()->endOfWeek(Carbon::SATURDAY);

        // Batas atas lewat endOfDay(): occurred_on tersimpan dengan waktu "00:00:00", dan batas
        // atas berupa tanggal polos membandingkannya sebagai teks sehingga transaksi hari ini
        // tersingkir diam-diam.
        $counts = $user->transactions()
            ->whereBetween('occurred_on', [$start->toDateString(), $today->copy()->endOfDay()])
            ->get()
            ->groupBy(fn ($t) => $t->occurred_on->toDateString())
            ->map(fn ($rows) => $rows->count());

        $maxCount = $counts->max() ?? 0;

        $days = [];
        $cursor = $start->copy();
        while ($cursor->lessThanOrEqualTo($gridEnd)) {
            $key = $cursor->toDateString();
            $count = $counts[$key] ?? 0;
            $days[] = [
                'date' => $key,
                'count' => $count,
                'level' => $this->levelFor($count, $maxCount),
                'inFuture' => $cursor->greaterThan($today),
            ];
            $cursor->addDay();
        }

        $weeks = array_chunk($days, 7);

        $monthLabels = [];
        $lastMonth = null;
        foreach ($weeks as $weekIndex => $week) {
            $month = Carbon::parse($week[0]['date'])->format('M');
            if ($month !== $lastMonth) {
                $monthLabels[] = ['index' => $weekIndex, 'label' => Carbon::parse($week[0]['date'])->translatedFormat('M')];
                $lastMonth = $month;
            }
        }

        return [
            'weeks' => $weeks,
            'monthLabels' => $monthLabels,
            'totalDays' => $counts->count(),
            'streak' => $this->currentStreak($counts, $today),
        ];
    }

    private function levelFor(int $count, int $max): int
    {
        if ($count <= 0 || $max <= 0) {
            return 0;
        }

        return (int) ceil($count / $max * 4);
    }

    /** Berapa hari berturut-turut sampai hari ini (termasuk hari ini) yang punya catatan. */
    private function currentStreak($counts, Carbon $today): int
    {
        $streak = 0;
        $day = $today->copy();
        while (($counts[$day->toDateString()] ?? 0) > 0) {
            $streak++;
            $day->subDay();
        }

        return $streak;
    }

    public function render(): View
    {
        $canUseReports = auth()->user()->entitlements()->isEnabled(Feature::ReportsAndInsights);
        $grid = $canUseReports ? $this->buildGrid() : null;

        return view('livewire.heatmap-pencatatan', [
            'canUseReports' => $canUseReports,
            'grid' => $grid,
        ])->layout('components.layout', ['title' => 'Heatmap pencatatan — Rizqflow']);
    }
}
