<?php

namespace Tests\Feature;

use App\Livewire\BandingkanPeriode;
use App\Models\Account;
use App\Models\Category;
use App\Models\Room;
use App\Models\Transaction;
use App\Models\User;
use App\Support\PeriodComparator;
use App\Support\Reports\MonthComparison;
use App\Support\Reports\ReportInsightSentences;
use App\Support\Reports\ReportInsights;
use App\Support\Reports\YearlyReportLoader;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Carbon;
use Livewire\Livewire;
use Tests\TestCase;

/** PDF laporan bulanan dan tahunan (Pro). Isinya diambil dari aggregasi yang sama dengan halaman web. */
class LaporanPdfTest extends TestCase
{
    use RefreshDatabase;

    private User $user;

    private Room $room;

    private Category $category;

    private Account $account;

    protected function setUp(): void
    {
        parent::setUp();

        $this->user = User::factory()->create(['plans' => ['pro']]);
        $this->room = Room::create(['user_id' => $this->user->id, 'name' => 'Keluarga', 'type' => 'mencukupi', 'slot' => 1, 'sort_order' => 1]);
        $this->category = Category::create(['room_id' => $this->room->id, 'name' => 'Belanja bulanan', 'sort_order' => 1]);
        $this->account = Account::create(['user_id' => $this->user->id, 'name' => 'Bank Jago', 'type' => 'bank', 'sort_order' => 1]);
    }

    private function tx(string $type, int $amount, string $date, bool $withRoom = true): Transaction
    {
        return Transaction::create([
            'user_id' => $this->user->id, 'account_id' => $this->account->id,
            'room_id' => $withRoom ? $this->room->id : null, 'category_id' => $withRoom ? $this->category->id : null,
            'category' => $withRoom ? $this->category->name : null,
            'type' => $type, 'amount_minor' => $amount, 'occurred_on' => Carbon::parse($date),
        ]);
    }

    public function test_free_user_is_redirected_away_from_both_pdfs(): void
    {
        $this->user->update(['plans' => []]);

        $this->actingAs($this->user)->get(route('laporan.bulanan.pdf', ['bulan' => '2026-10']))->assertRedirect(route('laporan.bandingkan'));
        $this->actingAs($this->user)->get(route('laporan.tahunan.pdf', ['tahun' => '2026']))->assertRedirect(route('laporan.bandingkan'));
    }

    public function test_pro_user_downloads_the_monthly_report_as_a_pdf(): void
    {
        $this->tx('pemasukan', 2_000_000, '2026-10-02');
        $this->tx('pengeluaran', 500_000, '2026-10-05');

        $response = $this->actingAs($this->user)->get(route('laporan.bulanan.pdf', ['bulan' => '2026-10']));

        $response->assertOk();
        $response->assertHeader('content-type', 'application/pdf');
        $this->assertStringContainsString('laporan-bulanan-2026-10.pdf', $response->headers->get('content-disposition'));
        $this->assertStringStartsWith('%PDF-', $response->getContent());
    }

    public function test_pro_user_downloads_the_yearly_report_as_a_pdf(): void
    {
        $this->tx('pemasukan', 2_000_000, '2026-01-02');
        $this->tx('pengeluaran', 500_000, '2026-03-05');

        $response = $this->actingAs($this->user)->get(route('laporan.tahunan.pdf', ['tahun' => '2026']));

        $response->assertOk();
        $response->assertHeader('content-type', 'application/pdf');
        $this->assertStringContainsString('laporan-tahunan-2026.pdf', $response->headers->get('content-disposition'));
        $this->assertStringStartsWith('%PDF-', $response->getContent());
    }

