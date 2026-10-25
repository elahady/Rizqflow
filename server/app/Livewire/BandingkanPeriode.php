<?php

namespace App\Livewire;

use App\Models\User;
use App\Support\Feature;
use App\Support\PeriodComparator;
use App\Support\Reports\ReportInsightSentences;
use App\Support\Reports\ReportInsights;
use App\Support\Reports\YearlyReportLoader;
use Illuminate\Contracts\View\View;
use Illuminate\Support\Carbon;
use Livewire\Component;

/**
 * Bandingkan dua periode berdampingan (Pro, web-only): bulan atau tahun ini vs lalu, atau periode
 * lain pilihan pengguna — lebih bebas dari kartu "vs bulan lalu" yang sudah ada di Laporan mobile
 * (S36, selalu bulan berjalan vs bulan sebelumnya). Logika perbandingannya di App\Support\PeriodComparator,
 * dipakai bersama dengan ekspor PDF (ReportPdfController) supaya angkanya selalu sama persis.
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

    public function render(): View
    {
        $user = auth()->user();
        $canUseReports = $user->entitlements()->isEnabled(Feature::ReportsAndInsights);

        $result = $canUseReports ? PeriodComparator::compare($user, $this->granularity, $this->periodA, $this->periodB) : null;

        return view('livewire.bandingkan-periode', [
            'canUseReports' => $canUseReports,
            'a' => $result['a'] ?? null,
            'b' => $result['b'] ?? null,
            'rooms' => $result['rooms'] ?? collect(),
            'categories' => $result['categories'] ?? collect(),
            'periodLabelA' => $result['periodLabelA'] ?? '',
            'periodLabelB' => $result['periodLabelB'] ?? '',
            'insightSentences' => $canUseReports ? $this->insightSentences($user) : [],
        ])->layout('components.layout', ['title' => 'Bandingkan periode — Rizqflow']);
    }

    /**
     * Insight hanya untuk satuan tahun: kalimat dari laporan tahun A (periode kiri), sama seperti di
     * laporan tahunan. Tahun di luar 2000–2100 atau bukan empat digit dilewati, supaya masukan
     * ngawur tidak membuat halaman galat.
     *
     * @return list<string>
     */
    private function insightSentences(User $user): array
    {
        if ($this->granularity !== 'tahun' || ! preg_match('/^\d{4}$/', $this->periodA)) {
            return [];
        }

        $year = (int) $this->periodA;
        if ($year < 2000 || $year > 2100) {
            return [];
        }

        return ReportInsightSentences::of(ReportInsights::of(YearlyReportLoader::load($user, $year)));
    }
}
