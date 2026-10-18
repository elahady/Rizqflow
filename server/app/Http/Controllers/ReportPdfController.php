<?php

namespace App\Http\Controllers;

use App\Support\Feature;
use App\Support\PeriodComparator;
use Barryvdh\DomPDF\Facade\Pdf;
use Illuminate\Http\RedirectResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Carbon;
use Symfony\Component\HttpFoundation\Response;

/**
 * Ekspor laporan PDF (Pro, web-only), lewat barryvdh/laravel-dompdf (pure PHP, tidak perlu
 * Chromium/Node di server — cocok untuk VPS kecil, lihat docs/roadmap.md Tahap 12). Memakai
 * App\Support\PeriodComparator yang sama dengan halaman Bandingkan periode, supaya angkanya
 * selalu sama persis dengan yang dilihat pengguna sebelum mengunduh.
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
}
