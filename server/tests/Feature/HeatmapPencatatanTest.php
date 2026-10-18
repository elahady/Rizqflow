<?php

namespace Tests\Feature;

use App\Livewire\HeatmapPencatatan;
use App\Models\Account;
use App\Models\Transaction;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Carbon;
use Livewire\Livewire;
use Tests\TestCase;

class HeatmapPencatatanTest extends TestCase
{
    use RefreshDatabase;

    private User $user;

    private Account $account;

    protected function setUp(): void
    {
        parent::setUp();

        $this->user = User::factory()->create(['plans' => ['pro']]);
        $this->account = Account::create(['user_id' => $this->user->id, 'name' => 'Bank Jago', 'type' => 'bank', 'sort_order' => 1]);
    }

    private function tx(Carbon $date): Transaction
    {
        return Transaction::create([
            'user_id' => $this->user->id, 'account_id' => $this->account->id,
            'type' => 'pengeluaran', 'amount_minor' => 10_000, 'occurred_on' => $date,
        ]);
    }

    public function test_free_user_sees_an_upsell(): void
    {
        $this->user->update(['plans' => []]);

        $this->actingAs($this->user)
            ->get(route('laporan.heatmap'))
            ->assertOk()
            ->assertSee('Heatmap pencatatan adalah fitur Pro.');
    }

    public function test_the_grid_spans_full_weeks_covering_at_least_a_year(): void
    {
        $grid = Livewire::actingAs($this->user)->test(HeatmapPencatatan::class)->viewData('grid');

        $this->assertGreaterThanOrEqual(53, count($grid['weeks']));
        foreach ($grid['weeks'] as $week) {
            $this->assertCount(7, $week);
        }
    }

    public function test_days_without_a_transaction_are_level_zero(): void
    {
        $grid = Livewire::actingAs($this->user)->test(HeatmapPencatatan::class)->viewData('grid');

        $this->assertSame(0, $grid['totalDays']);
        foreach ($grid['weeks'] as $week) {
            foreach ($week as $day) {
                $this->assertSame(0, $day['level']);
            }
        }
    }

    public function test_the_busiest_day_gets_the_highest_level(): void
    {
        for ($i = 0; $i < 5; $i++) {
            $this->tx(Carbon::today());
        }
        $this->tx(Carbon::today()->subDay());

        $grid = Livewire::actingAs($this->user)->test(HeatmapPencatatan::class)->viewData('grid');
        $byDate = collect($grid['weeks'])->flatten(1)->keyBy('date');

        $this->assertSame(4, $byDate[Carbon::today()->toDateString()]['level']);
        $this->assertLessThan(4, $byDate[Carbon::today()->subDay()->toDateString()]['level']);
        $this->assertSame(2, $grid['totalDays']);
    }

    public function test_current_streak_counts_consecutive_days_up_to_today(): void
    {
        $this->tx(Carbon::today());
        $this->tx(Carbon::today()->subDay());
        $this->tx(Carbon::today()->subDays(2));
        $this->tx(Carbon::today()->subDays(4)); // bolong di hari ke-3, memutus beruntun

        $grid = Livewire::actingAs($this->user)->test(HeatmapPencatatan::class)->viewData('grid');

        $this->assertSame(3, $grid['streak']);
    }

    public function test_a_broken_streak_counts_zero_when_today_has_no_record(): void
    {
        $this->tx(Carbon::today()->subDay());

        $grid = Livewire::actingAs($this->user)->test(HeatmapPencatatan::class)->viewData('grid');

        $this->assertSame(0, $grid['streak']);
    }

    public function test_days_in_the_future_within_the_last_week_are_marked_so_they_render_as_blank(): void
    {
        $grid = Livewire::actingAs($this->user)->test(HeatmapPencatatan::class)->viewData('grid');
        $lastWeek = end($grid['weeks']);

        $hasFuture = collect($lastWeek)->contains(fn ($day) => $day['inFuture']);
        $this->assertTrue($hasFuture || Carbon::today()->dayOfWeek === Carbon::SATURDAY);
    }
}
