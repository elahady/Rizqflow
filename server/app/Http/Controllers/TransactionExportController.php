<?php

namespace App\Http\Controllers;

use Illuminate\Http\Request;
use Symfony\Component\HttpFoundation\StreamedResponse;

/**
 * Ekspor CSV (bagian dari S20): selalu Gratis seperti ekspor data lainnya (lihat CLAUDE.md).
 * Backup dan restore terenkripsi di S20 belum dikerjakan di web — metode enkripsinya menunggu
 * keputusan Tahap 13 (sama seperti blocker sinkron data asli di Tahap 14), jadi sengaja dipisah
 * dari ekspor CSV ini yang tidak butuh keputusan itu.
 */
class TransactionExportController extends Controller
{
    public function csv(Request $request): StreamedResponse
    {
        // User::transactions() sudah punya orderByDesc('occurred_on') bawaan; reorder() menggantinya, bukan menumpuk.
        $transactions = $request->user()->transactions()->with(['room', 'account'])->reorder('occurred_on')->get();
        $filename = 'transaksi-rizqflow-'.now()->format('Y-m-d').'.csv';

        return response()->streamDownload(function () use ($transactions) {
            $handle = fopen('php://output', 'w');
            fputcsv($handle, ['Tanggal', 'Jenis', 'Ruang', 'Kategori', 'Akun', 'Nominal (Rp)', 'Catatan']);

            foreach ($transactions as $transaction) {
                fputcsv($handle, [
                    $transaction->occurred_on->toDateString(),
                    $transaction->type,
                    $transaction->room?->name ?? '',
                    $transaction->category ?? '',
                    $transaction->account?->name ?? '',
                    $transaction->amount_minor,
                    $transaction->note ?? '',
                ]);
            }

            fclose($handle);
        }, $filename, ['Content-Type' => 'text/csv; charset=UTF-8']);
    }
}
