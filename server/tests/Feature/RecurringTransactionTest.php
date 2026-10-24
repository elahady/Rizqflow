<?php

namespace Tests\Feature;

use App\Livewire\TransaksiBerulang;
use App\Models\Account;
use App\Models\Category;
use App\Models\RecurringRule;
use App\Models\Room;
use App\Models\Transaction;
use App\Models\User;
use App\Support\RecurringRunner;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Carbon;
use Livewire\Livewire;
use Tests\TestCase;

class RecurringTransactionTest extends TestCase
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
        $this->account = Account::create(['user_id' => $this->user->id, 'name' => 'Bank Jago', 'type' => 'bank', 'balance_minor' => 1_000_000, 'sort_order' => 1]);
    }

    // --- Kelola aturan (TransaksiBerulang) ---

    public function test_creating_a_monthly_expense_rule(): void
    {
        Livewire::actingAs($this->user)
            ->test(TransaksiBerulang::class)
            ->call('startAdd')
            ->set('amount', '150000')
            ->set('roomId', $this->room->id)
            ->set('categoryId', $this->category->id)
            ->set('accountId', $this->account->id)
            ->set('frequency', 'bulanan')
            ->set('startDate', Carbon::today()->toDateString())
            ->set('note', 'Internet rumah')
            ->call('save')
            ->assertSet('error', null);

        $rule = RecurringRule::first();
        $this->assertSame('pengeluaran', $rule->type);
        $this->assertSame(150_000, $rule->amount_minor);
        $this->assertSame($this->room->id, $rule->room_id);
        $this->assertTrue($rule->active);
        $this->assertTrue($rule->next_due->equalTo(Carbon::today()));
    }

    public function test_creating_an_income_rule_without_a_room(): void
    {
        Livewire::actingAs($this->user)
            ->test(TransaksiBerulang::class)
            ->call('startAdd')
            ->set('type', 'pemasukan')
            ->set('amount', '8500000')
            ->set('source', 'Gaji')
            ->set('accountId', $this->account->id)
            ->set('frequency', 'bulanan')
            ->set('startDate', Carbon::today()->toDateString())
            ->call('save')
            ->assertSet('error', null);

        $rule = RecurringRule::first();
        $this->assertSame('pemasukan', $rule->type);
        $this->assertNull($rule->room_id);
        $this->assertSame('Gaji', $rule->source);
    }

    public function test_start_date_before_today_is_rejected_on_create(): void
    {
        Livewire::actingAs($this->user)
            ->test(TransaksiBerulang::class)
            ->call('startAdd')
            ->set('amount', '150000')
            ->set('roomId', $this->room->id)
            ->set('categoryId', $this->category->id)
            ->set('accountId', $this->account->id)
            ->set('startDate', Carbon::today()->subDay()->toDateString())
            ->call('save')
            ->assertSet('error', 'Tanggal mulai tidak boleh sebelum hari ini.');

        $this->assertSame(0, RecurringRule::count());
    }

    public function test_pausing_and_resuming_does_not_catch_up_missed_occurrences(): void
    {
        $rule = RecurringRule::create([
            'user_id' => $this->user->id, 'type' => 'pengeluaran', 'amount_minor' => 50_000,
            'account_id' => $this->account->id, 'room_id' => $this->room->id, 'category_id' => $this->category->id,
            'frequency' => 'harian', 'start_date' => Carbon::today()->subDays(5), 'next_due' => Carbon::today()->subDays(3), 'active' => true,
        ]);

        Livewire::actingAs($this->user)->test(TransaksiBerulang::class)->call('pause', $rule->id);
        $this->assertFalse($rule->refresh()->active);

        Livewire::actingAs($this->user)->test(TransaksiBerulang::class)->call('resume', $rule->id);
        $rule->refresh();
        $this->assertTrue($rule->active);
        $this->assertTrue($rule->next_due->equalTo(Carbon::today()));
    }

    public function test_deleting_a_rule_keeps_its_recorded_transactions(): void
    {
        $rule = RecurringRule::create([
            'user_id' => $this->user->id, 'type' => 'pengeluaran', 'amount_minor' => 50_000,
            'account_id' => $this->account->id, 'room_id' => $this->room->id, 'category_id' => $this->category->id,
            'frequency' => 'bulanan', 'start_date' => Carbon::today(), 'next_due' => Carbon::today(), 'active' => true,
        ]);
        RecurringRunner::runDue($this->user);
        $this->assertSame(1, Transaction::count());

        Livewire::actingAs($this->user)->test(TransaksiBerulang::class)->call('delete', $rule->id);

        $this->assertSame(0, RecurringRule::count());
        $this->assertSame(1, Transaction::count());
        $this->assertNull(Transaction::first()->recurring_rule_id);
    }

    public function test_cannot_manage_another_users_rule(): void
    {
        $otherUser = User::factory()->create();
        $otherAccount = Account::create(['user_id' => $otherUser->id, 'name' => 'Tunai', 'type' => 'tunai', 'sort_order' => 1]);
        $otherRule = RecurringRule::create([
            'user_id' => $otherUser->id, 'type' => 'pemasukan', 'amount_minor' => 1000,
            'account_id' => $otherAccount->id, 'frequency' => 'bulanan', 'start_date' => Carbon::today(), 'next_due' => Carbon::today(),
        ]);

        Livewire::actingAs($this->user)
            ->test(TransaksiBerulang::class)
            ->call('delete', $otherRule->id)
            ->assertStatus(404);

        $this->assertSame(1, RecurringRule::count());
    }

    // --- RecurringRunner ---

    public function test_running_due_records_a_due_occurrence_and_moves_the_account_balance(): void
    {
        RecurringRule::create([
            'user_id' => $this->user->id, 'type' => 'pengeluaran', 'amount_minor' => 150_000,
            'account_id' => $this->account->id, 'room_id' => $this->room->id, 'category_id' => $this->category->id,
            'frequency' => 'bulanan', 'start_date' => Carbon::today(), 'next_due' => Carbon::today(), 'active' => true,
        ]);

        $result = RecurringRunner::runDue($this->user);

        $this->assertSame(1, $result['recorded']);
        $tx = Transaction::first();
        $this->assertSame('pengeluaran', $tx->type);
        $this->assertSame(150_000, $tx->amount_minor);
        $this->assertSame(1_000_000 - 150_000, $this->account->refresh()->balance_minor);
    }

    public function test_running_due_records_a_recurring_income_with_its_allocation_snapshot(): void
    {
        $this->room->update(['allocation_percent' => 100]);
        $rule = RecurringRule::create([
            'user_id' => $this->user->id, 'type' => 'pemasukan', 'amount_minor' => 5_000_000,
            'account_id' => $this->account->id, 'source' => 'Gaji',
            'frequency' => 'bulanan', 'start_date' => Carbon::today(), 'next_due' => Carbon::today(), 'active' => true,
        ]);

        $result = RecurringRunner::runDue($this->user);

        $this->assertSame(1, $result['recorded']);
        $tx = Transaction::first();
        $this->assertSame('pemasukan', $tx->type);
        $this->assertSame($rule->id, $tx->recurring_rule_id);
        $this->assertSame('Gaji', $tx->category);
        $this->assertSame([5_000_000], $tx->allocationEntries->pluck('amount_minor')->all());
        $this->assertSame(1_000_000 + 5_000_000, $this->account->refresh()->balance_minor);
    }

    public function test_running_due_catches_up_multiple_missed_daily_occurrences(): void
    {
        RecurringRule::create([
            'user_id' => $this->user->id, 'type' => 'pemasukan', 'amount_minor' => 10_000, 'source' => 'Usaha',
            'account_id' => $this->account->id, 'frequency' => 'harian', 'start_date' => Carbon::today()->subDays(2), 'next_due' => Carbon::today()->subDays(2), 'active' => true,
        ]);

        $result = RecurringRunner::runDue($this->user);

        $this->assertSame(3, $result['recorded']);
        $this->assertSame(3, Transaction::count());
        $this->assertSame(1_000_000 + 30_000, $this->account->refresh()->balance_minor);
    }

    public function test_running_due_twice_does_not_duplicate_transactions(): void
    {
        RecurringRule::create([
            'user_id' => $this->user->id, 'type' => 'pengeluaran', 'amount_minor' => 50_000,
            'account_id' => $this->account->id, 'room_id' => $this->room->id, 'category_id' => $this->category->id,
            'frequency' => 'bulanan', 'start_date' => Carbon::today(), 'next_due' => Carbon::today(), 'active' => true,
        ]);

        RecurringRunner::runDue($this->user);
        $second = RecurringRunner::runDue($this->user);

        $this->assertSame(0, $second['recorded']);
        $this->assertSame(1, Transaction::count());
    }

    public function test_running_due_skips_a_paused_rule(): void
    {
        RecurringRule::create([
            'user_id' => $this->user->id, 'type' => 'pengeluaran', 'amount_minor' => 50_000,
            'account_id' => $this->account->id, 'room_id' => $this->room->id, 'category_id' => $this->category->id,
            'frequency' => 'bulanan', 'start_date' => Carbon::today(), 'next_due' => Carbon::today(), 'active' => false,
        ]);

        $result = RecurringRunner::runDue($this->user);

        $this->assertSame(0, $result['recorded']);
        $this->assertSame(0, Transaction::count());
    }

    public function test_running_due_blocks_a_rule_whose_room_is_archived(): void
    {
        $this->room->update(['archived' => true]);
        RecurringRule::create([
            'user_id' => $this->user->id, 'type' => 'pengeluaran', 'amount_minor' => 50_000,
            'account_id' => $this->account->id, 'room_id' => $this->room->id, 'category_id' => $this->category->id,
            'frequency' => 'bulanan', 'start_date' => Carbon::today(), 'next_due' => Carbon::today(), 'active' => true,
        ]);

        $result = RecurringRunner::runDue($this->user);

        $this->assertSame(0, $result['recorded']);
        $this->assertCount(1, $result['blocked']);
        $this->assertSame(0, Transaction::count());
    }

    public function test_running_due_does_not_run_past_an_end_date(): void
    {
        RecurringRule::create([
            'user_id' => $this->user->id, 'type' => 'pengeluaran', 'amount_minor' => 50_000,
            'account_id' => $this->account->id, 'room_id' => $this->room->id, 'category_id' => $this->category->id,
            'frequency' => 'harian', 'start_date' => Carbon::today()->subDays(5), 'next_due' => Carbon::today()->subDays(5),
            'end_date' => Carbon::today()->subDays(3), 'active' => true,
        ]);

        $result = RecurringRunner::runDue($this->user);

        // Kemunculan hari ke -5, -4, -3 (jatuh tempo <= end_date) tercatat; sesudahnya berhenti karena isFinished().
        $this->assertSame(3, $result['recorded']);
    }
}
