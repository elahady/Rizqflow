<?php

namespace Tests\Feature;

use App\Auth\ApiTokenService;
use App\Livewire\Profil;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Laravel\Sanctum\NewAccessToken;
use Laravel\Sanctum\PersonalAccessToken;
use Livewire\Livewire;
use Tests\TestCase;

/** Profil: daftar perangkat yang masuk, cabut satu, dan keluar dari semua perangkat (Tahap 14, token Tahap 12). */
class ProfilDevicesTest extends TestCase
{
    use RefreshDatabase;

    private function device(User $user, string $deviceId): NewAccessToken
    {
        return app(ApiTokenService::class)->issueForDevice($user, 'android:'.$deviceId);
    }

    public function test_lists_each_device_with_last_used_and_expiry_but_never_the_token(): void
    {
        $user = User::factory()->create();
        $token = $this->device($user, 'uuid-rahasia-perangkat-1');
        PersonalAccessToken::whereKey($token->accessToken->id)->update(['last_used_at' => now()->subDays(2)]);

        $page = Livewire::actingAs($user)->test(Profil::class)
            ->assertSee('Perangkat yang masuk')
            ->assertSee('Android')
            ->assertSee('Terakhir dipakai '.now()->subDays(2)->locale('id')->translatedFormat('j F Y'))
            ->assertSee('Berlaku sampai '.$token->accessToken->expires_at->locale('id')->translatedFormat('j F Y'))
            ->assertSee('Keluar dari semua perangkat');

        // Isi token (bagian acak setelah "id|") dan hash-nya tidak boleh muncul di halaman, dan id perangkat juga tidak.
        $secret = explode('|', $token->plainTextToken, 2)[1];
        $page->assertDontSee($token->plainTextToken)
            ->assertDontSee($secret)
            ->assertDontSee($token->accessToken->token)
            ->assertDontSee('uuid-rahasia-perangkat-1');
    }

    public function test_device_that_never_connected_says_so_and_expired_one_is_marked(): void
    {
        $user = User::factory()->create();
        $token = $this->device($user, 'pernah-kedaluwarsa');
        PersonalAccessToken::whereKey($token->accessToken->id)->update([
            'last_used_at' => null,
            'expires_at' => now()->subDay(),
        ]);

        Livewire::actingAs($user)->test(Profil::class)
            ->assertSee('Belum pernah dipakai')
            ->assertSee('Kedaluwarsa pada '.now()->subDay()->locale('id')->translatedFormat('j F Y'));
    }

    public function test_account_without_devices_sees_empty_state_and_no_sign_out_all_button(): void
    {
        $user = User::factory()->create();

        Livewire::actingAs($user)->test(Profil::class)
            ->assertSee('Belum ada perangkat Android yang masuk')
            ->assertDontSee('Keluar dari semua perangkat');
    }

    public function test_revoking_one_device_deletes_only_that_row(): void
    {
        $user = User::factory()->create();
        $first = $this->device($user, 'perangkat-a');
        $second = $this->device($user, 'perangkat-b');

        Livewire::actingAs($user)->test(Profil::class)
            ->call('revokeDevice', $first->accessToken->id)
            ->assertSet('deviceNotice', 'Perangkat sudah dicabut dan harus masuk lagi.');

        $this->assertDatabaseMissing('personal_access_tokens', ['id' => $first->accessToken->id]);
        $this->assertDatabaseHas('personal_access_tokens', ['id' => $second->accessToken->id]);
        $this->assertSame(1, $user->tokens()->count());
    }

    public function test_revoked_device_token_no_longer_authenticates_against_the_api(): void
    {
        $user = User::factory()->create();
        $token = $this->device($user, 'perangkat-dicabut');

        $this->withHeader('Authorization', 'Bearer '.$token->plainTextToken)
            ->postJson('/api/auth/logout')
            ->assertNoContent();

        // Setelah dicabut dari Profil, token yang sama tidak boleh lagi lolos autentikasi.
        $this->assertSame(0, PersonalAccessToken::count());
        $this->app['auth']->forgetGuards();
        $this->withHeader('Authorization', 'Bearer '.$token->plainTextToken)
            ->postJson('/api/auth/logout')
            ->assertUnauthorized();
    }

    public function test_revoking_a_device_from_profil_locks_out_its_token(): void
    {
        $user = User::factory()->create();
        $token = $this->device($user, 'perangkat-profil');

        Livewire::actingAs($user)->test(Profil::class)
            ->call('revokeDevice', $token->accessToken->id);

        $this->app['auth']->forgetGuards();
        $this->withHeader('Authorization', 'Bearer '.$token->plainTextToken)
            ->postJson('/api/auth/logout')
            ->assertUnauthorized();
    }

    public function test_sign_out_everywhere_deletes_all_device_tokens_of_this_account_only(): void
    {
        $user = $this->userWithDevices(['perangkat-1', 'perangkat-2', 'perangkat-3']);
        $other = $this->userWithDevices(['milik-akun-lain']);

        Livewire::actingAs($user)->test(Profil::class)
            ->call('revokeAllDevices')
            ->assertSet('deviceNotice', 'Semua perangkat Android sudah dikeluarkan dari akun ini.')
            ->assertDontSee('Keluar dari semua perangkat')
            ->assertSee('Belum ada perangkat Android yang masuk');

        $this->assertSame(0, $user->tokens()->count());
        $this->assertSame(1, $other->tokens()->count(), 'Token akun lain tidak boleh ikut terhapus.');
    }

    public function test_cannot_revoke_a_token_that_belongs_to_another_account(): void
    {
        $user = User::factory()->create();
        $this->device($user, 'milik-saya');
        $other = User::factory()->create();
        $foreign = $this->device($other, 'milik-orang-lain');

        Livewire::actingAs($user)->test(Profil::class)
            ->call('revokeDevice', $foreign->accessToken->id)
            ->assertSet('deviceNotice', 'Perangkat itu sudah tidak masuk.');

        $this->assertDatabaseHas('personal_access_tokens', ['id' => $foreign->accessToken->id]);
        $this->assertSame(1, $other->tokens()->count());
    }

    public function test_another_accounts_devices_are_not_listed(): void
    {
        $user = User::factory()->create();
        $other = User::factory()->create();
        $this->device($other, 'perangkat-orang-lain');

        Livewire::actingAs($user)->test(Profil::class)
            ->assertSee('Belum ada perangkat Android yang masuk')
            ->assertDontSee('perangkat-orang-lain');
    }

    /** @param list<string> $deviceIds */
    private function userWithDevices(array $deviceIds): User
    {
        $user = User::factory()->create();
        foreach ($deviceIds as $deviceId) {
            $this->device($user, $deviceId);
        }

        return $user;
    }
}
