<?php

namespace Tests\Unit;

use App\Support\Reports\BasisPoints;
use App\Support\Reports\CategorySpend;
use App\Support\Reports\MonthComparison;
use App\Support\Reports\MonthTotals;
use App\Support\Reports\ReportInsights;
use App\Support\Reports\RoomSpend;
use App\Support\Reports\YearlyReport;
use PHPUnit\Framework\TestCase;

/**
 * Port `ReportInsightsTest.kt` (:domain) persis: nilai dan kasus tepi yang sama. Laporan dirakit
 * tangan lewat [report()], seperti di Android: [expenses] pengeluaran per bulan (Januari dst.),
 * pemasukan tetap [incomePerMonth] di tiap bulan berpengeluaran, dan tahun pembanding nol.
 */
class ReportInsightsTest extends TestCase
{
    /**
     * @param  list<int>  $expenses
     * @param  list<RoomSpend>  $roomSpend
     * @param  list<CategorySpend>  $categories
     */
    private function report(
        array $expenses,
        int $incomePerMonth = 0,
        array $roomSpend = [],
        array $categories = [],
    ): YearlyReport {
        $months = [];
        for ($month = 1; $month <= 12; $month++) {
            $expense = $expenses[$month - 1] ?? 0;
            $months[] = new MonthTotals(sprintf('2026-%02d', $month), $expense > 0 ? $incomePerMonth : 0, $expense);
        }

        $income = array_sum(array_map(fn (MonthTotals $m) => $m->income, $months));
        $expense = array_sum(array_map(fn (MonthTotals $m) => $m->expense, $months));

        return new YearlyReport(2026, $months, new MonthComparison($income, 0), new MonthComparison($expense, 0), $roomSpend, $categories);
    }

    private function keluarga(): RoomSpend
    {
        return new RoomSpend(1, 'Keluarga', 750_000);
    }

    public function test_tahun_tanpa_data_tidak_menghasilkan_insight(): void
    {
        $this->assertSame([], ReportInsights::of($this->report([])));
    }

    public function test_satu_bulan_saja_tidak_menyimpulkan_tertinggi_terendah_atau_tren_tapi_rata_rata_tetap_ada(): void
    {
        $insights = ReportInsights::of($this->report([0, 0, 500_000]));

        $this->assertArrayNotHasKey('boros', $insights);
        $this->assertArrayNotHasKey('hemat', $insights);
        $this->assertArrayNotHasKey('tren', $insights);
        $this->assertSame(500_000, $insights['rata_rata']['amount']);
    }

    public function test_bulan_tertinggi_dan_terendah_hanya_dari_bulan_yang_punya_pengeluaran(): void
    {
        $insights = ReportInsights::of($this->report([300_000, 0, 900_000, 100_000]));

        // Februari kosong (Rp 0) tidak dihitung sebagai "terendah": hanya bulan berpengeluaran, jadi April.
        $this->assertSame('2026-03', $insights['boros']['month']);
        $this->assertSame(900_000, $insights['boros']['amount']);
        $this->assertSame('2026-04', $insights['hemat']['month']);
        $this->assertSame(100_000, $insights['hemat']['amount']);
    }

    public function test_rata_rata_dibagi_bulan_berpengeluaran_bukan_dua_belas(): void
    {
        $insights = ReportInsights::of($this->report([300_000, 0, 900_000]));

        $this->assertSame(600_000, $insights['rata_rata']['amount']); // 1.200.000 / 2 bulan aktif
        $this->assertSame(2, $insights['rata_rata']['monthsCounted']);
    }

    public function test_tren_naik_bila_rata_rata_tiga_bulan_terakhir_melebihi_ambang(): void
    {
        // Sebelumnya 100rb x3 = 100rb, terakhir 200rb x3 = 200rb: naik 100%.
        $trend = ReportInsights::of($this->report([100_000, 100_000, 100_000, 200_000, 200_000, 200_000]))['tren'];

        $this->assertSame('up', $trend['direction']);
        $this->assertSame(10_000, $trend['changeBp']);
    }

