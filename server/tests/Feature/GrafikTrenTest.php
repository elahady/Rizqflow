<?php

namespace Tests\Feature;

use App\Livewire\GrafikTren;
use App\Models\Account;
use App\Models\Category;
use App\Models\Room;
use App\Models\Transaction;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Carbon;
use Livewire\Livewire;
use Tests\TestCase;

class GrafikTrenTest extends TestCase
{
    use RefreshDatabase;

    private User $user;

    private Room $room;

    private Category $category;

    private Account $account;

    protected function setUp(): void
    {
        parent::setUp();

        $this->user = User::factory()->create();
        $this->room = Room::create(['user_id' => $this->user->id, 'name' => 'Keluarga', 'type' => 'mencukupi', 'slot' => 1, 'sort_order' => 1]);
        $this->category = Category::create(['room_id' => $this->room->id, 'name' => 'Belanja bulanan', 'sort_order' => 1]);
        $this->account = Account::create(['user_id' => $this->user->id, 'name' => 'Bank Jago', 'type' => 'bank', 'sort_order' => 1]);
    }

    private function tx(string $type, int $amount, Carbon $date, ?int $roomId = null, ?int $categoryId = null): Transaction
    {
        return Transaction::create([
            'user_id' => $this->user->id, 'account_id' => $this->account->id, 'room_id' => $roomId, 'category_id' => $categoryId,
            'type' => $type, 'amount_minor' => $amount, 'occurred_on' => $date,
        ]);
    }

    /** Menangkap payload "series" dari event trend-chart-data lewat API publik assertDispatched. */
    private function capturedSeries($component): array
    {
        $series = null;
        $component->assertDispatched('trend-chart-data', function ($name, $params) use (&$series) {
            $series = $params['series'];

            return true;
        });

        return $series;
    }

    public function test_free_user_sees_an_upsell_instead_of_the_chart(): void
    {
        $this->actingAs($this->user)
            ->get(route('laporan.tren'))
            ->assertOk()
            ->assertSee('Grafik tren adalah fitur Pro.')
            ->assertDontSee('id="trend-chart"', false);
    }

    public function test_free_user_does_not_receive_a_trend_chart_data_event(): void
    {
        Livewire::actingAs($this->user)
            ->test(GrafikTren::class)
            ->assertNotDispatched('trend-chart-data');
    }

    public function test_pro_user_gets_monthly_totals_for_the_selected_range(): void
    {
        $this->user->update(['plans' => ['pro']]);
        $this->tx('pemasukan', 1_000_000, Carbon::now()->subMonths(1));
        $this->tx('pengeluaran', 300_000, Carbon::now()->subMonths(1), $this->room->id, $this->category->id);
        $this->tx('pemasukan', 2_000_000, Carbon::now());
        $this->tx('pengeluaran', 500_000, Carbon::now(), $this->room->id, $this->category->id);

        $component = Livewire::actingAs($this->user)->test(GrafikTren::class)->set('months', 2);
        $series = $this->capturedSeries($component);

        $this->assertSame([300_000, 500_000], $series['expense']);
        $this->assertSame([1_000_000, 2_000_000], $series['income']);
        $this->assertCount(2, $series['labels']);
    }

    public function test_room_filter_narrows_the_expense_series_but_not_income(): void
    {
        $this->user->update(['plans' => ['pro']]);
        $otherRoom = Room::create(['user_id' => $this->user->id, 'name' => 'Diri', 'type' => 'menumbuhkan', 'slot' => 2, 'sort_order' => 2]);
        $otherCategory = Category::create(['room_id' => $otherRoom->id, 'name' => 'Investasi', 'sort_order' => 1]);

        $this->tx('pemasukan', 1_000_000, Carbon::now());
        $this->tx('pengeluaran', 300_000, Carbon::now(), $this->room->id, $this->category->id);
        $this->tx('pengeluaran', 700_000, Carbon::now(), $otherRoom->id, $otherCategory->id);

        $component = Livewire::actingAs($this->user)->test(GrafikTren::class)->set('months', 1)->set('roomId', $this->room->id);
        $series = $this->capturedSeries($component);

        $this->assertSame([300_000], $series['expense']);
        $this->assertSame([1_000_000], $series['income']);
    }

    public function test_category_filter_further_narrows_the_expense_series(): void
    {
        $this->user->update(['plans' => ['pro']]);
        $otherCategory = Category::create(['room_id' => $this->room->id, 'name' => 'Listrik', 'sort_order' => 2]);

        $this->tx('pengeluaran', 300_000, Carbon::now(), $this->room->id, $this->category->id);
        $this->tx('pengeluaran', 150_000, Carbon::now(), $this->room->id, $otherCategory->id);

        $component = Livewire::actingAs($this->user)
            ->test(GrafikTren::class)
            ->set('months', 1)
            ->set('roomId', $this->room->id)
            ->set('categoryId', $this->category->id);
        $series = $this->capturedSeries($component);

        $this->assertSame([300_000], $series['expense']);
    }

    public function test_changing_the_room_resets_the_category_filter(): void
    {
        Livewire::actingAs($this->user)
            ->test(GrafikTren::class)
            ->set('categoryId', $this->category->id)
            ->set('roomId', $this->room->id)
            ->assertSet('categoryId', null);
    }

    public function test_months_outside_the_range_are_zero(): void
    {
        $this->user->update(['plans' => ['pro']]);
        $this->tx('pemasukan', 1_000_000, Carbon::now()->subMonths(5));

        $component = Livewire::actingAs($this->user)->test(GrafikTren::class)->set('months', 2);
        $series = $this->capturedSeries($component);

        $this->assertSame([0, 0], $series['income']);
    }
}
