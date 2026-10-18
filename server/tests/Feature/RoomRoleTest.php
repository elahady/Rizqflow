<?php

namespace Tests\Feature;

use App\Livewire\Ruang;
use App\Models\Account;
use App\Models\Category;
use App\Models\Room;
use App\Models\RoomRole;
use App\Models\Transaction;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Carbon;
use Livewire\Livewire;
use Tests\TestCase;

class RoomRoleTest extends TestCase
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
        $this->room = Room::create(['user_id' => $this->user->id, 'name' => 'Diri', 'type' => 'menumbuhkan', 'slot' => 2, 'sort_order' => 2, 'budget_minor' => 0, 'used_minor' => 0]);
        $this->category = Category::create(['room_id' => $this->room->id, 'name' => 'Investasi', 'sort_order' => 1]);
        $this->account = Account::create(['user_id' => $this->user->id, 'name' => 'Bank Jago', 'type' => 'bank', 'balance_minor' => 10_000_000, 'sort_order' => 1]);
    }

    // --- Pro gate ---

    public function test_a_free_user_cannot_assign_a_role(): void
    {
        Livewire::actingAs($this->user)
            ->test(Ruang::class, ['room' => $this->room])
            ->call('startPickRole', 'trader')
            ->assertSet('roleError', 'Sistem per peran (Trader/Investor) adalah fitur Pro.')
            ->assertSet('showRoleForm', false);

        $this->assertSame(0, RoomRole::count());
    }

    // --- Trader ---

    public function test_a_pro_user_can_assign_the_trader_role(): void
    {
        $this->user->update(['plans' => ['pro']]);

        Livewire::actingAs($this->user)
            ->test(Ruang::class, ['room' => $this->room])
            ->call('startPickRole', 'trader')
            ->set('capital', '20000000')
            ->set('riskPercent', '2')
            ->call('saveRole')
            ->assertSet('roleError', null);

        $role = RoomRole::first();
        $this->assertSame('trader', $role->kind);
        $this->assertSame(20_000_000, $role->capital_minor);
        $this->assertSame(200, $role->risk_bp);
        $this->assertSame(400_000, $role->maxRiskPerTradeMinor());
    }

    public function test_trader_risk_above_fifty_percent_is_rejected(): void
    {
        $this->user->update(['plans' => ['pro']]);

        Livewire::actingAs($this->user)
            ->test(Ruang::class, ['room' => $this->room])
            ->call('startPickRole', 'trader')
            ->set('capital', '1000000')
            ->set('riskPercent', '51')
            ->call('saveRole')
            ->assertSet('roleError', 'Risiko per trade 0,01% sampai 50%.');

        $this->assertSame(0, RoomRole::count());
    }

    // --- Investor / DCA ---

    public function test_a_pro_user_can_assign_the_investor_role(): void
    {
        $this->user->update(['plans' => ['pro']]);

        Livewire::actingAs($this->user)
            ->test(Ruang::class, ['room' => $this->room])
            ->call('startPickRole', 'investor')
            ->set('dcaAmount', '500000')
            ->set('dcaDay', '10')
            ->set('dcaAccountId', $this->account->id)
            ->set('dcaCategoryId', $this->category->id)
            ->call('saveRole')
            ->assertSet('roleError', null);

        $role = RoomRole::first();
        $this->assertSame('investor', $role->kind);
        $this->assertSame(500_000, $role->dca_amount_minor);
        $this->assertSame(10, $role->dca_day_of_month);
    }

    public function test_dca_day_outside_one_to_28_is_rejected(): void
    {
        $this->user->update(['plans' => ['pro']]);

        Livewire::actingAs($this->user)
            ->test(Ruang::class, ['room' => $this->room])
            ->call('startPickRole', 'investor')
            ->set('dcaAmount', '500000')
            ->set('dcaDay', '29')
            ->set('dcaAccountId', $this->account->id)
            ->set('dcaCategoryId', $this->category->id)
            ->call('saveRole')
            ->assertSet('roleError', 'Tanggal DCA 1 sampai 28.');

        $this->assertSame(0, RoomRole::count());
    }

    public function test_dca_status_is_due_once_the_day_has_passed_and_upcoming_before(): void
    {
        $role = RoomRole::create([
            'room_id' => $this->room->id, 'kind' => 'investor', 'dca_amount_minor' => 500_000, 'dca_day_of_month' => 10,
            'dca_account_id' => $this->account->id, 'dca_category_id' => $this->category->id,
        ]);

        $this->assertSame('jatuh_tempo', $role->dcaStatusOn(Carbon::parse('2026-10-10')));
        $this->assertSame('akan_datang', $role->dcaStatusOn(Carbon::parse('2026-10-05')));
    }

    public function test_recording_dca_now_creates_an_expense_moves_the_balance_and_marks_the_month_done(): void
    {
        $role = RoomRole::create([
            'room_id' => $this->room->id, 'kind' => 'investor', 'dca_amount_minor' => 500_000, 'dca_day_of_month' => 10,
            'dca_account_id' => $this->account->id, 'dca_category_id' => $this->category->id,
        ]);

        Livewire::actingAs($this->user)
            ->test(Ruang::class, ['room' => $this->room])
            ->call('recordDcaNow');

        $tx = Transaction::first();
        $this->assertSame('pengeluaran', $tx->type);
        $this->assertSame(500_000, $tx->amount_minor);
        $this->assertSame($this->room->id, $tx->room_id);
        $this->assertSame(10_000_000 - 500_000, $this->account->refresh()->balance_minor);
        $this->assertSame('selesai', $role->refresh()->dcaStatusOn(Carbon::today()));
    }

    // --- Lepas peran ---

    public function test_removing_a_role_keeps_previously_recorded_transactions(): void
    {
        $role = RoomRole::create([
            'room_id' => $this->room->id, 'kind' => 'investor', 'dca_amount_minor' => 500_000, 'dca_day_of_month' => 10,
            'dca_account_id' => $this->account->id, 'dca_category_id' => $this->category->id,
        ]);
        Livewire::actingAs($this->user)->test(Ruang::class, ['room' => $this->room])->call('recordDcaNow');

        Livewire::actingAs($this->user)->test(Ruang::class, ['room' => $this->room])->call('removeRole');

        $this->assertSame(0, RoomRole::count());
        $this->assertSame(1, Transaction::count());
    }

    public function test_memberi_room_has_no_role_card(): void
    {
        $memberi = Room::create(['user_id' => $this->user->id, 'name' => 'Memberi', 'type' => 'menunaikan', 'slot' => 1, 'sort_order' => 1]);

        $this->actingAs($this->user)
            ->get(route('ruang.detail', $memberi))
            ->assertOk()
            ->assertDontSee('Trader');
    }
}
