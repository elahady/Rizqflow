<?php

namespace App\Support;

use App\Models\Category;
use App\Models\Transaction;
use App\Models\User;
use Illuminate\Database\Eloquent\Builder;
use Illuminate\Database\Eloquent\Relations\HasMany;
use Illuminate\Support\Collection;

/**
 * Rekap zakat tahunan (untuk SPT): pengeluaran milik pengguna di pos sistem Zakat mal pada satu
 * tahun Masehi. Dipakai bersama halaman Zakat (tabel), ZakatRekapController (PDF dan CSV) supaya
 * angka dan isinya selalu sama. Semua query lewat $user->transactions(), jadi tidak pernah
 * memuat data pengguna lain.
 */
class ZakatRekap
{
    public const CSV_HEADER = ['Tanggal', 'Jenis zakat', 'Lembaga penerima', 'No. bukti setor', 'Nominal'];

    /** Transaksi zakat milik $user (semua tahun): pengeluaran di pos sistem Zakat mal. */
    public static function query(User $user): HasMany
    {
        return $user->transactions()
            ->expense()
            ->whereHas('pos', fn (Builder $pos) => $pos->zakat());
    }

    /**
     * Tahun yang punya pembayaran zakat plus $currentYear, terbaru dulu.
     *
     * @return list<int>
     */
    public static function years(User $user, int $currentYear): array
    {
        $years = self::query($user)->reorder()->get(['occurred_on'])->toBase()
            ->map(fn (Transaction $t) => (int) $t->occurred_on->format('Y'))
            ->push($currentYear)
            ->unique()
            ->sortDesc()
            ->values()
            ->all();

        return $years;
    }

    /**
     * Transaksi zakat tahun $year, urut tanggal naik.
     *
     * @return Collection<int, Transaction>
     */
    public static function transactions(User $user, int $year): Collection
    {
        return self::query($user)
            ->where('occurred_on', '>=', sprintf('%04d-01-01', $year))
            ->where('occurred_on', '<', sprintf('%04d-01-01', $year + 1))
            ->reorder()
            ->orderBy('occurred_on')
            ->orderBy('id')
            ->get();
    }

    public static function totalMinor(Collection $transactions): int
    {
        return (int) $transactions->sum('amount_minor');
    }

    /** CSV rekap: sama persis dengan isi tabel di halaman Zakat; nominal rupiah bulat tanpa pemisah. */
    public static function csv(User $user, int $year): string
    {
        $handle = fopen('php://temp', 'r+');
        fputcsv($handle, self::CSV_HEADER, ',', '"', '');

        foreach (self::transactions($user, $year) as $transaction) {
            fputcsv($handle, [
                $transaction->occurred_on->toDateString(),
                self::csvCell($transaction->category ?? Category::ZAKAT),
                self::csvCell($transaction->recipient_institution ?? ''),
                self::csvCell($transaction->receipt_number ?? ''),
                $transaction->amount_minor,
            ], ',', '"', '');
        }

        rewind($handle);
        $csv = (string) stream_get_contents($handle);
        fclose($handle);

        return $csv;
    }

    /** Cegah CSV injection: sel teks yang diawali = + - @ tab atau CR diberi awalan apostrof. */
    public static function csvCell(string $value): string
    {
        if ($value !== '' && in_array($value[0], ['=', '+', '-', '@', "\t", "\r"], true)) {
            return "'".$value;
        }

        return $value;
    }
}
