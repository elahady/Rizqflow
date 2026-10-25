<?php

namespace App\Livewire;

use App\Models\Category;
use App\Models\Room;
use App\Support\Period;
use App\Support\PeriodComparator;
use App\Support\Rupiah;
use Illuminate\Contracts\View\View;
use Livewire\Attributes\Url;
use Livewire\Component;

/**
 * Ringkasan bulanan (porting S36 Laporan di Android, Gratis): pemasukan, pengeluaran, sisa, perbandingan
 * dengan bulan lalu, pengeluaran per ruang, dan lima kategori terbesar. Tidak dikunci Entitlements:
 * ringkasan dasar tetap gratis untuk semua akun. Tren dan Bandingkan periode tetap Pro di Analisa.
 *
 * Angka diturunkan dari transaksi lewat PeriodComparator (jalur yang sama dengan Bandingkan), tidak ada
 * yang disimpan. Perbandingan dan persen mengikuti MonthComparison.kt di :domain dan comparisonText di
 * Android: persen basis poin terpotong (seperti BigInteger.divide), hanya ada bila bulan lalu positif.
 */
class Ringkasan extends Component
{
    /** Kategori terbesar yang ditampilkan (MonthlyReport.MAX_CATEGORIES di Android). */
    private const MAX_CATEGORIES = 5;

    #[Url]
    public ?string $bulan = null;

    public function render(): View
    {
        $user = auth()->user();
        $month = Period::fromQuery($this->bulan);

        $current = PeriodComparator::summaryOf($user, 'bulan', Period::query($month));
        $previous = PeriodComparator::summaryOf($user, 'bulan', Period::query(Period::previous($month)));

        $comparisons = [
            $this->comparison('Pemasukan', $current['income'], $previous['income']),
            $this->comparison('Pengeluaran', $current['expense'], $previous['expense']),
            $this->comparison('Sisa bulan ini', $current['remainder'], $previous['remainder']),
        ];

        $rooms = $user->rooms()->get()->keyBy('id');

        $roomRows = collect($current['rooms'])
            ->sortByDesc('amount_minor')
            ->values()
            ->map(fn (array $row) => [
                'room' => $rooms->get($row['room_id']),
                'name' => $row['name'],
                'amount' => $row['amount_minor'],
            ])
            ->filter(fn (array $row) => $row['room'] !== null)
            ->values();

        $categories = collect($current['categories'])->sortByDesc('amount_minor')->take(self::MAX_CATEGORIES)->values();
        $categoryModels = Category::with('room')->whereIn('id', $categories->pluck('category_id'))->get()->keyBy('id');
        $categoryRows = $categories->map(fn (array $row) => [
            'room' => $categoryModels->get($row['category_id'])?->room,
            'name' => $row['name'],
            'amount' => $row['amount_minor'],
        ])->filter(fn (array $row) => $row['room'] !== null)->values();

        return view('livewire.ringkasan', [
            'monthLabel' => Period::label($month),
            'hasData' => $current['income'] > 0 || $current['expense'] > 0,
            'comparisons' => $comparisons,
            'roomRows' => $this->withShare($roomRows),
            'categoryRows' => $this->withShare($categoryRows),
        ])->layout('components.layout', ['title' => 'Ringkasan — Rizqflow', 'period' => $month]);
    }

    /**
     * @return array{label: string, current: int, previous: int, text: string, direction: string}
     */
    private function comparison(string $label, int $current, int $previous): array
    {
        $delta = $current - $previous;
        $percentBp = $previous > 0 ? intdiv($delta * 10_000, $previous) : null;

        if ($previous === 0 && $percentBp === null) {
            $text = 'Belum ada data bulan lalu';
            $direction = 'netral';
        } elseif ($delta === 0) {
            $text = 'Sama dengan bulan lalu ('.Rupiah::format($previous).')';
            $direction = 'netral';
        } else {
            $up = $delta > 0;
            $amount = $percentBp !== null ? self::formatPercent(abs($percentBp)) : Rupiah::format(abs($delta));
            $text = ($up ? 'Naik ' : 'Turun ').$amount.' dari bulan lalu ('.Rupiah::format($previous).')';
            $direction = $up ? 'naik' : 'turun';
        }

        return ['label' => $label, 'current' => $current, 'previous' => $previous, 'text' => $text, 'direction' => $direction];
    }

    /**
     * Batang per baris: persen terhadap yang terbesar di daftar itu (sama seperti BarRow di Android).
     *
     * @param  \Illuminate\Support\Collection<int, array{room: ?Room, name: string, amount: int}>  $rows
     * @return \Illuminate\Support\Collection<int, array{room: ?Room, name: string, amount: int, percent: int}>
     */
    private function withShare($rows)
    {
        $max = (int) ($rows->first()['amount'] ?? 0);

        return $rows->map(fn (array $row) => $row + [
            'percent' => $max > 0 ? intdiv($row['amount'] * 100, $max) : 0,
        ]);
    }

    /** Persen basis poin (0 sampai 10.000) ke tampilan "12%" atau "12,5%", seperti formatPercent di Android. */
    private static function formatPercent(int $basisPoints): string
    {
        $whole = intdiv($basisPoints, 100);
        $fraction = $basisPoints % 100;

        return match (true) {
            $fraction === 0 => $whole.'%',
            $fraction % 10 === 0 => $whole.','.intdiv($fraction, 10).'%',
            default => $whole.','.str_pad((string) $fraction, 2, '0', STR_PAD_LEFT).'%',
        };
    }
}
