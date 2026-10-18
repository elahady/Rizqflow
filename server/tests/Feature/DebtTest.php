<?php

namespace Tests\Feature;

use App\Livewire\UtangPiutang;
use App\Models\Account;
use App\Models\Debt;
use App\Models\DebtPayment;
use App\Models\Transaction;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Carbon;
use Livewire\Livewire;
use Tests\TestCase;

class DebtTest extends TestCase
{
    use RefreshDatabase;

    private User $user;

    private Account $account;

    protected function setUp(): void
    {
        parent::setUp();

        $this->user = User::factory()->create();
        $this->account = Account::create(['user_id' => $this->user->id, 'name' => 'Bank Jago', 'type' => 'bank', 'balance_minor' => 1_000_000, 'sort_order' => 1]);
    }

    // --- Catat baru ---

    public function test_lending_moves_money_out_of_the_account_without_becoming_an_expense(): void
    {
        Livewire::actingAs($this->user)
            ->test(UtangPiutang::class)
            ->call('startAdd')
            ->set('direction', 'piutang')
            ->set('party', 'Budi')
            ->set('amount', '150000')
            ->set('accountId', $this->account->id)
            ->call('save')
            ->assertSet('error', null);

        $debt = Debt::first();
        $this->assertSame('piutang', $debt->direction);
        $this->assertSame(150_000, $debt->principal_minor);
        $this->assertSame(1_000_000 - 150_000, $this->account->refresh()->balance_minor);
        $this->assertSame(0, Transaction::count());
    }

    public function test_borrowing_moves_money_into_the_account_without_being_allocated_to_a_room(): void
    {
        Livewire::actingAs($this->user)
            ->test(UtangPiutang::class)
            ->call('startAdd')
            ->set('direction', 'utang')
            ->set('party', 'Ayah')
            ->set('amount', '500000')
            ->set('accountId', $this->account->id)
            ->call('save');

        $debt = Debt::first();
        $this->assertSame('utang', $debt->direction);
        $this->assertSame(1_000_000 + 500_000, $this->account->refresh()->balance_minor);
    }

    public function test_an_old_loan_without_moving_money_does_not_touch_the_balance(): void
    {
        Livewire::actingAs($this->user)
            ->test(UtangPiutang::class)
            ->call('startAdd')
            ->set('direction', 'piutang')
            ->set('party', 'Budi')
            ->set('amount', '150000')
            ->set('recordMovement', false)
            ->call('save');

        $this->assertSame(1_000_000, $this->account->refresh()->balance_minor);
        $this->assertNull(Debt::first()->account_id);
    }

    public function test_invalid_input_is_rejected_and_nothing_is_saved(): void
    {
        Livewire::actingAs($this->user)
            ->test(UtangPiutang::class)
            ->call('startAdd')
            ->set('party', '  ')
            ->set('amount', '150000')
            ->set('accountId', $this->account->id)
            ->call('save')
            ->assertSet('error', 'Nama pihak 1 sampai 40 karakter.');

        $this->assertSame(0, Debt::count());
        $this->assertSame(1_000_000, $this->account->refresh()->balance_minor);
    }

    public function test_an_archived_account_fails_the_new_loan_without_saving_the_debt(): void
    {
        $this->account->update(['archived' => true]);

        Livewire::actingAs($this->user)
            ->test(UtangPiutang::class)
            ->call('startAdd')
            ->set('party', 'Budi')
            ->set('amount', '150000')
            ->set('accountId', $this->account->id)
            ->call('save')
            ->assertSet('error', 'Pilih akun.');

        $this->assertSame(0, Debt::count());
    }

    // --- Pelunasan ---

    public function test_partial_repayment_reduces_the_outstanding_amount_and_moves_the_balance(): void
    {
        $debt = Debt::create(['user_id' => $this->user->id, 'direction' => 'piutang', 'party' => 'Budi', 'principal_minor' => 200_000, 'account_id' => $this->account->id, 'start_date' => Carbon::today()]);
        $this->account->decrement('balance_minor', 200_000);

        Livewire::actingAs($this->user)
            ->test(UtangPiutang::class)
            ->call('startRepay', $debt->id)
            ->set('payAmount', '50000')
            ->set('payAccountId', $this->account->id)
            ->call('repay')
            ->assertSet('payError', null);

        $this->assertSame(150_000, $debt->outstandingMinor());
        $this->assertFalse($debt->isSettled());
        $this->assertSame(1_000_000 - 200_000 + 50_000, $this->account->refresh()->balance_minor);
    }

