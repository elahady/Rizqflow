<?php

namespace App\Livewire;

use App\Support\Feature;
use App\Support\Reports\ReportInsightSentences;
use App\Support\Reports\ReportInsights;
use App\Support\Reports\YearlyReportLoader;
use Illuminate\Contracts\View\View;
use Illuminate\Support\Carbon;
use Livewire\Component;

/**
 * Laporan tahunan (Pro, web-only): 12 baris bulan termasuk bulan kosong, total tahun ini vs tahun
 * lalu, pengeluaran per ruang, 10 kategori terbesar, dan insight kalimat. Port layar Tahunan di
 * Android (`YearlyReportLoader` dan `ReportInsights` di :domain). Semua angka datang dari
 * App\Support\Reports\YearlyReportLoader, yang memakai ulang App\Support\PeriodComparator.
 */
class LaporanTahunan extends Component
{
    public string $tahun = '';

    public function mount(): void
    {
        $this->tahun = Carbon::today()->format('Y');
    }

    public function render(): View
    {
        $user = auth()->user();
        $canUseReports = $user->entitlements()->isEnabled(Feature::ReportsAndInsights);

        $report = $canUseReports ? YearlyReportLoader::load($user, $this->year()) : null;

        return view('livewire.laporan-tahunan', [
            'canUseReports' => $canUseReports,
            'year' => $this->year(),
            'report' => $report,
            'insightSentences' => $report ? ReportInsightSentences::of(ReportInsights::of($report)) : [],
        ])->layout('components.layout', ['title' => 'Laporan tahunan — Rizqflow']);
    }

    /** Tahun dari input; tidak sah atau di luar 2000–2100 jatuh ke tahun ini (bukan galat). */
    private function year(): int
    {
        $year = preg_match('/^\d{4}$/', $this->tahun) ? (int) $this->tahun : (int) Carbon::today()->format('Y');

        return $year >= 2000 && $year <= 2100 ? $year : (int) Carbon::today()->format('Y');
    }
}
