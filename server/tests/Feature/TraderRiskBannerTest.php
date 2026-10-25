<?php

namespace Tests\Feature;

use App\Livewire\Transaksi;
use App\Models\Account;
use App\Models\Category;
use App\Models\Room;
use App\Models\RoomRole;
use App\Models\Transaction;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Livewire\Livewire;
use Tests\TestCase;

/**
 * Banner lembut batas risiko Trader di baris cepat Catat (Transaksi). Porting dari Android
 * (RoleService.riskWarning, banner catat_over_risk): hanya info, tidak pernah memblokir simpan.
 * Modal Rp 10 juta di 1% berarti batas Rp 100.000 per trade.
 */
class TraderRiskBannerTest extends TestCase
{
    use RefreshDatabase;

    private const BANNER = 'Melewati batas risiko Rp 100.000 per trade';

    private User $user;

    private Category $trader;

    private Category $plain;

    private Account $account;

    protected function setUp(): void
    {
        parent::setUp();

        $this->user = User::factory()->create(['plans' => ['pro']]);
        $this->account = Account::create(['user_id' => $this->user->id, 'name' => 'Bank Jago', 'type' => 'bank', 'balance_minor' => 10_000_000, 'sort_order' => 1]);

        $diri = Room::create(['user_id' => $this->user->id, 'name' => 'Diri', 'type' => 'menumbuhkan', 'slot' => 2, 'sort_order' => 2]);
        $this->trader = Category::create(['room_id' => $diri->id, 'name' => 'Trading', 'sort_order' => 1]);
        RoomRole::create(['room_id' => $diri->id, 'kind' => 'trader', 'capital_minor' => 10_000_000, 'risk_bp' => 100]);

        // Ruang tanpa peran: tidak pernah diberi banner, berapa pun nominalnya.
        $keluarga = Room::create(['user_id' => $this->user->id, 'name' => 'Keluarga', 'type' => 'mencukupi', 'slot' => 1, 'sort_order' => 1]);
        $this->plain = Category::create(['room_id' => $keluarga->id, 'name' => 'Belanja', 'sort_order' => 1]);
    }

    private function catat(string $amount, Category $category): \Livewire\Features\SupportTesting\Testable
    {
        return Livewire::actingAs($this->user)
            ->test(Transaksi::class)
            ->set('quickCategoryId', $category->id)
            ->set('quickAccountId', $this->account->id)
            ->set('quickAmount', $amount);
    }

    public function test_banner_appears_when_the_expense_passes_the_per_trade_limit(): void
    {
        $this->catat('1000000', $this->trader)
            ->assertSee(self::BANNER.' (kamu mencatat Rp 1.000.000). Tetap bisa disimpan.');
    }

    public function test_banner_does_not_appear_below_the_limit(): void
    {
        $this->catat('50000', $this->trader)->assertDontSee('Melewati batas risiko');
    }

    public function test_banner_does_not_appear_exactly_at_the_limit(): void
    {
        $this->catat('100000', $this->trader)->assertDontSee('Melewati batas risiko');
    }

    public function test_banner_does_not_block_saving_the_expense_above_the_limit(): void
    {
        $this->catat('1000000', $this->trader)
            ->set('quickNote', 'rugi trade')
            ->call('save')
            ->assertSet('quickError', null)
            ->assertSee('Tersimpan');

        $this->assertSame(1, Transaction::count());
        $this->assertSame(1_000_000, (int) Transaction::first()->amount_minor);
    }

    public function test_banner_does_not_appear_in_a_room_without_the_trader_role(): void
    {
        $this->catat('1000000', $this->plain)->assertDontSee('Melewati batas risiko');
    }

    public function test_banner_does_not_appear_for_an_investor_room(): void
    {
        $dca = Room::create(['user_id' => $this->user->id, 'name' => 'Investasi', 'type' => 'menumbuhkan', 'slot' => 3, 'sort_order' => 3]);
        RoomRole::create([
            'room_id' => $dca->id, 'kind' => 'investor', 'dca_amount_minor' => 500_000,
            'dca_day_of_month' => 5, 'dca_account_id' => $this->account->id,
            'dca_category_id' => Category::create(['room_id' => $dca->id, 'name' => 'DCA', 'sort_order' => 1])->id,
        ]);
        $category = Category::create(['room_id' => $dca->id, 'name' => 'Reksadana', 'sort_order' => 2]);

        $this->catat('1000000', $category)->assertDontSee('Melewati batas risiko');
    }

    public function test_banner_is_off_without_pro_even_if_the_trader_role_is_still_stored(): void
    {
        // Turun paket: peran tetap tersimpan, tetapi modulnya diam dan tidak memunculkan banner.
        $this->user->update(['plans' => []]);

        $this->catat('1000000', $this->trader)->assertDontSee('Melewati batas risiko');
        $this->assertSame(1, RoomRole::count());
    }
}
