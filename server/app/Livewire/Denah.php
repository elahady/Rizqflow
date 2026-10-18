<?php

namespace App\Livewire;

use Illuminate\Contracts\View\View;
use Livewire\Component;

/**
 * Beranda (S05 Denah) — ringkasan rezeki bulan ini, kartu ruang, dan "perlu perhatian".
 * Data masih dari seed/contoh (scaffold web tahap awal); sinkron data asli menyusul Tahap 9.
 */
class Denah extends Component
{
    public function render(): View
    {
        $user = auth()->user();
        $month = now()->startOfMonth();

        $summary = $user->monthlySummaries()->whereDate('month', $month)->first();
        $rooms = $user->rooms;

        $attention = [];
        foreach ($rooms as $room) {
            if ($room->status() === 'perlu_perhatian') {
                $attention[] = [
                    'text' => "Ruang {$room->name} hampir melewati jatah bulan ini",
                    'pill' => $room->usedPercent().'%',
                ];
            }
        }

        $zakat = $user->zakatProfiles()->where('status', 'haul_berjalan')->first();
        if ($zakat && $zakat->haul_day && $zakat->haul_total_days) {
            $daysLeft = max($zakat->haul_total_days - $zakat->haul_day, 0);
            $attention[] = [
                'text' => 'Haul zakat '.$daysLeft.' hari lagi',
                'pill' => $daysLeft.' hari',
                'href' => route('zakat'),
            ];
        }

        return view('livewire.denah', [
            'dateLabel' => now()->locale('id')->translatedFormat('l, j F Y'),
            'summary' => $summary,
            'rooms' => $rooms,
            'attention' => $attention,
        ])->layout('components.layout', ['title' => 'Denah — Rizqflow']);
    }
}
