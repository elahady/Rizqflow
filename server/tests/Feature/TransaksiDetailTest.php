<?php

namespace Tests\Feature;

use App\Livewire\TransaksiDetail;
use App\Models\Account;
use App\Models\Category;
use App\Models\Room;
use App\Models\Transaction;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Livewire\Livewire;
use Tests\TestCase;

class TransaksiDetailTest extends TestCase
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

    private function expense(int $amount = 150_000, ?Account $account = null): Transaction
    {
        $account ??= $this->account;

        return Transaction::create([
            'user_id' => $this->user->id,
            'room_id' => $this->room->id,
            'category_id' => $this->category->id,
            'account_id' => $account->id,
            'type' => 'pengeluaran',
            'amount_minor' => $amount,
            'category' => $this->category->name,
            'note' => 'Belanja pasar',
            'occurred_on' => now(),
        ]);
    }

    private function income(int $amount = 2_000_000, ?Account $account = null): Transaction
    {
        $account ??= $this->account;

        return Transaction::create([
            'user_id' => $this->user->id,
            'room_id' => null,
            'account_id' => $account->id,
            'type' => 'pemasukan',
            'amount_minor' => $amount,
            'category' => 'Gaji',
            'note' => 'Gaji September',
            'occurred_on' => now(),
        ]);
    }

    // --- Pengeluaran ---

    public function test_editing_an_expense_updates_its_fields_and_the_account_balance(): void
    {
        $tx = $this->expense(150_000);
        $diri = Room::create(['user_id' => $this->user->id, 'name' => 'Diri', 'type' => 'menumbuhkan', 'slot' => 2, 'sort_order' => 2]);
        $investasi = Category::create(['room_id' => $diri->id, 'name' => 'Investasi', 'sort_order' => 1]);

        Livewire::actingAs($this->user)
            ->test(TransaksiDetail::class, ['transaction' => $tx])
            ->call('startEdit')
            ->set('amount', '175000')
            ->set('roomId', $diri->id)
            ->set('categoryId', $investasi->id)
            ->set('note', 'dipindah')
            ->call('save')
            ->assertSet('error', null);

        $tx->refresh();
        $this->assertSame(175_000, $tx->amount_minor);
        $this->assertSame($diri->id, $tx->room_id);
        $this->assertSame($investasi->id, $tx->category_id);
        $this->assertSame('dipindah', $tx->note);
        // Nominal naik 25.000 (150.000 -> 175.000): saldo akun berkurang 25.000 lagi.
        $this->assertSame(1_000_000 - 25_000, $this->account->refresh()->balance_minor);
    }

    public function test_moving_an_expense_to_another_account_refunds_the_old_one(): void
    {
        $other = Account::create(['user_id' => $this->user->id, 'name' => 'Tunai', 'type' => 'tunai', 'balance_minor' => 50_000, 'sort_order' => 2]);
        $tx = $this->expense(150_000);

        Livewire::actingAs($this->user)
            ->test(TransaksiDetail::class, ['transaction' => $tx])
            ->call('startEdit')
            ->set('accountId', $other->id)
            ->call('save');

        // Akun lama direfund (pengeluaran tidak lagi menjadi tanggungannya), akun baru menanggungnya.
        $this->assertSame(1_000_000 + 150_000, $this->account->refresh()->balance_minor);
        $this->assertSame(50_000 - 150_000, $other->refresh()->balance_minor);
    }

    public function test_deleting_an_expense_refunds_the_account(): void
    {
        $tx = $this->expense(150_000);

        Livewire::actingAs($this->user)
            ->test(TransaksiDetail::class, ['transaction' => $tx])
            ->call('delete');

        $this->assertSame(0, Transaction::count());
        $this->assertSame(1_000_000 + 150_000, $this->account->refresh()->balance_minor);
    }

    public function test_rejects_invalid_input_and_leaves_data_unchanged(): void
    {
        $tx = $this->expense(150_000);

        Livewire::actingAs($this->user)
            ->test(TransaksiDetail::class, ['transaction' => $tx])
            ->call('startEdit')
            ->set('amount', '0')
            ->call('save')
            ->assertSet('error', 'Nominal wajib lebih dari nol.');

        Livewire::actingAs($this->user)
            ->test(TransaksiDetail::class, ['transaction' => $tx])
            ->call('startEdit')
            ->set('note', str_repeat('x', 201))
            ->call('save')
            ->assertSet('error', 'Catatan paling banyak 200 karakter.');

        $tx->refresh();
        $this->assertSame(150_000, $tx->amount_minor);
        // Validasi gagal dua kali: saldo tidak tersentuh sama sekali.
        $this->assertSame(1_000_000, $this->account->refresh()->balance_minor);
    }

    public function test_cannot_switch_to_an_archived_account(): void
    {
        $archived = Account::create(['user_id' => $this->user->id, 'name' => 'Lama', 'type' => 'tunai', 'archived' => true, 'sort_order' => 2]);
        $tx = $this->expense(150_000);

        Livewire::actingAs($this->user)
            ->test(TransaksiDetail::class, ['transaction' => $tx])
            ->call('startEdit')
            ->set('accountId', $archived->id)
            ->call('save')
            ->assertSet('error', 'Akun ini sudah diarsipkan — pilih akun lain.');

        $this->assertSame($this->account->id, $tx->refresh()->account_id);
    }

    public function test_archived_account_already_in_use_may_stay_unchanged(): void
    {
        $tx = $this->expense(150_000);
        $this->account->update(['archived' => true]);

        Livewire::actingAs($this->user)
            ->test(TransaksiDetail::class, ['transaction' => $tx])
            ->call('startEdit')
            ->set('note', 'catatan baru')
            ->call('save')
            ->assertSet('error', null);

        $this->assertSame('catatan baru', $tx->refresh()->note);
    }

    // --- Pemasukan ---

    public function test_editing_income_updates_amount_source_and_account_balance(): void
    {
        $tx = $this->income(2_000_000);

        Livewire::actingAs($this->user)
            ->test(TransaksiDetail::class, ['transaction' => $tx])
            ->call('startEdit')
            ->set('amount', '2500000')
            ->set('source', 'Usaha')
            ->call('save')
            ->assertSet('error', null);

        $tx->refresh();
        $this->assertSame(2_500_000, $tx->amount_minor);
        $this->assertSame('Usaha', $tx->category);
        // Nominal naik 500.000 (2.000.000 -> 2.500.000): saldo akun bertambah 500.000 lagi.
        $this->assertSame(1_000_000 + 500_000, $this->account->refresh()->balance_minor);
    }

    public function test_income_has_no_room_or_category_fields(): void
    {
        $tx = $this->income();

        $this->actingAs($this->user)
            ->get(route('transaksi.detail', $tx))
            ->assertOk()
            ->assertSee('Sumber')
            ->assertDontSee('wire:model.live="roomId"', false);
    }

    public function test_deleting_income_removes_the_amount_from_the_account(): void
    {
        $tx = $this->income(2_000_000);

        Livewire::actingAs($this->user)
            ->test(TransaksiDetail::class, ['transaction' => $tx])
            ->call('delete');

        $this->assertSame(0, Transaction::count());
        $this->assertSame(1_000_000 - 2_000_000, $this->account->refresh()->balance_minor);
    }

    // --- Kepemilikan ---

    public function test_cannot_view_another_users_transaction(): void
    {
        $otherUser = User::factory()->create();
        $otherAccount = Account::create(['user_id' => $otherUser->id, 'name' => 'Punya orang lain', 'type' => 'tunai', 'sort_order' => 1]);
        $otherTx = Transaction::create([
            'user_id' => $otherUser->id,
            'account_id' => $otherAccount->id,
            'type' => 'pemasukan',
            'amount_minor' => 1_000,
            'occurred_on' => now(),
        ]);

        $this->actingAs($this->user)
            ->get(route('transaksi.detail', $otherTx))
            ->assertForbidden();
    }

    public function test_page_shows_transaction_amount_and_can_be_reached_from_the_list(): void
    {
        $tx = $this->expense(150_000);

        $this->actingAs($this->user)
            ->get(route('transaksi'))
            ->assertOk()
            ->assertSee(route('transaksi.detail', $tx), false);

        $this->actingAs($this->user)
            ->get(route('transaksi.detail', $tx))
            ->assertOk()
            ->assertSee('Rp 150.000');
    }
}
