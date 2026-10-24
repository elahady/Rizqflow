<?php

namespace Tests\Feature;

use App\Livewire\Transaksi;
use App\Models\Account;
use App\Models\Transaction;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Livewire\Livewire;
use Tests\TestCase;

class TransaksiSearchTest extends TestCase
{
    use RefreshDatabase;

    private User $user;

    private Account $account;

    protected function setUp(): void
    {
        parent::setUp();

        $this->travelTo('2026-10-24 10:00:00');

        $this->user = User::factory()->create();
        $this->account = Account::create(['user_id' => $this->user->id, 'name' => 'Bank Jago', 'type' => 'bank', 'sort_order' => 1]);
    }

    private function tx(string $type, int $amount, ?string $note, string $date): Transaction
    {
        return Transaction::create([
            'user_id' => $this->user->id, 'account_id' => $this->account->id, 'type' => $type,
            'amount_minor' => $amount, 'note' => $note, 'occurred_on' => $date,
        ]);
    }

    public function test_searching_by_note_finds_a_matching_transaction(): void
    {
        $this->tx('pengeluaran', 150_000, 'Belanja pasar', '2026-10-01');
        $this->tx('pengeluaran', 50_000, 'Token listrik', '2026-10-02');

        Livewire::actingAs($this->user)
            ->test(Transaksi::class)
            ->set('search', 'pasar')
            ->assertSee('Belanja pasar')
            ->assertDontSee('Token listrik');
    }

    public function test_searching_by_amount_finds_a_matching_transaction(): void
    {
        $this->tx('pengeluaran', 150_000, 'Belanja mingguan', '2026-10-01');
        $this->tx('pengeluaran', 50_000, 'Token listrik', '2026-10-02');

        Livewire::actingAs($this->user)
            ->test(Transaksi::class)
            ->set('search', '150000')
            ->assertSee('Belanja mingguan')
            ->assertDontSee('Token listrik');
    }

    public function test_quick_filter_shows_only_income_or_only_expense(): void
    {
        $this->tx('pemasukan', 8_500_000, 'Gaji September', '2026-10-01');
        $this->tx('pengeluaran', 150_000, 'Belanja mingguan', '2026-10-02');

        Livewire::actingAs($this->user)
            ->test(Transaksi::class)
            ->set('jenis', 'pemasukan')
            ->assertSee('Gaji September')
            ->assertDontSee('Belanja mingguan');
    }

    public function test_search_works_across_periods_and_years_and_the_date_includes_the_year(): void
    {
        $this->tx('pengeluaran', 150_000, 'Belanja lama', '2024-10-01');
        $this->tx('pengeluaran', 150_000, 'Belanja baru', '2026-10-01');

        $component = Livewire::actingAs($this->user)->test(Transaksi::class)->set('search', 'belanja');

        $component->assertSee('Belanja lama')->assertSee('Belanja baru');
        $component->assertSee('Hasil pencarian di semua periode');
        $component->assertSee('1 Okt 2024')->assertSee('1 Okt 2026');
    }

    public function test_empty_state_message_differs_when_filtering_versus_truly_empty(): void
    {
        Livewire::actingAs($this->user)
            ->test(Transaksi::class)
            ->assertSee('Belum ada transaksi di Oktober 2026.');

        $this->tx('pengeluaran', 150_000, 'Belanja pasar', '2026-10-01');

        Livewire::actingAs($this->user)
            ->test(Transaksi::class)
            ->set('search', 'tidak ada yang cocok sama sekali')
            ->assertSee('Tidak ada transaksi yang cocok.');
    }
}
