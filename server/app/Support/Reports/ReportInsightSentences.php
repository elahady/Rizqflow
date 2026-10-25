<?php

namespace App\Support\Reports;

use App\Support\Rupiah;

/**
 * Mengubah hasil {@see ReportInsights} jadi kalimat Indonesia (port dari `Insight.describe` dan
 * string `insight_*` di Android). Dipakai layar dan PDF supaya kalimatnya sama persis.
 */
final class ReportInsightSentences
{
    private const MONTH_ABBREV = ['Jan', 'Feb', 'Mar', 'Apr', 'Mei', 'Jun', 'Jul', 'Agu', 'Sep', 'Okt', 'Nov', 'Des'];

    /**
     * @param  array<string, array<string, mixed>>  $insights  keluaran {@see ReportInsights::of()}
     * @return list<string>
     */
    public static function of(array $insights): array
    {
        $sentences = [];
        foreach ($insights as $kind => $insight) {
            $sentences[] = match ($kind) {
                'tren' => self::trend($insight),
                'tabungan' => self::savings($insight['basisPoints']),
                'ruang' => 'Ruang '.$insight['roomName'].' memakai '.self::percent($insight['basisPoints']).' dari seluruh pengeluaran.',
                'kategori' => 'Kategori terbesar: '.$insight['categoryName'].' (ruang '.$insight['roomName'].'), '.Rupiah::format($insight['amount']).'.',
                'boros' => 'Bulan paling boros: '.self::monthLabel($insight['month']).' ('.Rupiah::format($insight['amount']).').',
                'hemat' => 'Bulan paling hemat: '.self::monthLabel($insight['month']).' ('.Rupiah::format($insight['amount']).').',
                'rata_rata' => 'Rata-rata pengeluaran '.Rupiah::format($insight['amount']).' per bulan, dari '.$insight['monthsCounted'].' bulan yang punya pengeluaran.',
                default => null,
            };
        }

        return array_values(array_filter($sentences, fn (?string $sentence) => $sentence !== null));
    }

    /** Persen dari basis poin, sama seperti `formatPercent` di Android: "10%", "2,5%", "2,05%". */
    public static function percent(int $basisPoints): string
    {
        $whole = intdiv($basisPoints, 100);
        $fraction = $basisPoints % 100;

        return match (true) {
            $fraction === 0 => $whole.'%',
            $fraction % 10 === 0 => $whole.','.intdiv($fraction, 10).'%',
            default => $whole.','.str_pad((string) $fraction, 2, '0', STR_PAD_LEFT).'%',
        };
    }

    /** @param  array{direction: string, changeBp: int}  $trend */
    private static function trend(array $trend): string
    {
        $magnitude = abs($trend['changeBp']);

        return match ($trend['direction']) {
            'up' => 'Pengeluaran 3 bulan terakhir naik '.self::percent($magnitude).' dibanding 3 bulan sebelumnya (rata-rata per bulan).',
            'down' => 'Pengeluaran 3 bulan terakhir turun '.self::percent($magnitude).' dibanding 3 bulan sebelumnya (rata-rata per bulan).',
            default => 'Pengeluaran 3 bulan terakhir relatif datar dibanding 3 bulan sebelumnya.',
        };
    }

    private static function savings(int $basisPoints): string
    {
        return match (true) {
            $basisPoints > 0 => 'Kamu menyisihkan '.self::percent($basisPoints).' dari pemasukan setahun.',
            $basisPoints < 0 => 'Pengeluaran melebihi pemasukan setahun; selisihnya '.self::percent(abs($basisPoints)).' dari total pemasukan.',
            default => 'Pemasukan dan pengeluaran setahun seimbang.',
        };
    }

    /** "Y-m" jadi "Mar 2026", memakai singkatan bulan Indonesia dari strings.xml. */
    private static function monthLabel(string $yearMonth): string
    {
        $month = (int) substr($yearMonth, 5, 2);

        return self::MONTH_ABBREV[$month - 1].' '.substr($yearMonth, 0, 4);
    }
}
