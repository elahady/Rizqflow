<?php

namespace Tests\Feature\Api;

use App\Auth\ApiTokenService;
use App\Auth\GoogleIdTokenVerifier;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Laravel\Sanctum\PersonalAccessToken;
use PHPUnit\Framework\Attributes\DataProvider;
use Tests\Support\UsesApiTokens;
use Tests\TestCase;

/**
 * Penerbitan token saat masuk dengan Google (Tahap 12): validasi `device_id`, plafon token per akun
 * (yang terlama dibuang), dan batas laju per IP untuk POST /api/auth/google. Hari "sekarang" dipatok
 * 24 Okt 2026 10:00 UTC.
 */
class ApiTokenIssuanceTest extends TestCase
{
    use RefreshDatabase, UsesApiTokens;

    protected function setUp(): void
    {
        parent::setUp();
        $this->travelTo('2026-10-24 10:00:00');
    }

    private function fakeGoogle(): void
    {
        $this->app->instance(GoogleIdTokenVerifier::class, new class implements GoogleIdTokenVerifier
        {
            public function verify(string $idToken): ?array
            {
                return str_starts_with($idToken, 'valid-')
                    ? ['sub' => substr($idToken, 6), 'email' => 'roziq@example.com', 'email_verified' => true, 'name' => 'Roziq']
                    : null;
            }
        });
    }

    /** Masuk persis seperti klien Retrofit; $body menggantikan seluruh isi (mis. tanpa device_id). */
    private function login(array $body, string $ip = '203.0.113.7')
    {
        $this->app['auth']->forgetGuards();

        return $this->call('POST', '/api/auth/google', [], [], [], [
            'CONTENT_TYPE' => 'application/json',
            'REMOTE_ADDR' => $ip,
        ], json_encode($body));
    }

    // --- device_id ---------------------------------------------------------------------------------

    public static function invalidDeviceIds(): array
    {
        return [
            'terlalu panjang (65)' => [str_repeat('a', 65)],
            'spasi' => ['pixel 1'],
            'garis miring' => ['../etc/passwd'],
            'titik dua' => ['android:pixel'],
            'huruf non-ASCII' => ['pixél'],
            'baris baru' => ["pixel\n1"],
            'angka bukan string' => [12345],
            'larik' => [['pixel-1']],
            'objek' => [['a' => 'b']],
            'boolean' => [true],
        ];
    }

    #[DataProvider('invalidDeviceIds')]
    public function test_an_invalid_device_id_is_422_and_creates_nothing(mixed $deviceId): void
    {
        $this->fakeGoogle();

        $this->login(['credential' => 'valid-123', 'device_id' => $deviceId])
            ->assertUnprocessable()
            ->assertJsonValidationErrors('device_id');

        $this->assertSame(0, PersonalAccessToken::count());
        $this->assertSame(0, User::count());
    }

    public static function validDeviceIds(): array
    {
        return [
            'UUID seperti dari aplikasi' => ['3f2b8c1e-9d4a-4e7b-a1c2-5d6e7f8a9b0c', 'android:3f2b8c1e-9d4a-4e7b-a1c2-5d6e7f8a9b0c'],
            'pendek' => ['pixel-1', 'android:pixel-1'],
            'titik dan garis bawah' => ['Pixel_9.pro', 'android:Pixel_9.pro'],
            'tepat 64 karakter' => [str_repeat('a', 64), 'android:'.str_repeat('a', 64)],
        ];
    }

    #[DataProvider('validDeviceIds')]
    public function test_a_valid_device_id_becomes_the_token_name(string $deviceId, string $expectedName): void
    {
        $this->fakeGoogle();

        $this->login(['credential' => 'valid-123', 'device_id' => $deviceId])->assertOk();

        $this->assertSame($expectedName, PersonalAccessToken::sole()->name);
    }

    public static function absentDeviceIds(): array
    {
        return [
            'tidak dikirim' => [['credential' => 'valid-123']],
            'null' => [['credential' => 'valid-123', 'device_id' => null]],
            'string kosong' => [['credential' => 'valid-123', 'device_id' => '']],
        ];
    }

    #[DataProvider('absentDeviceIds')]
    public function test_a_client_without_a_device_id_still_logs_in_with_the_default_name(array $body): void
    {
        $this->fakeGoogle();

        $this->login($body)->assertOk()->assertJsonStructure(['token', 'expires_at']);

        $this->assertSame('android:default', PersonalAccessToken::sole()->name);
    }

    // --- plafon token per akun ---------------------------------------------------------------------------

    /** 20 token d0..d19 untuk $user, dibuat berselang semenit (d0 paling lama). */
    private function fillToCap(User $user): void
    {
        for ($i = 0; $i < ApiTokenService::MAX_TOKENS_PER_ACCOUNT; $i++) {
            $this->tokenFor($user, 'android:d'.$i);
            $this->travelTo(now()->addMinute());
        }
    }

