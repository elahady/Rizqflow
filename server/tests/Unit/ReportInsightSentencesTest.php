<?php

namespace Tests\Unit;

use App\Support\Reports\ReportInsightSentences;
use PHPUnit\Framework\TestCase;

/** Kalimat insight harus sama dengan string `insight_*` di strings.xml Android, termasuk format persen. */
class ReportInsightSentencesTest extends TestCase
{
    public function test_persen_memakai_koma_dan_menyembunyikan_nol_di_belakang(): void
    {
        $this->assertSame('10%', ReportInsightSentences::percent(1_000));
        $this->assertSame('2,5%', ReportInsightSentences::percent(250));
        $this->assertSame('2,05%', ReportInsightSentences::percent(205));
        $this->assertSame('0,05%', ReportInsightSentences::percent(5));
    }

    public function test_kalimat_tren_naik_turun_dan_datar(): void
    {
        $sentences = ReportInsightSentences::of([
            'tren' => ['direction' => 'up', 'changeBp' => 10_000],
        ]);
        $this->assertSame(['Pengeluaran 3 bulan terakhir naik 100% dibanding 3 bulan sebelumnya (rata-rata per bulan).'], $sentences);

        $this->assertSame(
            ['Pengeluaran 3 bulan terakhir turun 50% dibanding 3 bulan sebelumnya (rata-rata per bulan).'],
            ReportInsightSentences::of(['tren' => ['direction' => 'down', 'changeBp' => -5_000]]),
        );
        $this->assertSame(
            ['Pengeluaran 3 bulan terakhir relatif datar dibanding 3 bulan sebelumnya.'],
            ReportInsightSentences::of(['tren' => ['direction' => 'flat', 'changeBp' => 300]]),
        );
    }

    public function test_kalimat_tabungan_positif_negatif_dan_seimbang(): void
    {
        $this->assertSame(['Kamu menyisihkan 60% dari pemasukan setahun.'], ReportInsightSentences::of(['tabungan' => ['basisPoints' => 6_000]]));
        $this->assertSame(
            ['Pengeluaran melebihi pemasukan setahun; selisihnya 50% dari total pemasukan.'],
            ReportInsightSentences::of(['tabungan' => ['basisPoints' => -5_000]]),
        );
        $this->assertSame(['Pemasukan dan pengeluaran setahun seimbang.'], ReportInsightSentences::of(['tabungan' => ['basisPoints' => 0]]));
    }

    public function test_bulan_memakai_singkatan_indonesia_dan_rupiah_tanpa_desimal(): void
    {
        $sentences = ReportInsightSentences::of([
            'boros' => ['month' => '2026-03', 'amount' => 900_000],
            'hemat' => ['month' => '2026-10', 'amount' => 100_000],
            'rata_rata' => ['amount' => 600_000, 'monthsCounted' => 2],
        ]);

        $this->assertSame(
            [
                'Bulan paling boros: Mar 2026 (Rp 900.000).',
                'Bulan paling hemat: Okt 2026 (Rp 100.000).',
                'Rata-rata pengeluaran Rp 600.000 per bulan, dari 2 bulan yang punya pengeluaran.',
            ],
            $sentences,
        );
    }
}
