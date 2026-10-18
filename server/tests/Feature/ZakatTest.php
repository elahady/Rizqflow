<?php

namespace Tests\Feature;

use App\Livewire\Zakat;
use App\Models\Account;
use App\Models\Category;
use App\Models\Room;
use App\Models\Transaction;
use App\Models\User;
use App\Models\ZakatProfile;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Livewire\Livewire;
use Tests\TestCase;

class ZakatTest extends TestCase
{
    use RefreshDatabase;

    private User $user;

    private Room $memberi;

    private Category $zakatMal;

    private Account $account;

    protected function setUp(): void
    {
        parent::setUp();

        $this->user = User::factory()->create();
        $this->memberi = Room::create(['user_id' => $this->user->id, 'name' => 'Memberi', 'type' => 'menunaikan', 'slot' => 1, 'sort_order' => 1]);
        $this->zakatMal = Category::create(['room_id' => $this->memberi->id, 'name' => 'Zakat mal', 'is_system' => true, 'sort_order' => 1]);
        $this->account = Account::create(['user_id' => $this->user->id, 'name' => 'Bank Jago', 'type' => 'bank', 'balance_minor' => 10_000_000, 'sort_order' => 1]);
    }

    // --- Profil harta ---

    public function test_creating_the_first_profile(): void
    {
        Livewire::actingAs($this->user)
            ->test(Zakat::class)
            ->call('startEditProfile')
            ->set('name', 'Utama')
            ->set('netWealth', '200000000')
            ->set('nisab', '141100000')
            ->call('saveProfile')
            ->assertSet('profileError', null);

        $profile = ZakatProfile::first();
        $this->assertSame('Utama', $profile->name);
        $this->assertSame(200_000_000, $profile->net_wealth_minor);
        $this->assertSame(141_100_000, $profile->nisab_minor);
        $this->assertSame('haul_berjalan', $profile->status);
    }

    public function test_profile_without_a_name_shows_an_error(): void
    {
        Livewire::actingAs($this->user)
            ->test(Zakat::class)
            ->call('startEditProfile')
            ->set('name', '  ')
            ->call('saveProfile')
            ->assertSet('profileError', 'Nama profil wajib diisi.');

        $this->assertSame(0, ZakatProfile::count());
    }

    public function test_wealth_below_nisab_sets_status_belum_nisab(): void
    {
        Livewire::actingAs($this->user)
            ->test(Zakat::class)
            ->call('startEditProfile')
            ->set('netWealth', '50000000')
            ->set('nisab', '141100000')
            ->call('saveProfile');

        $this->assertSame('belum_nisab', ZakatProfile::first()->status);
    }

    public function test_haul_day_reaching_total_sets_status_haul_genap(): void
    {
        Livewire::actingAs($this->user)
            ->test(Zakat::class)
            ->call('startEditProfile')
            ->set('netWealth', '200000000')
            ->set('nisab', '141100000')
            ->set('haulDay', '354')
            ->set('haulTotalDays', '354')
            ->call('saveProfile');

        $this->assertSame('haul_genap', ZakatProfile::first()->status);
    }

    public function test_editing_an_existing_profile_updates_it_in_place(): void
    {
        $profile = ZakatProfile::create(['user_id' => $this->user->id, 'name' => 'Utama', 'net_wealth_minor' => 100_000_000, 'nisab_minor' => 141_100_000, 'status' => 'belum_nisab']);

        Livewire::actingAs($this->user)
            ->test(Zakat::class)
            ->call('startEditProfile')
            ->set('netWealth', '200000000')
            ->call('saveProfile');

        $this->assertSame(1, ZakatProfile::count());
        $this->assertSame(200_000_000, $profile->refresh()->net_wealth_minor);
        $this->assertSame('haul_berjalan', $profile->status);
    }

    // --- Tunaikan zakat ---

    public function test_paying_zakat_creates_an_expense_and_moves_the_account_balance(): void
    {
        ZakatProfile::create(['user_id' => $this->user->id, 'name' => 'Utama', 'net_wealth_minor' => 200_000_000, 'nisab_minor' => 141_100_000, 'status' => 'haul_berjalan']);

        Livewire::actingAs($this->user)
            ->test(Zakat::class)
            ->call('startPayZakat')
            ->assertSet('zakatAmount', '5000000')
            ->set('zakatAccountId', $this->account->id)
            ->call('payZakat')
            ->assertSet('payError', null);

        $tx = Transaction::first();
        $this->assertSame('pengeluaran', $tx->type);
        $this->assertSame(5_000_000, $tx->amount_minor);
        $this->assertSame($this->zakatMal->id, $tx->category_id);
        $this->assertSame($this->memberi->id, $tx->room_id);
        $this->assertSame(10_000_000 - 5_000_000, $this->account->refresh()->balance_minor);
    }

    public function test_paying_zakat_starts_a_new_haul(): void
    {
        $profile = ZakatProfile::create([
            'user_id' => $this->user->id, 'name' => 'Utama', 'net_wealth_minor' => 200_000_000, 'nisab_minor' => 141_100_000,
            'status' => 'haul_genap', 'haul_day' => 354, 'haul_total_days' => 354, 'haul_due_label' => '1 Muharram 1449',
        ]);

        Livewire::actingAs($this->user)
            ->test(Zakat::class)
            ->call('startPayZakat')
            ->set('zakatAccountId', $this->account->id)
            ->call('payZakat');

        $profile->refresh();
        $this->assertSame(0, $profile->haul_day);
        $this->assertNull($profile->haul_due_label);
        $this->assertSame('haul_berjalan', $profile->status);
        $this->assertNotNull($profile->haul_started_label);
    }

    public function test_paying_zakat_without_an_account_shows_an_error(): void
    {
        ZakatProfile::create(['user_id' => $this->user->id, 'name' => 'Utama', 'net_wealth_minor' => 200_000_000, 'nisab_minor' => 141_100_000, 'status' => 'haul_berjalan']);

        Livewire::actingAs($this->user)
            ->test(Zakat::class)
            ->call('startPayZakat')
            ->call('payZakat')
            ->assertSet('payError', 'Pilih akun sumber.');

        $this->assertSame(0, Transaction::count());
    }

    public function test_paying_zakat_without_a_zakat_mal_category_shows_an_error(): void
    {
        $this->zakatMal->delete();
        ZakatProfile::create(['user_id' => $this->user->id, 'name' => 'Utama', 'net_wealth_minor' => 200_000_000, 'nisab_minor' => 141_100_000, 'status' => 'haul_berjalan']);

        Livewire::actingAs($this->user)
            ->test(Zakat::class)
            ->call('startPayZakat')
            ->set('zakatAccountId', $this->account->id)
            ->call('payZakat')
            ->assertSet('payError', 'Ruang Memberi atau kategori Zakat mal belum ada.');

        $this->assertSame(0, Transaction::count());
    }

    public function test_page_shows_empty_state_without_a_profile(): void
    {
        $this->actingAs($this->user)
            ->get(route('zakat'))
            ->assertOk()
            ->assertSee('Belum ada profil harta');
    }
}
