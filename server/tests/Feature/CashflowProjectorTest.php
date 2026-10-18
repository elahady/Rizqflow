<?php

namespace Tests\Feature;

use App\Livewire\ProyeksiCashflow;
use App\Models\Account;
use App\Models\Bill;
use App\Models\Category;
use App\Models\RecurringRule;
use App\Models\Room;
use App\Models\User;
use App\Support\CashflowProjector;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Carbon;
use Livewire\Livewire;
use Tests\TestCase;

class CashflowProjectorTest extends TestCase
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
        $this->account = Account::create(['user_id' => $this->user->id, 'name' => 'Bank Jago', 'type' => 'bank', 'balance_minor' => 1_000_000, 'sort_order' => 1]);
    }

    public function test_starts_from_the_total_balance_of_all_accounts(): void
    {
        Account::create(['user_id' => $this->user->id, 'name' => 'Tunai', 'type' => 'tunai', 'balance_minor' => 500_000, 'sort_order' => 2]);

        $result = CashflowProjector::project($this->user, 30);

        $this->assertSame(1_500_000, $result['startBalance']);
    }

    public function test_an_upcoming_income_rule_increases_the_projected_balance(): void
    {
        RecurringRule::create([
            'user_id' => $this->user->id, 'type' => 'pemasukan', 'amount_minor' => 500_000, 'source' => 'Bonus',
            'account_id' => $this->account->id, 'frequency' => 'bulanan',
            'start_date' => Carbon::today(), 'next_due' => Carbon::today()->addDays(5), 'active' => true,
        ]);

        $result = CashflowProjector::project($this->user, 30);

        $this->assertCount(1, $result['events']);
        $this->assertSame(500_000, $result['events'][0]['amount_minor']);
        $this->assertSame(1_500_000, $result['events'][0]['balance_minor']);
        $this->assertSame(1_500_000, end($result['dailyBalances']));
    }

    public function test_a_scheduled_bill_decreases_the_projected_balance(): void
    {
        Bill::create([
            'user_id' => $this->user->id, 'name' => 'Listrik', 'amount_minor' => 200_000,
            'account_id' => $this->account->id, 'room_id' => $this->room->id, 'category_id' => $this->category->id,
            'frequency' => 'bulanan', 'start_date' => Carbon::today(), 'next_due' => Carbon::today()->addDays(10), 'active' => true,
        ]);

        $result = CashflowProjector::project($this->user, 30);

        $this->assertSame(-200_000, $result['events'][0]['amount_minor']);
        $this->assertSame(800_000, $result['events'][0]['balance_minor']);
    }

    public function test_an_overdue_rule_is_projected_as_happening_today_not_dropped(): void
    {
        RecurringRule::create([
            'user_id' => $this->user->id, 'type' => 'pengeluaran', 'amount_minor' => 100_000,
            'account_id' => $this->account->id, 'room_id' => $this->room->id, 'category_id' => $this->category->id,
            'frequency' => 'bulanan', 'start_date' => Carbon::today()->subDays(40), 'next_due' => Carbon::today()->subDays(10), 'active' => true,
        ]);

        $result = CashflowProjector::project($this->user, 30);

        $this->assertSame(Carbon::today()->toDateString(), $result['events'][0]['date']);
        $this->assertSame(900_000, $result['dailyBalances'][0]);
    }

    public function test_a_one_off_bill_produces_only_one_occurrence(): void
    {
        Bill::create([
            'user_id' => $this->user->id, 'name' => 'Pajak motor', 'amount_minor' => 350_000,
            'account_id' => $this->account->id, 'room_id' => $this->room->id, 'category_id' => $this->category->id,
            'frequency' => null, 'start_date' => Carbon::today(), 'next_due' => Carbon::today()->addDays(5), 'active' => true,
        ]);

        $result = CashflowProjector::project($this->user, 90);

        $this->assertCount(1, $result['events']);
    }

    public function test_installments_stop_at_the_remaining_count(): void
    {
        Bill::create([
            'user_id' => $this->user->id, 'name' => 'Cicilan motor', 'amount_minor' => 500_000,
            'account_id' => $this->account->id, 'room_id' => $this->room->id, 'category_id' => $this->category->id,
            'frequency' => 'bulanan', 'total_installments' => 12, 'paid_count' => 10,
            'start_date' => Carbon::today(), 'next_due' => Carbon::today()->addDays(3), 'active' => true,
        ]);

        $result = CashflowProjector::project($this->user, 90);

        $this->assertCount(2, $result['events']);
    }

    public function test_a_paused_rule_and_a_bill_with_an_archived_room_are_excluded(): void
    {
        RecurringRule::create([
            'user_id' => $this->user->id, 'type' => 'pemasukan', 'amount_minor' => 500_000,
            'account_id' => $this->account->id, 'frequency' => 'bulanan',
            'start_date' => Carbon::today(), 'next_due' => Carbon::today()->addDays(5), 'active' => false,
        ]);
        $this->room->update(['archived' => true]);
        Bill::create([
            'user_id' => $this->user->id, 'name' => 'Listrik', 'amount_minor' => 200_000,
            'account_id' => $this->account->id, 'room_id' => $this->room->id, 'category_id' => $this->category->id,
            'frequency' => 'bulanan', 'start_date' => Carbon::today(), 'next_due' => Carbon::today()->addDays(10), 'active' => true,
        ]);

        $result = CashflowProjector::project($this->user, 30);

        $this->assertCount(0, $result['events']);
        $this->assertSame(1_000_000, end($result['dailyBalances']));
    }

    public function test_free_user_sees_an_upsell(): void
    {
        $this->user->update(['plans' => []]);

        $this->actingAs($this->user)
            ->get(route('laporan.cashflow'))
            ->assertOk()
            ->assertSee('Proyeksi cashflow adalah fitur Pro.');
    }

    public function test_page_dispatches_the_chart_data_event_for_a_pro_user(): void
    {
        $series = null;
        Livewire::actingAs($this->user)
            ->test(ProyeksiCashflow::class)
            ->assertDispatched('cashflow-chart-data', function ($name, $params) use (&$series) {
                $series = $params['series'];

                return true;
            });

        $this->assertCount(31, $series['labels']);
    }
}
