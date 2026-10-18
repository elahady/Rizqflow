<?php

namespace App\Livewire;

use App\Support\Feature;
use Illuminate\Contracts\View\View;
use Livewire\Component;

/**
 * Hub laporan dan analisa web-only (Pro, sejalan dengan "laporan dan insight bulanan/tahunan, PDF"
 * di monetisasi.md). Daftar kartu bertambah mengikuti task Tahap 14 "Fitur analisa web-only" di
 * roadmap.md; tiap kartu menuju halamannya sendiri yang menolak tampil tanpa Pro.
 */
class Laporan extends Component
{
    public function render(): View
    {
        return view('livewire.laporan', [
            'canUseReports' => auth()->user()->entitlements()->isEnabled(Feature::ReportsAndInsights),
        ])->layout('components.layout', ['title' => 'Laporan — Rizqflow']);
    }
}
