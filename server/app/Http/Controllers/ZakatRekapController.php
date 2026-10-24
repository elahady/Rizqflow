<?php

namespace App\Http\Controllers;

use App\Support\ZakatRekap;
use Barryvdh\DomPDF\Facade\Pdf;
use Illuminate\Http\Request;
use Symfony\Component\HttpFoundation\Response;

/**
 * Unduhan rekap zakat tahunan (PDF dan CSV) untuk keperluan SPT, lihat butir "Desain web baru" di
 * docs/konsep.md. Isinya diambil dari App\Support\ZakatRekap, sama dengan tabel di halaman Zakat,
 * dan hanya memuat transaksi zakat milik pengguna yang masuk. Tahun tanpa data tetap menghasilkan
 * berkas dengan tabel kosong, bukan galat.
 */
class ZakatRekapController extends Controller
{
    public function pdf(Request $request, int $year): Response
    {
        $user = $request->user();
        $transactions = ZakatRekap::transactions($user, $year);

        return Pdf::loadView('pdf.zakat-rekap', [
            'year' => $year,
            'userName' => $user->name,
            'transactions' => $transactions,
            'totalMinor' => ZakatRekap::totalMinor($transactions),
        ])->setPaper('a4')->download('rekap-zakat-'.$year.'.pdf');
    }

    public function csv(Request $request, int $year): Response
    {
        return response(ZakatRekap::csv($request->user(), $year), 200, [
            'Content-Type' => 'text/csv; charset=UTF-8',
            'Content-Disposition' => 'attachment; filename="rekap-zakat-'.$year.'.csv"',
        ]);
    }
}
