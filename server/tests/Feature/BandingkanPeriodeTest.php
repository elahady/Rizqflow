<?php

namespace Tests\Feature;

use App\Livewire\BandingkanPeriode;
use App\Models\Account;
use App\Models\Category;
use App\Models\Room;
use App\Models\Transaction;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Carbon;
use Livewire\Livewire;
use Tests\TestCase;

class BandingkanPeriodeTest extends TestCase
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

    private function tx(string $type, int $amount, Carbon $date, ?int $roomId = null, ?int $categoryId = null): Transaction
    {
        return Transaction::create([
            'user_id' => $this->user->id, 'account_id' => $this->account->id, 'room_id' => $roomId, 'category_id' => $categoryId,
            'category' => $categoryId ? Category::find($categoryId)->name : null,
            'type' => $type, 'amount_minor' => $amount, 'occurred_on' => $date,
        ]);
    }

    public function test_free_user_sees_an_upsell(): void
    {
        $this->user->update(['plans' => []]);

        $this->actingAs($this->user)
            ->get(route('laporan.bandingkan'))
            ->assertOk()
            ->assertSee('Bandingkan periode adalah fitur Pro.');
    }

    public function test_defaults_to_this_month_versus_last_month(): void
    {
        $component = Livewire::actingAs($this->user)->test(BandingkanPeriode::class);

        $this->assertSame(Carbon::today()->format('Y-m'), $component->get('periodA'));
        $this->assertSame(Carbon::today()->subMonth()->format('Y-m'), $component->get('periodB'));
    }

    public function test_switching_granularity_to_year_resets_periods_to_this_year_versus_last_year(): void
    {
        $component = Livewire::actingAs($this->user)->test(BandingkanPeriode::class)->set('granularity', 'tahun');

        $this->assertSame(Carbon::today()->format('Y'), $component->get('periodA'));
        $this->assertSame(Carbon::today()->subYear()->format('Y'), $component->get('periodB'));
    }

    public function test_income_expense_and_remainder_are_computed_per_period(): void
    {
        $this->tx('pemasukan', 2_000_000, Carbon::now());
        $this->tx('pengeluaran', 500_000, Carbon::now(), $this->room->id, $this->category->id);
        $this->tx('pemasukan', 1_000_000, Carbon::now()->subMonth());
        $this->tx('pengeluaran', 300_000, Carbon::now()->subMonth(), $this->room->id, $this->category->id);

        $component = Livewire::actingAs($this->user)->test(BandingkanPeriode::class);

        $this->assertSame(2_000_000, $component->viewData('a')['income']);
        $this->assertSame(500_000, $component->viewData('a')['expense']);
        $this->assertSame(1_500_000, $component->viewData('a')['remainder']);
        $this->assertSame(1_000_000, $component->viewData('b')['income']);
        $this->assertSame(300_000, $component->viewData('b')['expense']);
    }

    public function test_room_breakdown_combines_both_periods_even_when_a_room_is_only_used_in_one(): void
    {
        $otherRoom = Room::create(['user_id' => $this->user->id, 'name' => 'Diri', 'type' => 'menumbuhkan', 'slot' => 2, 'sort_order' => 2]);
        $otherCategory = Category::create(['room_id' => $otherRoom->id, 'name' => 'Investasi', 'sort_order' => 1]);

        $this->tx('pengeluaran', 500_000, Carbon::now(), $this->room->id, $this->category->id);
        $this->tx('pengeluaran', 300_000, Carbon::now()->subMonth(), $otherRoom->id, $otherCategory->id);

        $rooms = Livewire::actingAs($this->user)->test(BandingkanPeriode::class)->viewData('rooms');

        $keluarga = $rooms->firstWhere('name', 'Keluarga');
        $diri = $rooms->firstWhere('name', 'Diri');
        $this->assertSame(500_000, $keluarga['a']);
        $this->assertSame(0, $keluarga['b']);
        $this->assertSame(0, $diri['a']);
        $this->assertSame(300_000, $diri['b']);
    }

    public function test_category_breakdown_is_limited_to_the_top_five_by_combined_spending(): void
    {
        for ($i = 1; $i <= 6; $i++) {
            $category = Category::create(['room_id' => $this->room->id, 'name' => "Pos $i", 'sort_order' => $i + 1]);
            $this->tx('pengeluaran', $i * 100_000, Carbon::now(), $this->room->id, $category->id);
        }

        $categories = Livewire::actingAs($this->user)->test(BandingkanPeriode::class)->viewData('categories');

        $this->assertCount(5, $categories);
        $this->assertSame('Pos 6', $categories->first()['name']);
    }

    public function test_an_invalid_future_year_does_not_crash_the_page(): void
    {
        Livewire::actingAs($this->user)
            ->test(BandingkanPeriode::class)
            ->set('granularity', 'tahun')
            ->set('periodA', '2030')
            ->assertOk();
    }

    public function test_insight_kalimat_tahunan_muncul_untuk_tahun_yang_dipilih(): void
    {
        $this->tx('pemasukan', 2_000_000, Carbon::parse('2026-01-05'));
        $this->tx('pengeluaran', 900_000, Carbon::parse('2026-03-05'), $this->room->id, $this->category->id);
        $this->tx('pengeluaran', 100_000, Carbon::parse('2026-04-05'), $this->room->id, $this->category->id);

        $component = Livewire::actingAs($this->user)
            ->test(BandingkanPeriode::class)
            ->set('granularity', 'tahun')
            ->set('periodA', '2026')
            ->set('periodB', '2025');

        $component->assertSee('Insight 2026')
            ->assertSee('Bulan paling boros: Mar 2026 (Rp 900.000).')
            ->assertSee('Bulan paling hemat: Apr 2026 (Rp 100.000).')
            ->assertSee('Kamu menyisihkan 50% dari pemasukan setahun.')
            ->assertSee('Rata-rata pengeluaran Rp 500.000 per bulan, dari 2 bulan yang punya pengeluaran.');
    }

    public function test_insight_tidak_dihitung_di_satuan_bulan(): void
    {
        $this->tx('pengeluaran', 900_000, Carbon::parse('2026-03-05'), $this->room->id, $this->category->id);

        $component = Livewire::actingAs($this->user)
            ->test(BandingkanPeriode::class)
            ->set('granularity', 'bulan')
            ->set('periodA', '2026-03');

        $this->assertSame([], $component->viewData('insightSentences'));
    }

    public function test_tahun_tanpa_data_tidak_menampilkan_insight(): void
    {
        $component = Livewire::actingAs($this->user)
            ->test(BandingkanPeriode::class)
            ->set('granularity', 'tahun')
            ->set('periodA', '2024');

        $this->assertSame([], $component->viewData('insightSentences'));
    }

    public function test_insight_tidak_muncul_untuk_pengguna_tanpa_pro(): void
    {
        $this->user->update(['plans' => []]);
        $this->tx('pengeluaran', 900_000, Carbon::parse('2026-03-05'), $this->room->id, $this->category->id);

        $component = Livewire::actingAs($this->user)
            ->test(BandingkanPeriode::class)
            ->set('granularity', 'tahun')
            ->set('periodA', '2026');

        $this->assertSame([], $component->viewData('insightSentences'));
    }
}
