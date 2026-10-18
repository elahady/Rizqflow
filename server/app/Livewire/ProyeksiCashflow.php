<?php

namespace App\Livewire;

use App\Support\CashflowProjector;
use App\Support\Feature;
use Illuminate\Contracts\View\View;
use Livewire\Component;

/**
 * Proyeksi cashflow (Pro, web-only): lihat App\Support\CashflowProjector untuk logikanya.
 */
class ProyeksiCashflow extends Component
{
    public int $days = 30;

    public function render(): View
    {
        $user = auth()->user();
        $canUseReports = $user->entitlements()->isEnabled(Feature::ReportsAndInsights);

        $projection = $canUseReports ? CashflowProjector::project($user, $this->days) : null;

        if ($projection) {
            $this->dispatch('cashflow-chart-data', series: [
                'labels' => $projection['dailyLabels'],
                'balance' => $projection['dailyBalances'],
            ]);
        }

        return view('livewire.proyeksi-cashflow', [
            'canUseReports' => $canUseReports,
            'projection' => $projection,
        ])->layout('components.layout', ['title' => 'Proyeksi cashflow — Rizqflow']);
    }
}
