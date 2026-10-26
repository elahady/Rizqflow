<?php

namespace App\Http\Controllers;

use App\Support\Feature;
use App\Support\Period;
use App\Support\PeriodComparator;
use App\Support\Reports\AliranReport;
use App\Support\Reports\MonthComparison;
use App\Support\Reports\ReportInsightSentences;
use App\Support\Reports\ReportInsights;
use App\Support\Reports\YearlyReportLoader;
use Barryvdh\DomPDF\Facade\Pdf;
use Illuminate\Http\RedirectResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Carbon;
use Symfony\Component\HttpFoundation\Response;

/**
 * Ekspor laporan PDF (Pro, web-only), lewat barryvdh/laravel-dompdf (pure PHP, tidak perlu
 * Chromium/Node di server — cocok untuk VPS kecil, lihat docs/roadmap.md Tahap 12). Setiap jenis
 * memakai aggregasi yang sama dengan halaman webnya supaya angkanya selalu sama persis:
 * Bandingkan periode (PeriodComparator), dan laporan bulanan (bulan ini vs bulan lalu) serta tahunan
 * (App\Support\Reports\YearlyReportLoader + ReportInsights).
 */
class ReportPdfController extends Controller
{
    public function bandingkanPeriode(Request $request): Response|RedirectResponse
    {
        $user = $request->user();
        if (! $user->entitlements()->isEnabled(Feature::ReportsAndInsights)) {
            return redirect()->route('laporan.bandingkan');
        }

        $granularity = $request->query('granularity') === 'tahun' ? 'tahun' : 'bulan';
        $pattern = $granularity === 'tahun' ? '/^\d{4}$/' : '/^\d{4}-\d{2}$/';
        $today = Carbon::today();
        $defaultA = $granularity === 'tahun' ? $today->format('Y') : $today->format('Y-m');
        $defaultB = $granularity === 'tahun' ? $today->copy()->subYear()->format('Y') : $today->copy()->subMonth()->format('Y-m');

        $periodA = preg_match($pattern, (string) $request->query('periodA')) ? $request->query('periodA') : $defaultA;
        $periodB = preg_match($pattern, (string) $request->query('periodB')) ? $request->query('periodB') : $defaultB;

        $result = PeriodComparator::compare($user, $granularity, $periodA, $periodB);
        $filename = 'bandingkan-'.str_replace(['/', ' '], '-', mb_strtolower($result['periodLabelA'])).'-vs-'.str_replace(['/', ' '], '-', mb_strtolower($result['periodLabelB'])).'.pdf';

        return Pdf::loadView('pdf.bandingkan-periode', $result + ['userName' => $user->name])
            ->download($filename);
    }

    /**
     * Laporan bulanan: bulan terpilih vs bulan sebelumnya (seperti kartu S36 di Android). Ringkasan,
     * pengeluaran per ruang bulan itu, dan 5 kategori terbesar, sama dengan laporan bulanan Android.
     */
    public function bulanan(Request $request): Response|RedirectResponse
    {
        $user = $request->user();
        if (! $user->entitlements()->isEnabled(Feature::ReportsAndInsights)) {
            return redirect()->route('laporan.bandingkan');
        }

        $bulan = $this->bulanFromQuery($request);
        $bulanLalu = Carbon::createFromFormat('Y-m', $bulan)->subMonth()->format('Y-m');

        $result = PeriodComparator::compare($user, 'bulan', $bulan, $bulanLalu);
        $a = $result['a'];
        $b = $result['b'];

        return Pdf::loadView('pdf.laporan-bulanan', [
            'periodLabel' => $result['periodLabelA'],
            'previousLabel' => $result['periodLabelB'],
            'userName' => $user->name,
            'income' => new MonthComparison($a['income'], $b['income']),
            'expense' => new MonthComparison($a['expense'], $b['expense']),
            'net' => new MonthComparison($a['remainder'], $b['remainder']),
            'rooms' => collect($a['rooms'])->sortByDesc('amount_minor')->values(),
            'categories' => collect($a['categories'])->sortByDesc('amount_minor')->take(5)->values(),
        ])->download('laporan-bulanan-'.$bulan.'.pdf');
    }

    /** Laporan tahunan, isinya sama dengan halaman Analisa › Tahunan (angka dan insight). */
    public function tahunan(Request $request): Response|RedirectResponse
    {
        $user = $request->user();
        if (! $user->entitlements()->isEnabled(Feature::ReportsAndInsights)) {
            return redirect()->route('laporan.bandingkan');
        }

        $year = $this->tahunFromQuery($request);
        $report = YearlyReportLoader::load($user, $year);

        return Pdf::loadView('pdf.laporan-tahunan', [
            'report' => $report,
            'insightSentences' => ReportInsightSentences::of(ReportInsights::of($report)),
            'userName' => $user->name,
        ])->download('laporan-tahunan-'.$year.'.pdf');
    }

    /**
     * Aliran rezeki (Analisa › Aliran) dalam bentuk PDF. Angka, tabel, dan kalimat berasal dari
     * AliranReport::build yang sama dengan layar, dengan filter rentang, bulan, dan akun dari query.
     */
    public function aliran(Request $request): Response|RedirectResponse
    {
        $user = $request->user();
        if (! $user->entitlements()->isEnabled(Feature::ReportsAndInsights)) {
            return redirect()->route('laporan');
        }

        $end = Period::fromQuery((string) $request->query('bulan'));
        $rentang = (string) $request->query('rentang');
        $count = in_array($rentang, ['1', '3', '12'], true) ? (int) $rentang : 1;
        $akunIds = AliranReport::accountIds($user, (string) $request->query('akun'), (string) $request->query('ids'));

        return Pdf::loadView('pdf.aliran', [
            'flow' => AliranReport::build($user, $end, $count, $akunIds),
            'rangeLabel' => AliranReport::rangeLabel($end, $count),
            'akunLabel' => AliranReport::accountLabel($user, $akunIds),
            'userName' => $user->name,
        ])->download('aliran-'.Period::query($end).'-'.$count.'-bulan.pdf');
    }

    /** Bulan "Y-m" yang valid dari query; tidak sah (termasuk 2026-13) jatuh ke bulan ini. */
    private function bulanFromQuery(Request $request): string
    {
        $bulan = (string) $request->query('bulan');
        $parsed = preg_match('/^\d{4}-\d{2}$/', $bulan) ? Carbon::createFromFormat('Y-m', $bulan) : false;

        return $parsed !== false && $parsed->format('Y-m') === $bulan ? $bulan : Carbon::today()->format('Y-m');
    }

    /** Tahun dari query; tidak sah atau di luar 2000–2100 jatuh ke tahun ini. */
    private function tahunFromQuery(Request $request): int
    {
        $tahun = (string) $request->query('tahun');
        $year = preg_match('/^\d{4}$/', $tahun) ? (int) $tahun : (int) Carbon::today()->format('Y');

        return $year >= 2000 && $year <= 2100 ? $year : (int) Carbon::today()->format('Y');
    }
}
