<?php

namespace App\Support;

use Illuminate\Support\Carbon;

/**
 * Impor transaksi massal dari CSV mutasi bank/e-wallet (Pro, web-only). Format yang didukung
 * sengaja sederhana dan tetap — bukan deteksi otomatis format tiap bank (BCA, Mandiri, GoPay,...
 * semua beda kolom): tiga kolom, Tanggal/Nominal/Catatan, tanpa header atau dengan header (baris
 * pertama dilewati otomatis bila kolom tanggalnya tidak bisa diurai). Nominal bertanda: negatif
 * jadi pengeluaran, positif jadi pemasukan — konvensi umum mutasi rekening. Pecahan rupiah (koma/
 * titik desimal) tidak didukung karena uang di Rizqflow selalu bilangan bulat rupiah penuh; semua
 * karakter selain digit dan tanda minus dibuang, jadi "1.000.000" dan "1,000,000" sama-sama dibaca
 * sebagai Rp 1.000.000, bukan seribu rupiah koma sekian. Pasangan transfer (mis. mutasi keluar di
 * satu baris dan masuk di baris lain) tidak disatukan — di luar cakupan, beda dari F7 di ui-flow.md
 * yang soal impor format internal "Transaksi Harian" pemilik, bukan mutasi bank/e-wallet pihak ketiga.
 */
class CsvTransactionImporter
{
    private const DATE_FORMATS = ['Y-m-d', 'd/m/Y', 'd-m-Y', 'd/m/y', 'd-m-y'];

    /** @return array{rows: list<array{date: string, amount_minor: int, type: string, note: ?string}>, skipped: int} */
    public static function parse(string $csvContent): array
    {
        $lines = preg_split('/\r\n|\r|\n/', trim($csvContent));
        $rows = [];
        $skipped = 0;

        foreach ($lines as $index => $line) {
            if (trim($line) === '') {
                continue;
            }

            $columns = str_getcsv($line);

            if ($index === 0 && self::parseDate($columns[0] ?? '') === null) {
                continue; // baris header, dilewati
            }

            $date = self::parseDate($columns[0] ?? '');
            $amount = self::parseAmount($columns[1] ?? '');

            if ($date === null || $amount === null || $amount === 0) {
                $skipped++;

                continue;
            }

            $rows[] = [
                'date' => $date->toDateString(),
                'amount_minor' => abs($amount),
                'type' => $amount < 0 ? 'pengeluaran' : 'pemasukan',
                'note' => ($columns[2] ?? null) !== null && trim($columns[2]) !== '' ? mb_substr(trim($columns[2]), 0, 200) : null,
            ];
        }

        return ['rows' => $rows, 'skipped' => $skipped];
    }

    private static function parseDate(string $raw): ?Carbon
    {
        $trimmed = trim($raw);
        if ($trimmed === '') {
            return null;
        }

        foreach (self::DATE_FORMATS as $format) {
            try {
                $date = Carbon::createFromFormat($format, $trimmed);
                if ($date !== false) {
                    return $date->startOfDay();
                }
            } catch (\Exception) {
                continue;
            }
        }

        return null;
    }

    private static function parseAmount(string $raw): ?int
    {
        $trimmed = trim($raw);
        if ($trimmed === '') {
            return null;
        }

        $negative = str_starts_with($trimmed, '-') || (str_starts_with($trimmed, '(') && str_ends_with($trimmed, ')'));
        $digits = preg_replace('/[^0-9]/', '', $trimmed);

        if ($digits === '' || $digits === null) {
            return null;
        }

        $value = (int) $digits;

        return $negative ? -$value : $value;
    }
}
