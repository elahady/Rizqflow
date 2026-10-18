<?php

namespace App\Livewire;

use App\Support\Feature;
use Illuminate\Contracts\View\View;
use Livewire\Component;

/**
 * Kekayaan bersih sepanjang waktu (Pro, web-only). Riwayatnya dibangun oleh
 * App\Support\NetWorthSnapshotter sejak fitur ini dipasang — tidak direkonstruksi mundur, karena
 * web tidak menyimpan riwayat saldo akun atau harta zakat (lihat migrasi net_worth_snapshots).
 */
class KekayaanBersih extends Component
{
    public function render(): View
    {
        $user = auth()->user();
        $canUseReports = $user->entitlements()->isEnabled(Feature::ReportsAndInsights);

        $snapshots = $canUseReports ? $user->netWorthSnapshots()->orderBy('snapshot_date')->get() : collect();

        if ($canUseReports && $snapshots->isNotEmpty()) {
            $this->dispatch('net-worth-chart-data', series: [
                'labels' => $snapshots->map(fn ($s) => $s->snapshot_date->translatedFormat('j M Y'))->all(),
                'accounts' => $snapshots->pluck('accounts_minor')->all(),
                'zakatWealth' => $snapshots->pluck('zakat_wealth_minor')->all(),
                'total' => $snapshots->pluck('total_minor')->all(),
            ]);
        }

        return view('livewire.kekayaan-bersih', [
            'canUseReports' => $canUseReports,
            'hasHistory' => $snapshots->count() >= 2,
            'latest' => $snapshots->last(),
        ])->layout('components.layout', ['title' => 'Kekayaan bersih — Rizqflow']);
    }
}
