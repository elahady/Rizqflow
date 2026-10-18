<?php

namespace Tests\Feature;

use App\Livewire\Tagihan;
use App\Models\Account;
use App\Models\Bill;
use App\Models\Category;
use App\Models\Room;
use App\Models\Transaction;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Carbon;
use Livewire\Livewire;
use Tests\TestCase;

class BillTest extends TestCase
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
        $this->category = Category::create(['room_id' => $this->room->id, 'name' => 'Listrik dan air', 'sort_order' => 1]);
        $this->account = Account::create(['user_id' => $this->user->id, 'name' => 'Bank Jago', 'type' => 'bank', 'balance_minor' => 1_000_000, 'sort_order' => 1]);
    }

    // --- Kelola ---

    public function test_creating_a_one_off_bill(): void
    {
        Livewire::actingAs($this->user)
            ->test(Tagihan::class)
            ->call('startAdd')
            ->set('name', 'Pajak motor')
            ->set('amount', '350000')
            ->set('roomId', $this->room->id)
            ->set('categoryId', $this->category->id)
            ->set('accountId', $this->account->id)
            ->set('dueDate', Carbon::today()->toDateString())
            ->call('save')
            ->assertSet('error', null);

        $bill = Bill::first();
        $this->assertNull($bill->frequency);
        $this->assertSame(350_000, $bill->amount_minor);
        $this->assertSame(0, $bill->paid_count);
    }

    public function test_overdue_due_date_is_allowed_on_create(): void
    {
        Livewire::actingAs($this->user)
            ->test(Tagihan::class)
            ->call('startAdd')
            ->set('name', 'Listrik')
            ->set('amount', '200000')
            ->set('roomId', $this->room->id)
            ->set('categoryId', $this->category->id)
            ->set('accountId', $this->account->id)
            ->set('frequency', 'bulanan')
            ->set('dueDate', Carbon::today()->subDays(5)->toDateString())
            ->call('save')
            ->assertSet('error', null);

        $bill = Bill::first();
        $this->assertSame('terlambat', $bill->statusOn(Carbon::today()));
    }

    public function test_category_from_another_room_is_rejected(): void
    {
        $otherRoom = Room::create(['user_id' => $this->user->id, 'name' => 'Diri', 'type' => 'menumbuhkan', 'slot' => 2, 'sort_order' => 2]);
        $otherCategory = Category::create(['room_id' => $otherRoom->id, 'name' => 'Investasi', 'sort_order' => 1]);

        Livewire::actingAs($this->user)
            ->test(Tagihan::class)
            ->call('startAdd')
            ->set('name', 'Listrik')
            ->set('amount', '200000')
            ->set('roomId', $this->room->id)
            ->set('categoryId', $otherCategory->id)
            ->set('accountId', $this->account->id)
            ->call('save')
            ->assertSet('error', 'Pilih kategori yang sesuai dengan ruang.');

        $this->assertSame(0, Bill::count());
    }

    public function test_invalid_installment_count_is_rejected(): void
    {
        Livewire::actingAs($this->user)
            ->test(Tagihan::class)
            ->call('startAdd')
            ->set('name', 'Cicilan motor')
            ->set('amount', '500000')
            ->set('roomId', $this->room->id)
            ->set('categoryId', $this->category->id)
            ->set('accountId', $this->account->id)
            ->set('frequency', 'bulanan')
            ->set('totalInstallments', '3')
            ->set('alreadyPaid', '3')
            ->call('save')
            ->assertSet('error', 'Jumlah cicilan minimal 1, hanya untuk tagihan berulang, dan harus lebih dari yang sudah dibayar.');

        $this->assertSame(0, Bill::count());
    }

    public function test_already_paid_installments_before_entering_the_app_are_counted(): void
    {
        Livewire::actingAs($this->user)
            ->test(Tagihan::class)
            ->call('startAdd')
            ->set('name', 'Cicilan laptop')
            ->set('amount', '500000')
            ->set('roomId', $this->room->id)
            ->set('categoryId', $this->category->id)
            ->set('accountId', $this->account->id)
            ->set('frequency', 'bulanan')
            ->set('totalInstallments', '12')
            ->set('alreadyPaid', '4')
            ->call('save')
            ->assertSet('error', null);

        $bill = Bill::first();
        $this->assertSame(4, $bill->paid_count);
        $this->assertSame(8, $bill->remainingInstallments());
    }

    // --- Bayar ---

    public function test_paying_records_an_expense_and_advances_the_due_date(): void
    {
        $bill = Bill::create([
            'user_id' => $this->user->id, 'name' => 'Listrik', 'amount_minor' => 200_000,
            'account_id' => $this->account->id, 'room_id' => $this->room->id, 'category_id' => $this->category->id,
            'frequency' => 'bulanan', 'start_date' => Carbon::today(), 'next_due' => Carbon::today(), 'active' => true,
        ]);

        Livewire::actingAs($this->user)
            ->test(Tagihan::class)
            ->call('startPay', $bill->id)
            ->assertSet('payAmount', '200000')
            ->call('pay')
            ->assertSet('payingId', null);

        $tx = Transaction::first();
        $this->assertSame('pengeluaran', $tx->type);
        $this->assertSame(200_000, $tx->amount_minor);
        $this->assertSame($bill->id, $tx->bill_id);
        $this->assertSame(1_000_000 - 200_000, $this->account->refresh()->balance_minor);

        $bill->refresh();
        $this->assertSame(1, $bill->paid_count);
        $this->assertTrue($bill->next_due->equalTo(Carbon::today()->addMonthsNoOverflow()));
    }

    public function test_paid_amount_may_differ_from_the_bills_usual_amount(): void
    {
        $bill = Bill::create([
            'user_id' => $this->user->id, 'name' => 'Listrik', 'amount_minor' => 200_000,
            'account_id' => $this->account->id, 'room_id' => $this->room->id, 'category_id' => $this->category->id,
            'frequency' => 'bulanan', 'start_date' => Carbon::today(), 'next_due' => Carbon::today(), 'active' => true,
        ]);

        Livewire::actingAs($this->user)
            ->test(Tagihan::class)
            ->call('startPay', $bill->id)
            ->set('payAmount', '235000')
            ->call('pay');

        $this->assertSame(235_000, Transaction::first()->amount_minor);
        $this->assertSame(200_000, $bill->refresh()->amount_minor);
    }

    public function test_monthly_due_date_starting_on_the_31st_returns_to_31_after_a_short_month(): void
    {
        $bill = Bill::create([
            'user_id' => $this->user->id, 'name' => 'Cicilan', 'amount_minor' => 500_000,
            'account_id' => $this->account->id, 'room_id' => $this->room->id, 'category_id' => $this->category->id,
            'frequency' => 'bulanan', 'start_date' => Carbon::create(2026, 1, 31), 'next_due' => Carbon::create(2026, 1, 31), 'active' => true,
        ]);

        Livewire::actingAs($this->user)->test(Tagihan::class)->call('startPay', $bill->id)->call('pay');

        $this->assertSame('2026-02-28', $bill->refresh()->next_due->toDateString());
    }

    public function test_a_one_off_bill_is_finished_after_being_paid_once_and_cannot_be_paid_again(): void
    {
        $bill = Bill::create([
            'user_id' => $this->user->id, 'name' => 'Pajak motor', 'amount_minor' => 350_000,
            'account_id' => $this->account->id, 'room_id' => $this->room->id, 'category_id' => $this->category->id,
            'frequency' => null, 'start_date' => Carbon::today(), 'next_due' => Carbon::today(), 'active' => true,
        ]);

        Livewire::actingAs($this->user)->test(Tagihan::class)->call('startPay', $bill->id)->call('pay');

        $bill->refresh();
        $this->assertTrue($bill->isFinished());
        $this->assertSame('lunas', $bill->statusOn(Carbon::today()));

        Livewire::actingAs($this->user)->test(Tagihan::class)->call('startPay', $bill->id)->call('pay');
        $this->assertSame(1, Transaction::count());
    }

    public function test_installments_stop_at_the_last_one_and_the_note_mentions_the_sequence(): void
    {
        $bill = Bill::create([
            'user_id' => $this->user->id, 'name' => 'Cicilan motor', 'amount_minor' => 500_000,
            'account_id' => $this->account->id, 'room_id' => $this->room->id, 'category_id' => $this->category->id,
            'frequency' => 'bulanan', 'total_installments' => 2, 'paid_count' => 1,
            'start_date' => Carbon::today(), 'next_due' => Carbon::today(), 'active' => true,
        ]);

        Livewire::actingAs($this->user)->test(Tagihan::class)->call('startPay', $bill->id)->call('pay');

        $bill->refresh();
        $this->assertTrue($bill->isFinished());
        $this->assertSame(2, $bill->paid_count);
        $this->assertSame('Cicilan motor (2/2)', Transaction::first()->note);
    }

    public function test_unlimited_installments_never_finish(): void
    {
        $bill = Bill::create([
            'user_id' => $this->user->id, 'name' => 'Listrik', 'amount_minor' => 200_000,
            'account_id' => $this->account->id, 'room_id' => $this->room->id, 'category_id' => $this->category->id,
            'frequency' => 'bulanan', 'paid_count' => 500,
            'start_date' => Carbon::today(), 'next_due' => Carbon::today(), 'active' => true,
        ]);

        $this->assertFalse($bill->isFinished());
    }

    public function test_paying_a_bill_whose_room_is_archived_is_rejected(): void
    {
        $this->room->update(['archived' => true]);
        $bill = Bill::create([
            'user_id' => $this->user->id, 'name' => 'Listrik', 'amount_minor' => 200_000,
            'account_id' => $this->account->id, 'room_id' => $this->room->id, 'category_id' => $this->category->id,
            'frequency' => 'bulanan', 'start_date' => Carbon::today(), 'next_due' => Carbon::today(), 'active' => true,
        ]);

        Livewire::actingAs($this->user)
            ->test(Tagihan::class)
            ->call('startPay', $bill->id)
            ->call('pay')
            ->assertSet('payError', 'Akun, ruang, atau kategori tagihan ini sudah diarsipkan.');

        $this->assertSame(0, Transaction::count());
        $this->assertSame(0, $bill->refresh()->paid_count);
    }

    // --- Jeda, hapus, status ---

    public function test_pausing_does_not_shift_the_due_date_and_a_paused_bill_is_not_overdue(): void
    {
        $bill = Bill::create([
            'user_id' => $this->user->id, 'name' => 'Listrik', 'amount_minor' => 200_000,
            'account_id' => $this->account->id, 'room_id' => $this->room->id, 'category_id' => $this->category->id,
            'frequency' => 'bulanan', 'start_date' => Carbon::today()->subDays(10), 'next_due' => Carbon::today()->subDays(10), 'active' => true,
        ]);

        Livewire::actingAs($this->user)->test(Tagihan::class)->call('pause', $bill->id);

        $bill->refresh();
        $this->assertFalse($bill->active);
        $this->assertTrue($bill->next_due->equalTo(Carbon::today()->subDays(10)));
        $this->assertSame('dijeda', $bill->statusOn(Carbon::today()));
    }

    public function test_deleting_a_bill_keeps_its_recorded_expenses(): void
    {
        $bill = Bill::create([
            'user_id' => $this->user->id, 'name' => 'Listrik', 'amount_minor' => 200_000,
            'account_id' => $this->account->id, 'room_id' => $this->room->id, 'category_id' => $this->category->id,
            'frequency' => 'bulanan', 'start_date' => Carbon::today(), 'next_due' => Carbon::today(), 'active' => true,
        ]);
        Livewire::actingAs($this->user)->test(Tagihan::class)->call('startPay', $bill->id)->call('pay');

        Livewire::actingAs($this->user)->test(Tagihan::class)->call('delete', $bill->id);

        $this->assertSame(0, Bill::count());
        $this->assertSame(1, Transaction::count());
        $this->assertNull(Transaction::first()->bill_id);
    }

    public function test_list_sorts_overdue_first_then_paused_then_finished(): void
    {
        $overdue = Bill::create(['user_id' => $this->user->id, 'name' => 'B terlambat', 'amount_minor' => 1000, 'account_id' => $this->account->id, 'room_id' => $this->room->id, 'category_id' => $this->category->id, 'start_date' => Carbon::today()->subDays(2), 'next_due' => Carbon::today()->subDays(2)]);
        $paused = Bill::create(['user_id' => $this->user->id, 'name' => 'A dijeda', 'amount_minor' => 1000, 'account_id' => $this->account->id, 'room_id' => $this->room->id, 'category_id' => $this->category->id, 'start_date' => Carbon::today(), 'next_due' => Carbon::today(), 'active' => false]);
        $finished = Bill::create(['user_id' => $this->user->id, 'name' => 'Z lunas', 'amount_minor' => 1000, 'account_id' => $this->account->id, 'room_id' => $this->room->id, 'category_id' => $this->category->id, 'start_date' => Carbon::today(), 'next_due' => Carbon::today(), 'paid_count' => 1]);

        $ids = Livewire::actingAs($this->user)->test(Tagihan::class)->viewData('bills')->pluck('id')->all();

        $this->assertSame([$overdue->id, $paused->id, $finished->id], $ids);
    }

    public function test_row_marked_unusable_when_its_account_is_archived(): void
    {
        $this->account->update(['archived' => true]);
        $bill = Bill::create([
            'user_id' => $this->user->id, 'name' => 'Listrik', 'amount_minor' => 200_000,
            'account_id' => $this->account->id, 'room_id' => $this->room->id, 'category_id' => $this->category->id,
            'start_date' => Carbon::today(), 'next_due' => Carbon::today(),
        ]);

        $this->assertFalse($bill->isUsable());
    }

    public function test_status_follows_distance_to_due_date(): void
    {
        $today = Carbon::today();
        $make = fn (Carbon $due) => Bill::create(['user_id' => $this->user->id, 'name' => 'X', 'amount_minor' => 1000, 'account_id' => $this->account->id, 'room_id' => $this->room->id, 'category_id' => $this->category->id, 'start_date' => $due, 'next_due' => $due, 'active' => true]);

        $this->assertSame('terlambat', $make($today->copy()->subDay())->statusOn($today));
        $this->assertSame('hari_ini', $make($today->copy())->statusOn($today));
        $this->assertSame('segera', $make($today->copy()->addDays(3))->statusOn($today));
        $this->assertSame('mendatang', $make($today->copy()->addDays(4))->statusOn($today));
    }

    public function test_cannot_manage_another_users_bill(): void
    {
        $otherUser = User::factory()->create();
        $otherAccount = Account::create(['user_id' => $otherUser->id, 'name' => 'Tunai', 'type' => 'tunai', 'sort_order' => 1]);
        $otherRoom = Room::create(['user_id' => $otherUser->id, 'name' => 'Lain', 'type' => 'mencukupi', 'slot' => 1, 'sort_order' => 1]);
        $otherCategory = Category::create(['room_id' => $otherRoom->id, 'name' => 'Lain-lain', 'sort_order' => 1]);
        $otherBill = Bill::create(['user_id' => $otherUser->id, 'name' => 'X', 'amount_minor' => 1000, 'account_id' => $otherAccount->id, 'room_id' => $otherRoom->id, 'category_id' => $otherCategory->id, 'start_date' => Carbon::today(), 'next_due' => Carbon::today()]);

        Livewire::actingAs($this->user)
            ->test(Tagihan::class)
            ->call('delete', $otherBill->id)
            ->assertStatus(404);

        $this->assertSame(1, Bill::count());
    }
}
