<?php

namespace Tests\Feature\Api;

use App\Auth\ApiTokenService;
use App\Auth\GoogleIdTokenVerifier;
use App\Livewire\Profil;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Laravel\Sanctum\PersonalAccessToken;
use Livewire\Livewire;
use Tests\Support\UsesApiTokens;
use Tests\TestCase;

/**
 * Daftar perangkat dan "Keluar dari semua perangkat" lewat API (Tahap 14): `GET /api/auth/tokens` dan
 * `DELETE /api/auth/tokens`, plus nama perangkat yang dikirim klien saat masuk dan memutar token.
 * Hari "sekarang" dipatok 26 Okt 2026 10:00 UTC supaya tanggal di jawaban bisa dibandingkan pasti.
 */
class DeviceListTest extends TestCase
{
    use RefreshDatabase, UsesApiTokens;

    private const PASSWORD = 'kopi-susu-gula-aren';

    protected function setUp(): void
    {
        parent::setUp();
        $this->travelTo('2026-10-26 10:00:00');
    }

    /** Token perangkat lewat jalur resmi (ApiTokenService), dengan nama perangkat opsional. */
    private function device(User $user, string $deviceId, ?string $deviceName = null): string
    {
        return app(ApiTokenService::class)->issueForDevice($user, 'android:'.$deviceId, $deviceName)->plainTextToken;
    }

    // --- GET /api/auth/tokens ------------------------------------------------------------------

    public function test_lists_this_accounts_devices_with_metadata_and_never_the_token_itself(): void
    {
        $user = User::factory()->create();
        $tokenA = $this->device($user, 'uuid-perangkat-a', 'Samsung SM-S911B');
        $tokenB = $this->device($user, 'uuid-perangkat-b');
        $idB = PersonalAccessToken::where('name', 'android:uuid-perangkat-b')->value('id');
        PersonalAccessToken::whereKey($idB)->update(['last_used_at' => now()->subDays(2)]);

        // Akun lain tidak boleh ikut terdaftar.
        $this->device(User::factory()->create(), 'uuid-akun-lain', 'Perangkat orang lain');

        $response = $this->api('GET', '/api/auth/tokens', $tokenA)->assertOk();

        $devices = $response->json('devices');
        $this->assertCount(2, $devices);
        $this->assertSame(['created_at', 'current', 'device_name', 'expires_at', 'id', 'label', 'last_used_at'], collect(array_keys($devices[0]))->sort()->values()->all());

        $named = collect($devices)->firstWhere('device_name', 'Samsung SM-S911B');
        $this->assertSame('Android', $named['label']);
        $this->assertTrue($named['current']);
        $this->assertSame('2026-10-26T10:00:00Z', $named['created_at']);
        $this->assertSame('2027-01-24T10:00:00Z', $named['expires_at']); // 90 hari setelah 26 Okt 2026

        $unnamed = collect($devices)->firstWhere('device_name', null);
        $this->assertFalse($unnamed['current']);
        $this->assertSame('2026-10-24T10:00:00Z', $unnamed['last_used_at']);

        // Isi token (bagian acak setelah "id|") dan pengenal perangkat asli tidak boleh ikut terjawab.
        $secretA = explode('|', $tokenA, 2)[1];
        $secretB = explode('|', $tokenB, 2)[1];
        $response->assertDontSee($secretA)->assertDontSee($secretB)->assertDontSee('uuid-perangkat-a')->assertDontSee('uuid-perangkat-b');
        $this->assertArrayNotHasKey('token', $devices[0]);
    }

    public function test_only_the_token_used_by_this_request_is_marked_current(): void
    {
        $user = User::factory()->create();
        $this->device($user, 'pertama', 'Pixel 8');
        $second = $this->device($user, 'kedua', 'Xiaomi 13');

        $devices = $this->api('GET', '/api/auth/tokens', $second)->assertOk()->json('devices');

        $this->assertCount(1, array_filter($devices, fn (array $d) => $d['current']));
        $this->assertTrue(collect($devices)->firstWhere('device_name', 'Xiaomi 13')['current']);
    }

    public function test_expired_device_is_still_listed_with_its_past_expiry(): void
    {
        $user = User::factory()->create();
        $alive = $this->device($user, 'hidup', 'Pixel 8');
        $expired = $this->device($user, 'kedaluwarsa', 'HP Lama');
        PersonalAccessToken::where('name', 'android:kedaluwarsa')->update(['expires_at' => now()->subDay()]);

        $devices = $this->api('GET', '/api/auth/tokens', $alive)->assertOk()->json('devices');

        $this->assertSame('2026-10-25T10:00:00Z', collect($devices)->firstWhere('device_name', 'HP Lama')['expires_at']);
        // Token yang sudah kedaluwarsa tidak bisa dipakai untuk memanggil endpoint lagi.
        $this->api('GET', '/api/auth/tokens', $expired)->assertUnauthorized();
    }

    public function test_listing_without_a_token_is_unauthorized_and_answers_json(): void
    {
        $user = User::factory()->create();
        $this->device($user, 'ada', 'Pixel 8');

        $this->api('GET', '/api/auth/tokens')
            ->assertUnauthorized()
            ->assertJsonMissing(['devices']);
    }

    public function test_listing_with_an_unknown_token_is_unauthorized(): void
    {
        $user = User::factory()->create();
        $this->device($user, 'ada', 'Pixel 8');

        $this->api('GET', '/api/auth/tokens', '999|palsu-tidak-pernah-terbit')->assertUnauthorized();
    }

