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

    // --- Multi-profil harta zakat (Pro) ---

    public function test_the_first_profile_is_free_even_without_pro(): void
    {
        Livewire::actingAs($this->user)
            ->test(Zakat::class)
            ->call('startAddProfile')
            ->assertSet('profileError', null)
            ->assertSet('editingProfile', true);
    }

    public function test_a_free_user_cannot_add_a_second_profile(): void
    {
        ZakatProfile::create(['user_id' => $this->user->id, 'name' => 'Utama']);

        Livewire::actingAs($this->user)
            ->test(Zakat::class)
            ->call('startAddProfile')
            ->assertSet('profileError', 'Profil harta lebih dari satu adalah fitur Pro.')
            ->assertSet('editingProfile', false);

        $this->assertSame(1, ZakatProfile::count());
    }

    public function test_a_pro_user_can_add_a_second_profile(): void
    {
        $this->user->update(['plans' => ['pro']]);
        ZakatProfile::create(['user_id' => $this->user->id, 'name' => 'Utama']);

        Livewire::actingAs($this->user)
            ->test(Zakat::class)
            ->call('startAddProfile')
            ->set('name', 'Istri')
            ->set('netWealth', '50000000')
            ->set('nisab', '141100000')
            ->call('saveProfile')
            ->assertSet('profileError', null);

        $this->assertSame(2, ZakatProfile::count());
    }

    public function test_selecting_a_profile_switches_the_displayed_haul_and_wealth(): void
    {
        $first = ZakatProfile::create(['user_id' => $this->user->id, 'name' => 'Utama', 'net_wealth_minor' => 200_000_000]);
        $second = ZakatProfile::create(['user_id' => $this->user->id, 'name' => 'Istri', 'net_wealth_minor' => 50_000_000]);

        Livewire::actingAs($this->user)
            ->test(Zakat::class)
            ->assertSet('selectedProfileId', $first->id)
            ->call('selectProfile', $second->id)
            ->assertSet('selectedProfileId', $second->id)
            ->assertSee('Rp 50.000.000');
    }

    public function test_the_last_active_profile_cannot_be_archived(): void
    {
        $profile = ZakatProfile::create(['user_id' => $this->user->id, 'name' => 'Utama']);

        Livewire::actingAs($this->user)
            ->test(Zakat::class)
            ->call('archiveProfile', $profile->id)
            ->assertSet('profileError', 'Profil harta aktif terakhir tidak bisa diarsipkan.');

        $this->assertFalse($profile->refresh()->archived);
    }

    public function test_archiving_one_of_several_profiles_switches_selection_to_another(): void
    {
        $first = ZakatProfile::create(['user_id' => $this->user->id, 'name' => 'Utama']);
        $second = ZakatProfile::create(['user_id' => $this->user->id, 'name' => 'Istri']);

        Livewire::actingAs($this->user)
            ->test(Zakat::class)
            ->call('selectProfile', $first->id)
            ->call('archiveProfile', $first->id)
            ->assertSet('selectedProfileId', $second->id);

        $this->assertTrue($first->refresh()->archived);
    }

    public function test_restoring_a_profile_makes_it_selectable_again(): void
    {
        $profile = ZakatProfile::create(['user_id' => $this->user->id, 'name' => 'Utama', 'archived' => true]);
        ZakatProfile::create(['user_id' => $this->user->id, 'name' => 'Istri']);

        Livewire::actingAs($this->user)->test(Zakat::class)->call('restoreProfile', $profile->id);

        $this->assertFalse($profile->refresh()->archived);
    }

    public function test_payment_note_mentions_the_profile_name_only_when_there_is_more_than_one(): void
    {
        $profile = ZakatProfile::create(['user_id' => $this->user->id, 'name' => 'Utama', 'net_wealth_minor' => 200_000_000]);

        Livewire::actingAs($this->user)
            ->test(Zakat::class)
            ->call('startPayZakat')
            ->set('zakatAccountId', $this->account->id)
            ->call('payZakat');

        $this->assertSame('Zakat mal', Transaction::first()->note);

        ZakatProfile::create(['user_id' => $this->user->id, 'name' => 'Istri']);

        Livewire::actingAs($this->user)
            ->test(Zakat::class)
            ->call('selectProfile', $profile->id)
            ->call('startPayZakat')
            ->set('zakatAccountId', $this->account->id)
            ->call('payZakat');

        $this->assertSame('Zakat mal — Utama', Transaction::latest('id')->first()->note);
    }

    public function test_cannot_select_or_archive_another_users_profile(): void
    {
        $otherUser = User::factory()->create();
        $otherProfile = ZakatProfile::create(['user_id' => $otherUser->id, 'name' => 'Lain']);
        ZakatProfile::create(['user_id' => $this->user->id, 'name' => 'Utama']);

        Livewire::actingAs($this->user)
            ->test(Zakat::class)
            ->call('selectProfile', $otherProfile->id)
            ->assertSet('selectedProfileId', fn ($id) => $id !== $otherProfile->id)
            ->call('archiveProfile', $otherProfile->id);

        $this->assertFalse($otherProfile->refresh()->archived);
    }
}
