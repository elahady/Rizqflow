<?php

namespace App\Livewire;

use App\Support\Feature;
use Illuminate\Contracts\View\View;
use Livewire\Component;

/**
 * Impor dan ekspor (menu Pengaturan): satu pintu ke semua jalur data keluar-masuk, yaitu ekspor CSV
 * transaksi, impor CSV mutasi bank, laporan PDF, dan rekap zakat (docs/design/web-mockup/project/
 * Navigasi.dc.html). Halaman ini hanya menautkan; pekerjaan tiap jalur ada di halamannya sendiri.
 * Ekspor data selalu gratis (CLAUDE.md, "Selalu gratis"); yang dikunci Pro hanya impor massal dan
 * laporan PDF, dan penguncian lewat Entitlements, bukan pemeriksaan paket di sini.
 */
class ImporEkspor extends Component
{
    public function render(): View
    {
        $isPro = auth()->user()->entitlements()->isEnabled(Feature::ReportsAndInsights);

        return view('livewire.impor-ekspor', [
            'isPro' => $isPro,
            'year' => now()->year,
        ])->layout('components.layout', ['title' => 'Impor dan ekspor — Rizqflow']);
    }
}
