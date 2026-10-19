<?php

namespace Tests\Feature;

use App\Livewire\Profil;
use App\Models\Room;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Laravel\Sanctum\PersonalAccessToken;
use Livewire\Livewire;
use Tests\TestCase;

/** Hapus akun dari web (Tahap 9, server-sync.md): syarat Google Play bila ada pembuatan akun. */
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
}