    public function test_full_repayment_settles_the_debt(): void
    {
        $debt = Debt::create(['user_id' => $this->user->id, 'direction' => 'utang', 'party' => 'Ayah', 'principal_minor' => 200_000, 'account_id' => $this->account->id, 'start_date' => Carbon::today()]);
        $this->account->increment('balance_minor', 200_000);

        Livewire::actingAs($this->user)
            ->test(UtangPiutang::class)
            ->call('startRepay', $debt->id)
            ->set('payAccountId', $this->account->id)
            ->call('repay');

        $this->assertTrue($debt->isSettled());
        $this->assertSame(1_000_000, $this->account->refresh()->balance_minor);
    }

    public function test_repayment_exceeding_the_outstanding_amount_is_rejected(): void
    {
        $debt = Debt::create(['user_id' => $this->user->id, 'direction' => 'piutang', 'party' => 'Budi', 'principal_minor' => 200_000, 'start_date' => Carbon::today()]);

        Livewire::actingAs($this->user)
            ->test(UtangPiutang::class)
            ->call('startRepay', $debt->id)
            ->set('payAmount', '250000')
            ->set('payAccountId', $this->account->id)
            ->call('repay')
            ->assertSet('payError', 'Nominal tidak boleh melebihi sisa Rp 200.000.');

        $this->assertSame(0, DebtPayment::count());
    }

    public function test_repayment_without_an_account_reduces_outstanding_without_touching_the_balance(): void
    {
        $debt = Debt::create(['user_id' => $this->user->id, 'direction' => 'piutang', 'party' => 'Budi', 'principal_minor' => 200_000, 'start_date' => Carbon::today()]);

        Livewire::actingAs($this->user)
            ->test(UtangPiutang::class)
            ->call('startRepay', $debt->id)
            ->set('payAmount', '50000')
            ->set('payUpdateBalance', false)
            ->call('repay');

        $this->assertSame(150_000, $debt->outstandingMinor());
        $this->assertSame(1_000_000, $this->account->refresh()->balance_minor);
    }

    public function test_deleting_one_misrecorded_payment_restores_the_outstanding_amount_and_balance(): void
    {
        $debt = Debt::create(['user_id' => $this->user->id, 'direction' => 'piutang', 'party' => 'Budi', 'principal_minor' => 200_000, 'start_date' => Carbon::today()]);
        $payment = DebtPayment::create(['debt_id' => $debt->id, 'amount_minor' => 50_000, 'paid_on' => Carbon::today(), 'account_id' => $this->account->id]);
        $this->account->increment('balance_minor', 50_000);

        Livewire::actingAs($this->user)->test(UtangPiutang::class)->call('deletePayment', $payment->id);

        $this->assertSame(200_000, $debt->outstandingMinor());
        $this->assertSame(1_000_000, $this->account->refresh()->balance_minor);
    }

    // --- Hapus utang ---

    public function test_deleting_a_debt_reverses_its_initial_movement_and_all_its_payments(): void
    {
        $debt = Debt::create(['user_id' => $this->user->id, 'direction' => 'piutang', 'party' => 'Budi', 'principal_minor' => 200_000, 'account_id' => $this->account->id, 'start_date' => Carbon::today()]);
        $this->account->decrement('balance_minor', 200_000);
        DebtPayment::create(['debt_id' => $debt->id, 'amount_minor' => 50_000, 'paid_on' => Carbon::today(), 'account_id' => $this->account->id]);
        $this->account->increment('balance_minor', 50_000);

        Livewire::actingAs($this->user)->test(UtangPiutang::class)->call('deleteDebt', $debt->id);

        $this->assertSame(0, Debt::count());
        $this->assertSame(0, DebtPayment::count());
        $this->assertSame(1_000_000, $this->account->refresh()->balance_minor);
    }

    public function test_deleting_a_debt_does_not_touch_another_debt(): void
    {
        $toDelete = Debt::create(['user_id' => $this->user->id, 'direction' => 'piutang', 'party' => 'Budi', 'principal_minor' => 100_000, 'start_date' => Carbon::today()]);
        $other = Debt::create(['user_id' => $this->user->id, 'direction' => 'utang', 'party' => 'Ayah', 'principal_minor' => 300_000, 'start_date' => Carbon::today()]);

        Livewire::actingAs($this->user)->test(UtangPiutang::class)->call('deleteDebt', $toDelete->id);

        $this->assertSame(1, Debt::count());
        $this->assertSame($other->id, Debt::first()->id);
    }

    // --- Ubah ---

    public function test_editing_shifts_party_due_date_note_and_collectible_without_changing_the_principal(): void
    {
        $debt = Debt::create(['user_id' => $this->user->id, 'direction' => 'piutang', 'party' => 'Budi', 'principal_minor' => 200_000, 'start_date' => Carbon::today()]);

        Livewire::actingAs($this->user)
            ->test(UtangPiutang::class)
            ->call('startEditDebt', $debt->id)
            ->set('editParty', 'Budi Santoso')
            ->set('editDueDate', Carbon::today()->addDays(30)->toDateString())
            ->set('editNote', 'untuk modal')
            ->set('editCollectible', false)
            ->call('updateDebt')
            ->assertSet('editError', null);

        $debt->refresh();
        $this->assertSame('Budi Santoso', $debt->party);
        $this->assertFalse($debt->collectible);
        $this->assertSame(200_000, $debt->principal_minor);
    }

