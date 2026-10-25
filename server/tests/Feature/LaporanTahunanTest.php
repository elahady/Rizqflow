<?php

namespace Tests\Feature;

use App\Livewire\LaporanTahunan;
use App\Models\Account;
use App\Models\Category;
use App\Models\Room;
use App\Models\Transaction;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Carbon;
use Livewire\Livewire;
use Tests\TestCase;

class LaporanTahunanTest extends TestCase
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

    private function tx(string $type, int $amount, string $date, ?Room $room = null, ?Category $category = null): Transaction
    {
        return Transaction::create([
            'user_id' => $this->user->id, 'account_id' => $this->account->id,
            'room_id' => $room?->id, 'category_id' => $category?->id, 'category' => $category?->name,
            'type' => $type, 'amount_minor' => $amount, 'occurred_on' => Carbon::parse($date),
        ]);
    }

    public function test_free_user_sees_an_upsell_instead_of_the_report(): void
    {
        $this->user->update(['plans' => []]);

        $this->actingAs($this->user)
            ->get(route('laporan.tahunan'))
            ->assertOk()
            ->assertSee('Laporan tahunan adalah fitur Pro.');
    }

    public function test_the_report_always_has_twelve_month_rows_including_empty_months(): void
    {
        $this->tx('pengeluaran', 300_000, '2026-03-05', $this->room, $this->category);

        $report = Livewire::actingAs($this->user)
            ->test(LaporanTahunan::class)
            ->set('tahun', '2026')
            ->viewData('report');

        $this->assertCount(12, $report->months);
        $this->assertSame('2026-01', $report->months[0]->month);
        $this->assertSame('2026-12', $report->months[11]->month);
        $this->assertFalse($report->months[0]->hasData());
        $this->assertSame(300_000, $report->months[2]->expense);
    }

    public function test_totals_are_compared_with_the_previous_year(): void
    {
        $this->tx('pemasukan', 2_000_000, '2026-01-05');
        $this->tx('pemasukan', 1_500_000, '2025-01-05');

        $component = Livewire::actingAs($this->user)
            ->test(LaporanTahunan::class)
            ->set('tahun', '2026');

        $report = $component->viewData('report');
        $this->assertSame(2_000_000, $report->income->current);
        $this->assertSame(1_500_000, $report->income->previous);
        $this->assertSame(3_333, $report->income->percentBp());
        $component->assertSee('Rp 2.000.000')->assertSee('33,33%');
    }

    public function test_pengeluaran_tanpa_ruang_tidak_ikut_total_seperti_di_bandingkan_periode(): void
    {
        // Aturan web (PeriodComparator::summaryOf, dipakai Bandingkan periode): total pengeluaran hanya
        // menghitung transaksi yang punya ruang. Android menghitung semua pengeluaran; perbedaan ini
        // dicatat di roadmap dan tidak diubah di sini supaya kedua layar web tetap sama.
        $this->tx('pengeluaran', 400_000, '2026-02-10', $this->room, $this->category);
        $this->tx('pengeluaran', 100_000, '2026-02-11');

        $report = Livewire::actingAs($this->user)
            ->test(LaporanTahunan::class)
            ->set('tahun', '2026')
            ->viewData('report');

        $this->assertSame(400_000, $report->expense->current);
        $this->assertCount(1, $report->roomSpend);
        $this->assertSame(400_000, $report->roomSpend[0]->amount);
    }

    public function test_room_spend_is_sorted_from_the_largest_and_categories_are_limited_to_ten(): void
    {
        $other = Room::create(['user_id' => $this->user->id, 'name' => 'Diri', 'type' => 'menumbuhkan', 'slot' => 2, 'sort_order' => 2]);
        $this->tx('pengeluaran', 100_000, '2026-04-01', $other, Category::create(['room_id' => $other->id, 'name' => 'Kecil', 'sort_order' => 1]));
        $this->tx('pengeluaran', 900_000, '2026-04-02', $this->room, $this->category);

        for ($i = 1; $i <= 11; $i++) {
            $category = Category::create(['room_id' => $this->room->id, 'name' => "Pos $i", 'sort_order' => $i + 1]);
            $this->tx('pengeluaran', $i * 10_000, '2026-05-01', $this->room, $category);
        }

        $report = Livewire::actingAs($this->user)
            ->test(LaporanTahunan::class)
            ->set('tahun', '2026')
            ->viewData('report');

        $this->assertSame('Keluarga', $report->roomSpend[0]->roomName);
        $this->assertSame('Diri', $report->roomSpend[1]->roomName);
        $this->assertCount(10, $report->topCategories);
        $this->assertSame('Belanja bulanan', $report->topCategories[0]->categoryName);
        $this->assertSame(900_000, $report->topCategories[0]->amount);
    }

    public function test_changing_the_year_shows_that_year_only(): void
    {
        $this->tx('pemasukan', 700_000, '2025-06-01');
        $this->tx('pemasukan', 2_000_000, '2026-06-01');

        $component = Livewire::actingAs($this->user)
            ->test(LaporanTahunan::class)
            ->set('tahun', '2025');

        $report = $component->viewData('report');
        $this->assertSame(2025, $report->year);
        $this->assertSame(700_000, $report->income->current);
        $this->assertSame(0, $report->income->previous);
    }

    public function test_insight_kalimat_is_shown_on_the_yearly_page(): void
    {
        $this->tx('pemasukan', 2_000_000, '2026-01-05');
        $this->tx('pengeluaran', 900_000, '2026-03-05', $this->room, $this->category);
        $this->tx('pengeluaran', 100_000, '2026-04-05', $this->room, $this->category);

        Livewire::actingAs($this->user)
            ->test(LaporanTahunan::class)
            ->set('tahun', '2026')
            ->assertSee('Insight 2026')
            ->assertSee('Bulan paling boros: Mar 2026 (Rp 900.000).')
            ->assertSee('Bulan paling hemat: Apr 2026 (Rp 100.000).');
    }

    public function test_a_year_without_any_records_shows_the_empty_state(): void
    {
        Livewire::actingAs($this->user)
            ->test(LaporanTahunan::class)
            ->set('tahun', '2024')
            ->assertSee('Belum ada catatan di 2024')
            ->assertDontSee('Insight 2024');
    }

    public function test_an_invalid_year_falls_back_to_this_year_instead_of_erroring(): void
    {
        Livewire::actingAs($this->user)
            ->test(LaporanTahunan::class)
            ->set('tahun', 'bukan-tahun')
            ->assertOk()
            ->assertSee('Tahun '.Carbon::today()->format('Y'));
    }

    public function test_the_yearly_tab_is_listed_in_the_analisa_navigation(): void
    {
        $this->actingAs($this->user)
            ->get(route('laporan.bandingkan'))
            ->assertOk()
            ->assertSee('Tahunan');
    }
}