    public function test_an_invalid_month_or_year_falls_back_to_the_current_one_instead_of_erroring(): void
    {
        $month = $this->actingAs($this->user)->get(route('laporan.bulanan.pdf', ['bulan' => '2026-13']));
        $month->assertOk();
        $this->assertStringContainsString('laporan-bulanan-'.Carbon::today()->format('Y-m').'.pdf', $month->headers->get('content-disposition'));

        $year = $this->actingAs($this->user)->get(route('laporan.tahunan.pdf', ['tahun' => 'bukan-tahun']));
        $year->assertOk();
        $this->assertStringStartsWith('%PDF-', $year->getContent());
        $this->assertStringContainsString('laporan-tahunan-'.Carbon::today()->format('Y').'.pdf', $year->headers->get('content-disposition'));
    }

    public function test_a_year_without_records_still_produces_a_pdf(): void
    {
        $response = $this->actingAs($this->user)->get(route('laporan.tahunan.pdf', ['tahun' => '2019']));

        $response->assertOk();
        $this->assertStringStartsWith('%PDF-', $response->getContent());
    }

    public function test_yearly_pdf_template_shows_the_same_numbers_and_insight_as_the_page(): void
    {
        $this->tx('pemasukan', 2_000_000, '2026-01-05');
        $this->tx('pemasukan', 1_500_000, '2025-01-05');
        $this->tx('pengeluaran', 900_000, '2026-03-05');
        $this->tx('pengeluaran', 100_000, '2026-04-05');

        $report = YearlyReportLoader::load($this->user, 2026);
        $html = view('pdf.laporan-tahunan', [
            'report' => $report,
            'insightSentences' => ReportInsightSentences::of(ReportInsights::of($report)),
            'userName' => $this->user->name,
        ])->render();

        $this->assertStringContainsString('Rp 2.000.000', $html);
        $this->assertStringContainsString('33,33%', $html);
        $this->assertStringContainsString('Bulan paling boros: Mar 2026 (Rp 900.000).', $html);
        $this->assertStringContainsString('Kamu menyisihkan 50% dari pemasukan setahun.', $html);
        // Dua belas baris bulan selalu ada, termasuk bulan kosong seperti Desember.
        $this->assertStringContainsString('Desember', $html);
        $this->assertStringContainsString('Total', $html);
    }

    public function test_monthly_pdf_template_shows_this_month_against_last_month(): void
    {
        $this->tx('pemasukan', 2_000_000, '2026-10-02');
        $this->tx('pemasukan', 1_000_000, '2026-09-02');
        $this->tx('pengeluaran', 500_000, '2026-10-05');

        $result = PeriodComparator::compare($this->user, 'bulan', '2026-10', '2026-09');
        $html = view('pdf.laporan-bulanan', [
            'periodLabel' => $result['periodLabelA'],
            'previousLabel' => $result['periodLabelB'],
            'userName' => $this->user->name,
            'income' => new MonthComparison($result['a']['income'], $result['b']['income']),
            'expense' => new MonthComparison($result['a']['expense'], $result['b']['expense']),
            'net' => new MonthComparison($result['a']['remainder'], $result['b']['remainder']),
            'rooms' => collect($result['a']['rooms'])->values(),
            'categories' => collect($result['a']['categories'])->values(),
        ])->render();

        $this->assertStringContainsString('Rp 2.000.000', $html);
        $this->assertStringContainsString('naik 100%', $html);
        $this->assertStringContainsString('Rp 500.000', $html);
    }

    public function test_bandingkan_page_offers_the_monthly_pdf_only_in_month_mode(): void
    {
        Livewire::actingAs($this->user)
            ->test(BandingkanPeriode::class)
            ->set('granularity', 'bulan')
            ->set('periodA', '2026-10')
            ->assertSee(route('laporan.bulanan.pdf', ['bulan' => '2026-10']));

        Livewire::actingAs($this->user)
            ->test(BandingkanPeriode::class)
            ->set('granularity', 'tahun')
            ->set('periodA', '2026')
            ->assertDontSee(route('laporan.bulanan.pdf', ['bulan' => '2026']));
    }
}