    // --- Ringkasan dan daftar ---

    public function test_overview_sums_outstanding_receivable_and_payable_excluding_settled(): void
    {
        $lent = Debt::create(['user_id' => $this->user->id, 'direction' => 'piutang', 'party' => 'Budi', 'principal_minor' => 200_000, 'start_date' => Carbon::today()]);
        DebtPayment::create(['debt_id' => $lent->id, 'amount_minor' => 50_000, 'paid_on' => Carbon::today()]);
        Debt::create(['user_id' => $this->user->id, 'direction' => 'utang', 'party' => 'Ayah', 'principal_minor' => 300_000, 'start_date' => Carbon::today()]);
        $settled = Debt::create(['user_id' => $this->user->id, 'direction' => 'piutang', 'party' => 'Citra', 'principal_minor' => 100_000, 'start_date' => Carbon::today()]);
        DebtPayment::create(['debt_id' => $settled->id, 'amount_minor' => 100_000, 'paid_on' => Carbon::today()]);

        $result = Livewire::actingAs($this->user)->test(UtangPiutang::class);

        $this->assertSame(150_000, $result->viewData('receivable'));
        $this->assertSame(300_000, $result->viewData('payable'));
    }

    public function test_list_sorts_overdue_and_nearest_first_then_no_due_date_then_settled(): void
    {
        $overdue = Debt::create(['user_id' => $this->user->id, 'direction' => 'piutang', 'party' => 'B', 'principal_minor' => 1000, 'start_date' => Carbon::today(), 'due_date' => Carbon::today()->subDay()]);
        $noDue = Debt::create(['user_id' => $this->user->id, 'direction' => 'piutang', 'party' => 'A', 'principal_minor' => 1000, 'start_date' => Carbon::today()]);
        $settled = Debt::create(['user_id' => $this->user->id, 'direction' => 'piutang', 'party' => 'Z', 'principal_minor' => 1000, 'start_date' => Carbon::today()]);
        DebtPayment::create(['debt_id' => $settled->id, 'amount_minor' => 1000, 'paid_on' => Carbon::today()]);

        $ids = Livewire::actingAs($this->user)->test(UtangPiutang::class)->viewData('debts')->pluck('id')->all();

        $this->assertSame([$overdue->id, $noDue->id, $settled->id], $ids);
    }

    public function test_status_follows_distance_to_due_date(): void
    {
        $today = Carbon::today();
        $make = fn (?Carbon $due) => Debt::create(['user_id' => $this->user->id, 'direction' => 'piutang', 'party' => 'X', 'principal_minor' => 1000, 'start_date' => $today, 'due_date' => $due]);

        $this->assertSame('berjalan', $make(null)->statusOn($today));
        $this->assertSame('terlambat', $make($today->copy()->subDay())->statusOn($today));
        $this->assertSame('hari_ini', $make($today->copy())->statusOn($today));
        $this->assertSame('segera', $make($today->copy()->addDays(3))->statusOn($today));
        $this->assertSame('berjalan', $make($today->copy()->addDays(4))->statusOn($today));
    }

    public function test_payment_history_is_newest_first(): void
    {
        $debt = Debt::create(['user_id' => $this->user->id, 'direction' => 'piutang', 'party' => 'Budi', 'principal_minor' => 300_000, 'start_date' => Carbon::today()->subDays(10)]);
        DebtPayment::create(['debt_id' => $debt->id, 'amount_minor' => 50_000, 'paid_on' => Carbon::today()->subDays(5)]);
        DebtPayment::create(['debt_id' => $debt->id, 'amount_minor' => 50_000, 'paid_on' => Carbon::today()]);

        $this->assertSame(
            [Carbon::today()->toDateString(), Carbon::today()->subDays(5)->toDateString()],
            $debt->payments->map(fn ($p) => $p->paid_on->toDateString())->all(),
        );
    }

    public function test_cannot_manage_another_users_debt(): void
    {
        $otherUser = User::factory()->create();
        $otherDebt = Debt::create(['user_id' => $otherUser->id, 'direction' => 'piutang', 'party' => 'X', 'principal_minor' => 1000, 'start_date' => Carbon::today()]);

        Livewire::actingAs($this->user)
            ->test(UtangPiutang::class)
            ->call('deleteDebt', $otherDebt->id)
            ->assertStatus(404);

        $this->assertSame(1, Debt::count());
    }
}