    private function names(User $user): array
    {
        return $user->tokens()->pluck('name')->sort()->values()->all();
    }

    public function test_the_cap_is_twenty(): void
    {
        $this->assertSame(20, ApiTokenService::MAX_TOKENS_PER_ACCOUNT);
    }

    public function test_a_new_device_at_the_cap_evicts_the_oldest_token(): void
    {
        $user = User::factory()->create();
        $this->fillToCap($user);

        app(ApiTokenService::class)->issueForDevice($user, 'android:baru');

        $this->assertSame(20, $user->tokens()->count());
        $this->assertSame(0, $user->tokens()->where('name', 'android:d0')->count());
        $this->assertSame(1, $user->tokens()->where('name', 'android:d1')->count());
        $this->assertSame(1, $user->tokens()->where('name', 'android:baru')->count());
    }

    public function test_recent_use_protects_a_token_from_eviction(): void
    {
        $user = User::factory()->create();
        $this->fillToCap($user);
        // d0 dibuat paling lama tetapi baru saja dipakai; yang terlama TAK dipakai sekarang d1.
        $user->tokens()->where('name', 'android:d0')->update(['last_used_at' => now()]);

        app(ApiTokenService::class)->issueForDevice($user, 'android:baru');

        $this->assertSame(20, $user->tokens()->count());
        $this->assertSame(1, $user->tokens()->where('name', 'android:d0')->count());
        $this->assertSame(0, $user->tokens()->where('name', 'android:d1')->count());
    }

    public function test_relogin_on_an_existing_device_at_the_cap_evicts_nobody_else(): void
    {
        $user = User::factory()->create();
        $this->fillToCap($user);
        $before = $this->names($user);

        app(ApiTokenService::class)->issueForDevice($user, 'android:d7');

        $this->assertSame($before, $this->names($user));
        $this->assertSame(20, $user->tokens()->count());
    }

    public function test_an_account_already_over_the_cap_is_trimmed_back_to_it(): void
    {
        $user = User::factory()->create();
        for ($i = 0; $i < 25; $i++) {
            $this->tokenFor($user, 'android:d'.$i);
            $this->travelTo(now()->addMinute());
        }

        app(ApiTokenService::class)->issueForDevice($user, 'android:baru');

        $this->assertSame(20, $user->tokens()->count());
        $this->assertSame(1, $user->tokens()->where('name', 'android:baru')->count());
        // Yang tersisa adalah 19 yang terbaru (d6..d24) plus yang baru.
        $this->assertSame(0, $user->tokens()->where('name', 'android:d5')->count());
        $this->assertSame(1, $user->tokens()->where('name', 'android:d6')->count());
    }

    public function test_the_cap_is_per_account(): void
    {
        $user = User::factory()->create();
        $other = User::factory()->create();
        $this->fillToCap($user);
        $this->tokenFor($other, 'android:d0');

        app(ApiTokenService::class)->issueForDevice($user, 'android:baru');

        $this->assertSame(1, $other->tokens()->count());
    }

    public function test_logging_in_from_endless_new_devices_never_exceeds_the_cap(): void
    {
        $this->fakeGoogle();

        $first = null;
        for ($i = 0; $i < 25; $i++) {
            $token = $this->login(['credential' => 'valid-123', 'device_id' => 'dev-'.$i])->assertOk()->json('token');
            $first ??= $token;
            $this->travelTo(now()->addSecond());
        }

        $this->assertSame(20, PersonalAccessToken::count());
        // Token perangkat pertama (yang terlama) sudah dibuang dan ditolak.
        $this->api('DELETE', '/api/auth/tokens', $first)->assertUnauthorized();
    }

    // --- batas laju masuk ----------------------------------------------------------------------------------

    public function test_login_is_limited_to_30_per_minute_per_ip(): void
    {
        $this->fakeGoogle();

        for ($i = 0; $i < 30; $i++) {
            $this->login(['credential' => 'valid-123', 'device_id' => 'dev-'.($i % 5)])->assertOk();
        }

        $blocked = $this->login(['credential' => 'valid-123', 'device_id' => 'dev-1'])->assertStatus(429);
        $this->assertNotNull($blocked->headers->get('Retry-After'));
        // IP lain tidak terpengaruh.
        $this->login(['credential' => 'valid-123', 'device_id' => 'dev-1'], '198.51.100.9')->assertOk();

        // Setelah semenit batasnya pulih.
        $this->travelTo(now()->addMinute()->addSecond());
        $this->login(['credential' => 'valid-123', 'device_id' => 'dev-1'])->assertOk();
    }

    public function test_failed_logins_count_toward_the_ip_limit_too(): void
    {
        $this->fakeGoogle();

        for ($i = 0; $i < 30; $i++) {
            $this->login(['credential' => 'palsu'])->assertUnprocessable();
        }

        $this->login(['credential' => 'palsu'])->assertStatus(429);
    }
}