    public function test_tren_turun_dan_datar_mengikuti_ambang(): void
    {
        $down = ReportInsights::of($this->report([400_000, 400_000, 400_000, 200_000, 200_000, 200_000]))['tren'];
        $this->assertSame('down', $down['direction']);
        $this->assertSame(-5_000, $down['changeBp']);

        // 103rb vs 100rb = +3%, di bawah ambang 5%: datar.
        $flat = ReportInsights::of($this->report([100_000, 100_000, 100_000, 103_000, 103_000, 103_000]))['tren'];
        $this->assertSame('flat', $flat['direction']);
    }

    public function test_tren_butuh_cukup_bulan_pembanding(): void
    {
        // Empat bulan aktif: tiga terakhir + hanya satu sebelumnya, belum cukup.
        $this->assertArrayNotHasKey('tren', ReportInsights::of($this->report([100_000, 200_000, 300_000, 400_000])));
        // Lima bulan aktif: dua pembanding, cukup.
        $this->assertArrayHasKey('tren', ReportInsights::of($this->report([100_000, 100_000, 200_000, 200_000, 200_000])));
    }

    public function test_tren_memakai_bulan_aktif_terakhir_bulan_kosong_di_antaranya_dilewati(): void
    {
        $trend = ReportInsights::of($this->report([100_000, 100_000, 100_000, 0, 0, 200_000, 200_000, 200_000]))['tren'];

        $this->assertSame('up', $trend['direction']);
        $this->assertSame(10_000, $trend['changeBp']);
    }

    public function test_tingkat_tabungan_positif_dan_negatif_dalam_basis_poin(): void
    {
        // Pemasukan 1.000.000 x2 = 2.000.000, pengeluaran 500.000 + 300.000 = 800.000, sisa 1.200.000 = 60%.
        $saving = ReportInsights::of($this->report([500_000, 300_000], incomePerMonth: 1_000_000))['tabungan'];
        $this->assertSame(6_000, $saving['basisPoints']);

        // Pemasukan 400rb, pengeluaran 600rb: sisa -200rb = -50%.
        $overspent = ReportInsights::of($this->report([600_000], incomePerMonth: 400_000))['tabungan'];
        $this->assertSame(-5_000, $overspent['basisPoints']);
    }

    public function test_tanpa_pemasukan_tidak_ada_tingkat_tabungan(): void
    {
        $this->assertArrayNotHasKey('tabungan', ReportInsights::of($this->report([500_000], incomePerMonth: 0)));
    }

    public function test_porsi_ruang_terbesar_dan_kategori_terbesar_mengikuti_laporan(): void
    {
        $insights = ReportInsights::of($this->report(
            expenses: [1_000_000],
            roomSpend: [$this->keluarga(), new RoomSpend(2, 'Diri', 250_000)],
            categories: [new CategorySpend(10, 'Belanja bulanan', 1, 'Keluarga', 750_000)],
        ));

        $this->assertSame('Keluarga', $insights['ruang']['roomName']);
        $this->assertSame(7_500, $insights['ruang']['basisPoints']);
        $this->assertSame('Belanja bulanan', $insights['kategori']['categoryName']);
        $this->assertSame('Keluarga', $insights['kategori']['roomName']);
        $this->assertSame(750_000, $insights['kategori']['amount']);
    }

    public function test_seri_pengeluaran_tertinggi_dimenangkan_bulan_yang_lebih_dulu(): void
    {
        $insights = ReportInsights::of($this->report([300_000, 0, 300_000, 100_000, 100_000]));

        $this->assertSame('2026-01', $insights['boros']['month']);
        $this->assertSame('2026-04', $insights['hemat']['month']);
    }

    public function test_pembagian_basis_poin_membulatkan_ke_nol_untuk_nilai_negatif(): void
    {
        // -1 dari 3 = -33,33% = -3333 bp, dibulatkan ke nol seperti pembagian Long Kotlin.
        $this->assertSame(-3_333, BasisPoints::of(-1, 3));
        $this->assertSame(3_333, BasisPoints::of(1, 3));
    }
}
