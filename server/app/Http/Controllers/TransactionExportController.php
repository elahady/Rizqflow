<?php

namespace App\Http\Controllers;

use Illuminate\Http\Request;
use Symfony\Component\HttpFoundation\StreamedResponse;

/**
 * Ekspor CSV (bagian dari S20): selalu Gratis seperti ekspor data lainnya (lihat CLAUDE.md).
 * Backup dan restore terenkripsi di S20 belum dikerjakan di web — metode enkripsinya menunggu
 * keputusan Tahap 13 (sama seperti blocker sinkron data asli di Tahap 14), jadi sengaja dipisah
 * dari ekspor CSV ini yang tidak butuh keputusan itu.
 *
 * Tanpa parameter, semua transaksi pengguna diekspor. `?ids=1,2,3` (ekspor pilihan dari halaman
 * Transaksi) membatasinya ke ID tersebut, tetap hanya yang milik pengguna: ID milik orang lain atau
 * yang tak sah dibuang diam-diam.
 */
class TransactionExportController extends Controller
{
    /** Batas ID per permintaan; cukup untuk satu halaman terpilih dan menjaga URL tetap wajar. */
    private const MAX_IDS = 2000;

    public function csv(Request $request): StreamedResponse
    {
        // User::transactions() sudah punya orderByDesc('occurred_on') bawaan; reorder() menggantinya, bukan menumpuk.
        $query = $request->user()->transactions()->with(['room', 'account'])->reorder('occurred_on')->orderBy('id');

        if ($request->has('ids')) {
            $raw = $request->query('ids');
            $ids = is_string($raw) ? array_slice(array_values(array_unique(array_filter(array_map('intval', explode(',', $raw)), fn (int $id) => $id > 0))), 0, self::MAX_IDS) : [];
            $query->whereIn('id', $ids);
        }

        $transactions = $query->get();
        $filename = 'transaksi-rizqflow-'.now()->format('Y-m-d').'.csv';

        return response()->streamDownload(function () use ($transactions) {
            $handle = fopen('php://output', 'w');
            fputcsv($handle, ['Tanggal', 'Jenis', 'Ruang', 'Kategori', 'Akun', 'Nominal (Rp)', 'Catatan']);

            foreach ($transactions as $transaction) {
                fputcsv($handle, [
                    $transaction->occurred_on->toDateString(),
                    $transaction->type,
                    self::safe($transaction->room?->name ?? ''),
                    self::safe($transaction->category ?? ''),
                    self::safe($transaction->account?->name ?? ''),
                    $transaction->amount_minor,
                    self::safe($transaction->note ?? ''),
                ]);
            }

            fclose($handle);
        }, $filename, ['Content-Type' => 'text/csv; charset=UTF-8']);
    }

    /**
     * Mencegah CSV formula injection: sel teks yang diawali = + - @ tab atau CR dibaca Excel/Sheets
     * sebagai rumus. Diberi awalan ' supaya tampil sebagai teks biasa.
     */
    private static function safe(string $value): string
    {
        return $value !== '' && in_array($value[0], ['=', '+', '-', '@', "\t", "\r"], true) ? "'".$value : $value;
    }
}
