<?php

namespace Tests\Feature;

use App\Livewire\Profil;
use App\Models\Room;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Hash;
use Laravel\Sanctum\PersonalAccessToken;
use Livewire\Livewire;
use Tests\TestCase;

/** Profil: hapus akun (Tahap 9), tetapkan/ubah kata sandi (masuk email+sandi web). */
class ProfilTest extends TestCase
{
    use RefreshDatabase;

    public function test_deleting_account_removes_user_and_related_data_and_logs_out(): void
    {
        $user = User::factory()->create(['google_sub' => '123']);
        $room = Room::create(['user_id' => $user->id, 'name' => 'Keluarga', 'type' => 'mencukupi', 'slot' => 1, 'sort_order' => 1]);
        $user->createToken('android:pixel-1');

        Livewire::actingAs($user)
            ->test(Profil::class)
            ->call('deleteAccount')
            ->assertRedirect(route('login'));

        $this->assertGuest();
        $this->assertSame(0, User::count());
        $this->assertModelMissing($room);
        $this->assertSame(0, PersonalAccessToken::count());
    }

    public function test_profile_page_shows_name_and_email(): void
    {
        $user = User::factory()->create(['name' => 'Roziq', 'email' => 'roziq@example.com']);

        $this->actingAs($user)->get(route('profil'))
            ->assertOk()
            ->assertSee('Roziq')
            ->assertSee('roziq@example.com');
    }

    public function test_guest_is_redirected_to_login(): void
    {
        $this->get(route('profil'))->assertRedirect(route('login'));
    }

    private function googleUser(): User
    {
        $user = User::factory()->create(['email' => 'roziq@example.com', 'google_sub' => 'g-1']);
        $user->forceFill(['password' => null])->save();

        return $user;
    }

    public function test_google_account_sees_the_set_password_form_without_current_password(): void
    {
        $this->actingAs($this->googleUser())->get(route('profil'))
            ->assertOk()
            ->assertSee('Kata sandi')
            ->assertSee('belum punya kata sandi')
            ->assertSee('Tetapkan kata sandi')
            ->assertDontSee('Kata sandi saat ini');
    }

    public function test_account_with_a_password_sees_the_change_form_with_current_password(): void
    {
        $this->actingAs(User::factory()->create())->get(route('profil'))
            ->assertOk()
            ->assertSee('Kata sandi saat ini')
            ->assertSee('Ubah kata sandi')
            ->assertDontSee('belum punya kata sandi');
    }

    public function test_google_account_sets_a_first_password_without_the_old_one_and_can_then_log_in_with_it(): void
    {
        $user = $this->googleUser();

        Livewire::actingAs($user)->test(Profil::class)
            ->set('password', 'kopi-susu-gula-aren')
            ->set('password_confirmation', 'kopi-susu-gula-aren')
            ->call('savePassword')
            ->assertHasNoErrors()
            ->assertSet('passwordSaved', 'set')
            ->assertSet('password', '');

        $user->refresh();
        $this->assertTrue(Hash::check('kopi-susu-gula-aren', $user->password));
        $this->assertSame('g-1', $user->google_sub);

        auth()->logout();
        $this->post(route('login.attempt'), ['email' => 'roziq@example.com', 'password' => 'kopi-susu-gula-aren'])
            ->assertRedirect(route('denah'));
        $this->assertAuthenticatedAs($user);
    }

    public function test_setting_the_first_password_does_not_sign_out_other_devices(): void
    {
        config(['session.driver' => 'database']);
        $user = $this->googleUser();
        DB::table('sessions')->insert(['id' => 'hp-lain', 'user_id' => $user->id, 'payload' => 'x', 'last_activity' => time()]);

        Livewire::actingAs($user)->test(Profil::class)
            ->set('password', 'kopi-susu-gula-aren')
            ->set('password_confirmation', 'kopi-susu-gula-aren')
            ->call('savePassword');

        $this->assertSame(1, DB::table('sessions')->where('id', 'hp-lain')->count());
    }

    public function test_account_with_a_password_must_give_the_current_one_to_change_it(): void
    {
        $user = User::factory()->create(['password' => 'kopi-susu-gula-aren']);

        Livewire::actingAs($user)->test(Profil::class)
            ->set('current_password', 'salah-banget-sandinya')
            ->set('password', 'sandi-baru-yang-aman')
            ->set('password_confirmation', 'sandi-baru-yang-aman')
            ->call('savePassword')
            ->assertHasErrors(['current_password'])
            ->assertSet('passwordSaved', null);

        $this->assertTrue(Hash::check('kopi-susu-gula-aren', $user->fresh()->password));
    }

    public function test_changing_the_password_with_the_right_current_one_works_and_revokes_other_sessions(): void
    {
        config(['session.driver' => 'database']);
        $user = User::factory()->create(['password' => 'kopi-susu-gula-aren']);
        $oldRemember = $user->remember_token;
        DB::table('sessions')->insert(['id' => 'hp-lain', 'user_id' => $user->id, 'payload' => 'x', 'last_activity' => time()]);

        Livewire::actingAs($user)->test(Profil::class)
            ->set('current_password', 'kopi-susu-gula-aren')
            ->set('password', 'sandi-baru-yang-aman')
            ->set('password_confirmation', 'sandi-baru-yang-aman')
            ->call('savePassword')
            ->assertHasNoErrors()
            ->assertSet('passwordSaved', 'changed');

        $this->assertTrue(Hash::check('sandi-baru-yang-aman', $user->fresh()->password));
        $this->assertSame(0, DB::table('sessions')->where('id', 'hp-lain')->count());
        $this->assertNotSame($oldRemember, $user->fresh()->remember_token);
    }

    public function test_new_password_must_be_strong_and_confirmed(): void
    {
        $user = $this->googleUser();

        Livewire::actingAs($user)->test(Profil::class)
            ->set('password', 'password123')
            ->set('password_confirmation', 'password123')
            ->call('savePassword')
            ->assertHasErrors(['password']);

        Livewire::actingAs($user)->test(Profil::class)
            ->set('password', 'kopi-susu-gula-aren')
            ->set('password_confirmation', 'beda-sekali-sandinya')
            ->call('savePassword')
            ->assertHasErrors(['password']);

        $this->assertFalse($user->fresh()->hasPassword());
    }

    public function test_wrong_current_password_is_locked_out_after_five_tries(): void
    {
        $user = User::factory()->create(['password' => 'kopi-susu-gula-aren']);
        $component = Livewire::actingAs($user)->test(Profil::class)
            ->set('password', 'sandi-baru-yang-aman')
            ->set('password_confirmation', 'sandi-baru-yang-aman');

        foreach (range(1, 5) as $i) {
            $component->set('current_password', 'salah-banget-sandinya')->call('savePassword');
        }

        $component->set('current_password', 'kopi-susu-gula-aren')->call('savePassword')
            ->assertHasErrors(['current_password']);
        $this->assertTrue(Hash::check('kopi-susu-gula-aren', $user->fresh()->password));
    }
}