    // --- DELETE /api/auth/tokens ---------------------------------------------------------------

    public function test_revoke_all_deletes_every_token_of_this_account_and_nothing_else(): void
    {
        $user = User::factory()->create();
        $tokenA = $this->device($user, 'a', 'Pixel 8');
        $tokenB = $this->device($user, 'b', 'Xiaomi 13');
        $other = $this->device(User::factory()->create(), 'lain', 'Perangkat lain');

        $this->api('DELETE', '/api/auth/tokens', $tokenA)->assertNoContent();

        $this->assertSame(0, $user->tokens()->count());
        $this->api('GET', '/api/auth/tokens', $tokenA)->assertUnauthorized();
        $this->api('GET', '/api/auth/tokens', $tokenB)->assertUnauthorized();
        // Akun lain tidak ikut tercabut.
        $this->api('GET', '/api/auth/tokens', $other)->assertOk();
    }

    public function test_revoke_all_without_a_token_is_unauthorized_and_deletes_nothing(): void
    {
        $user = User::factory()->create();
        $this->device($user, 'a', 'Pixel 8');

        $this->api('DELETE', '/api/auth/tokens')->assertUnauthorized();

        $this->assertSame(1, $user->tokens()->count());
    }

    public function test_revoke_all_with_an_unknown_token_is_unauthorized_and_deletes_nothing(): void
    {
        $user = User::factory()->create();
        $this->device($user, 'a', 'Pixel 8');

        $this->api('DELETE', '/api/auth/tokens', '999|palsu-tidak-pernah-terbit')->assertUnauthorized();

        $this->assertSame(1, $user->tokens()->count());
    }

    // --- nama perangkat dari klien -------------------------------------------------------------

    public function test_google_login_saves_the_device_name_sent_by_the_client(): void
    {
        $this->app->instance(GoogleIdTokenVerifier::class, new class implements GoogleIdTokenVerifier
        {
            public function verify(string $idToken): ?array
            {
                return ['sub' => 'sub-1', 'email' => 'roziq@example.com', 'email_verified' => true, 'name' => 'Roziq'];
            }
        });

        $this->postJson('/api/auth/google', [
            'credential' => 'valid',
            'device_id' => 'pixel-1',
            'device_name' => 'Google Pixel 8',
        ])->assertOk();

        $this->assertSame('Google Pixel 8', PersonalAccessToken::sole()->device_name);
    }

    public function test_password_login_saves_the_device_name_and_missing_name_stays_null(): void
    {
        User::factory()->create(['email' => 'budi@example.com', 'password' => self::PASSWORD]);

        $this->postJson('/api/auth/login', [
            'email' => 'budi@example.com', 'password' => self::PASSWORD, 'device_id' => 'hp-1', 'device_name' => 'Samsung SM-S911B',
        ])->assertOk();
        $this->postJson('/api/auth/login', [
            'email' => 'budi@example.com', 'password' => self::PASSWORD, 'device_id' => 'hp-2',
        ])->assertOk();

        $this->assertSame('Samsung SM-S911B', PersonalAccessToken::where('name', 'android:hp-1')->value('device_name'));
        $this->assertNull(PersonalAccessToken::where('name', 'android:hp-2')->value('device_name'));
    }

    public function test_blank_device_name_is_treated_as_missing(): void
    {
        User::factory()->create(['email' => 'budi@example.com', 'password' => self::PASSWORD]);

        $this->postJson('/api/auth/login', [
            'email' => 'budi@example.com', 'password' => self::PASSWORD, 'device_id' => 'hp-1', 'device_name' => '   ',
        ])->assertOk();

        $this->assertNull(PersonalAccessToken::sole()->device_name);
    }

    public function test_device_name_longer_than_100_characters_is_rejected(): void
    {
        User::factory()->create(['email' => 'budi@example.com', 'password' => self::PASSWORD]);

        $this->postJson('/api/auth/login', [
            'email' => 'budi@example.com', 'password' => self::PASSWORD, 'device_id' => 'hp-1', 'device_name' => str_repeat('a', 101),
        ])->assertUnprocessable()->assertJsonValidationErrors('device_name');

        $this->assertSame(0, PersonalAccessToken::count());
    }

    public function test_refresh_keeps_the_device_name_when_the_client_sends_none(): void
    {
        $user = User::factory()->create();
        $old = $this->device($user, 'hp', 'Pixel 8');

        $new = $this->api('POST', '/api/auth/refresh', $old)->assertOk()->json('token');

        $this->assertSame('Pixel 8', PersonalAccessToken::sole()->device_name);
        $this->api('GET', '/api/auth/tokens', $new)->assertOk()->assertJsonPath('devices.0.device_name', 'Pixel 8');
    }

    public function test_web_profil_shows_the_device_name_instead_of_the_generic_label(): void
    {
        $user = User::factory()->create();
        $this->device($user, 'uuid-tanpa-nama');
        $this->device($user, 'uuid-bernama', 'Samsung SM-S911B');

        Livewire::actingAs($user)->test(Profil::class)
            ->assertSee('Samsung SM-S911B')
            ->assertSee('Android');
    }

    public function test_refresh_replaces_the_device_name_when_the_client_sends_one(): void
    {
        $user = User::factory()->create();
        $old = $this->device($user, 'hp', 'Pixel 8');

        $this->api('POST', '/api/auth/refresh', $old, ['device_name' => 'Pixel 8 Pro'])->assertOk();

        $this->assertSame('Pixel 8 Pro', PersonalAccessToken::sole()->device_name);
    }
}
