<?php

namespace Tests\Feature\Api;

use App\Auth\GoogleIdTokenVerifier;
use App\Models\User;
use Illuminate\Console\Scheduling\Schedule;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Env;
use Laravel\Sanctum\PersonalAccessToken;
use PHPUnit\Framework\Attributes\DataProvider;
use Tests\Support\UsesApiTokens;
use Tests\TestCase;

/**
 * Masa berlaku token API (Tahap 12): masuk dengan Google menerbitkan token yang kedaluwarsa (`expires_at`
 * di kolom dan di jawaban), token kedaluwarsa ditolak 401 JSON, dan token kedaluwarsa dibuang harian.
 * Hari "sekarang" dipatok 24 Okt 2026 10:00 UTC; 90 hari kemudian jatuh 22 Jan 2027 (contoh kontrak).
 */
class ApiTokenExpiryTest extends TestCase
{
    use RefreshDatabase, UsesApiTokens;

    protected function setUp(): void
    {
        parent::setUp();
        $this->travelTo('2026-10-24 10:00:00');
    }

    protected function tearDown(): void
    {
        Env::getRepository()->clear('API_TOKEN_TTL_DAYS');
        parent::tearDown();
    }

    /** Token "valid-<sub>" sah dengan klaim di bawah; token lain ditolak. Sama pola dengan AccountControllerTest. */
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

    private function login(string $device = 'pixel-1')
    {
        return $this->api('POST', '/api/auth/google', null, ['credential' => 'valid-123', 'device_id' => $device]);
    }

    // --- masuk menerbitkan token yang kedaluwarsa --------------------------------------------

    public function test_login_returns_expires_at_90_days_ahead_and_stores_it_in_the_column(): void
    {
        $this->fakeGoogle();

        $response = $this->login()->assertOk();

        $response->assertJsonPath('expires_at', '2027-01-22T10:00:00Z');
        $this->assertSame('2027-01-22 10:00:00', PersonalAccessToken::sole()->expires_at->utc()->format('Y-m-d H:i:s'));
    }

    public function test_login_expires_at_is_iso_8601_utc_to_the_whole_second(): void
    {
        $this->fakeGoogle();
        $this->travelTo('2026-10-24 10:00:00.789456');

        $expiresAt = $this->login()->assertOk()->json('expires_at');

        $this->assertMatchesRegularExpression('/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/', $expiresAt);
        // Yang dijawab ke klien sama persis dengan yang tersimpan (kolom tak menyimpan pecahan detik).
        $this->assertSame($expiresAt, PersonalAccessToken::sole()->expires_at->utc()->format('Y-m-d\TH:i:s\Z'));
    }

    public function test_login_keeps_the_existing_response_fields_and_only_adds_expires_at(): void
    {
        $this->fakeGoogle();

        $response = $this->login()->assertOk()->assertJsonStructure(['token', 'expires_at', 'user' => ['id', 'name', 'email']]);

        $this->assertSame(['expires_at', 'token', 'user'], collect(array_keys($response->json()))->sort()->values()->all());
        $this->assertSame(['email', 'id', 'name'], collect(array_keys($response->json('user')))->sort()->values()->all());
    }

    public function test_the_token_issued_by_login_works_right_away(): void
    {
        $this->fakeGoogle();
        $token = $this->login()->json('token');
        User::sole()->forceFill(['plans' => ['sync']])->save();

        $this->api('GET', '/api/sync/pull', $token)->assertOk();
    }

    public function test_login_honours_a_ttl_from_config(): void
    {
        $this->fakeGoogle();
        config(['rizqflow.api_token_ttl_days' => 30]);

        $this->login()->assertOk()->assertJsonPath('expires_at', '2026-11-23T10:00:00Z');
        $this->assertSame('2026-11-23 10:00:00', PersonalAccessToken::sole()->expires_at->utc()->format('Y-m-d H:i:s'));
    }

    #[DataProvider('invalidTtls')]
    public function test_login_with_an_invalid_ttl_in_config_falls_back_to_90_days(mixed $invalid): void
    {
        $this->fakeGoogle();
        config(['rizqflow.api_token_ttl_days' => $invalid]);

        $this->login()->assertOk()->assertJsonPath('expires_at', '2027-01-22T10:00:00Z');
    }

    public static function invalidTtls(): array
    {
        return ['huruf' => ['abc'], 'nol' => [0], 'negatif' => [-7], 'kosong' => [null], 'pecahan' => ['2.5']];
    }

    public function test_relogin_on_the_same_device_issues_a_fresh_expiry_and_keeps_one_token(): void
    {
        $this->fakeGoogle();
        $this->login();
        $this->travelTo('2026-11-03 10:00:00');

        $this->login()->assertOk()->assertJsonPath('expires_at', '2027-02-01T10:00:00Z');

        $this->assertSame(1, PersonalAccessToken::count());
    }

    public function test_expires_at_is_always_utc_even_when_the_php_timezone_is_not(): void
    {
        $this->fakeGoogle();
        $original = date_default_timezone_get();
        date_default_timezone_set('Asia/Jakarta');
        try {
            $this->travelTo('2026-10-24 17:00:00'); // 10:00 UTC
            $response = $this->login()->assertOk();
            $token = $response->json('token');
            User::sole()->forceFill(['plans' => ['sync']])->save();

            $response->assertJsonPath('expires_at', '2027-01-22T10:00:00Z');

            // Masa berlaku tetap dihitung pada saat mutlak yang sama, bukan bergeser 7 jam.
            $this->travelTo('2027-01-22 16:59:00'); // 09:59 UTC
            $this->api('GET', '/api/sync/pull', $token)->assertOk();
            $this->travelTo('2027-01-22 17:01:00'); // 10:01 UTC
            $this->api('GET', '/api/sync/pull', $token)->assertUnauthorized();
        } finally {
            date_default_timezone_set($original);
        }
    }

    // --- env API_TOKEN_TTL_DAYS lewat berkas config -------------------------------------------

    /** Nilai env dibaca ulang dari berkas config yang sebenarnya, jadi yang diuji adalah env sungguhan. */
    private function configValuesFor(?string $env): array
    {
        $repository = Env::getRepository();
        $env === null ? $repository->clear('API_TOKEN_TTL_DAYS') : $repository->set('API_TOKEN_TTL_DAYS', $env);

        return [
            'days' => (require config_path('rizqflow.php'))['api_token_ttl_days'],
            'sanctum_minutes' => (require config_path('sanctum.php'))['expiration'],
        ];
    }

    #[DataProvider('envCases')]
    public function test_env_ttl_is_honoured_by_both_config_files_and_invalid_values_fall_back_to_90(?string $env, int $expectedDays): void
    {
        $values = $this->configValuesFor($env);

        $this->assertSame($expectedDays, $values['days']);
        $this->assertSame($expectedDays * 24 * 60, $values['sanctum_minutes']);
    }

    public static function envCases(): array
    {
        return [
            'tidak diisi' => [null, 90],
            'tiga puluh hari' => ['30', 30],
            'satu hari' => ['1', 1],
            'kosong' => ['', 90],
            'huruf' => ['sebulan', 90],
            'nol' => ['0', 90],
            'negatif' => ['-10', 90],
            'pecahan' => ['7.5', 90],
            'di atas batas' => ['999999', 90],
        ];
    }

    public function test_default_config_ties_the_sanctum_safety_net_to_the_token_ttl(): void
    {
        $this->assertSame(90, config('rizqflow.api_token_ttl_days'));
        $this->assertSame(config('rizqflow.api_token_ttl_days') * 24 * 60, config('sanctum.expiration'));
    }

    // --- token kedaluwarsa ditolak ------------------------------------------------------------

    public static function protectedEndpoints(): array
    {
        return [
            'sync pull' => ['GET', '/api/sync/pull'],
            'sync push' => ['POST', '/api/sync/push'],
            'klaim pembelian' => ['POST', '/api/purchases/claim'],
            'refresh' => ['POST', '/api/auth/refresh'],
            'logout' => ['POST', '/api/auth/logout'],
            'cabut semua token' => ['DELETE', '/api/auth/tokens'],
            'hapus akun' => ['DELETE', '/api/account'],
            'gabung ruang' => ['POST', '/api/rooms/abc/join'],
            'anggota ruang' => ['GET', '/api/rooms/abc/members'],
        ];
    }

    #[DataProvider('protectedEndpoints')]
    public function test_an_expired_token_is_rejected_with_a_json_401(string $method, string $uri): void
    {
        $user = $this->syncUser();
        $token = $this->tokenFor($user, expiresAt: now()->subSecond());

        $response = $this->api($method, $uri, $token);

        $response->assertStatus(401)->assertJsonStructure(['message']);
        $this->assertNull($response->headers->get('Location'));
        // Penolakan tidak mengubah apa pun: akun dan token (kedaluwarsa, menunggu dibuang) tetap ada.
        $this->assertSame(1, User::count());
        $this->assertSame(1, $user->tokens()->count());
    }

    public function test_a_token_works_up_to_the_moment_it_expires(): void
    {
        $user = $this->syncUser();
        $token = $this->tokenFor($user, expiresAt: now()->addHour());

        $this->api('GET', '/api/sync/pull', $token)->assertOk();

        $this->travelTo('2026-10-24 10:59:59');
        $this->api('GET', '/api/sync/pull', $token)->assertOk();

        $this->travelTo('2026-10-24 11:00:01');
        $this->api('GET', '/api/sync/pull', $token)->assertUnauthorized();
    }

    public function test_a_token_expires_90_days_after_login_in_practice(): void
    {
        $this->fakeGoogle();
        $token = $this->login()->json('token');
        User::sole()->forceFill(['plans' => ['sync']])->save();

        $this->travelTo('2027-01-22 09:59:59');
        $this->api('GET', '/api/sync/pull', $token)->assertOk();

        $this->travelTo('2027-01-22 10:00:01');
        $this->api('GET', '/api/sync/pull', $token)->assertUnauthorized();
    }

    public function test_a_legacy_token_without_expires_at_is_cut_off_by_the_sanctum_safety_net(): void
    {
        $user = $this->syncUser();
        $legacy = $user->createToken('android:lama')->plainTextToken; // terbitan sebelum ada masa berlaku
        $this->assertNull(PersonalAccessToken::sole()->expires_at);

        $this->travelTo(now()->addDays(89));
        $this->api('GET', '/api/sync/pull', $legacy)->assertOk();

        $this->travelTo(now()->addDays(2));
        $this->api('GET', '/api/sync/pull', $legacy)->assertUnauthorized();
    }

    // --- sanctum:prune-expired ------------------------------------------------------------------

    private function pruneSurvivors(): array
    {
        return PersonalAccessToken::orderBy('name')->pluck('name')->all();
    }

    public function test_prune_removes_tokens_expired_for_more_than_a_day_and_keeps_active_and_recent_ones(): void
    {
        $user = User::factory()->create();
        $this->tokenFor($user, 'aktif', now()->addDays(30));
        $this->tokenFor($user, 'kedaluwarsa-3-hari', now()->subDays(3));
        $this->tokenFor($user, 'kedaluwarsa-1-jam', now()->subHour());
        $user->createToken('lama-tanpa-expires'); // baru dibuat, belum lewat jaring pengaman

        $this->artisan('sanctum:prune-expired')->assertExitCode(0);

        $this->assertSame(['aktif', 'kedaluwarsa-1-jam', 'lama-tanpa-expires'], $this->pruneSurvivors());
    }

    public function test_prune_with_zero_hours_also_removes_the_recently_expired_but_never_an_active_token(): void
    {
        $user = User::factory()->create();
        $this->tokenFor($user, 'aktif', now()->addMinute());
        $this->tokenFor($user, 'kedaluwarsa-1-jam', now()->subHour());

        $this->artisan('sanctum:prune-expired', ['--hours' => 0])->assertExitCode(0);

        $this->assertSame(['aktif'], $this->pruneSurvivors());
    }

    public function test_prune_also_removes_ancient_tokens_that_never_got_an_expires_at(): void
    {
        $user = User::factory()->create();
        $this->travelTo(now()->subDays(200));
        $user->createToken('kuno-tanpa-expires');
        $this->travelTo(now()->addDays(200));
        $user->createToken('baru-tanpa-expires');

        $this->artisan('sanctum:prune-expired')->assertExitCode(0);

        $this->assertSame(['baru-tanpa-expires'], $this->pruneSurvivors());
    }

    public function test_prune_leaves_other_accounts_active_tokens_alone_while_pruning_expired_ones(): void
    {
        $pruned = User::factory()->create();
        $other = User::factory()->create();
        $this->tokenFor($pruned, 'a-kedaluwarsa', now()->subDays(5));
        $this->tokenFor($other, 'b-aktif', now()->addDays(5));

        $this->artisan('sanctum:prune-expired')->assertExitCode(0);

        $this->assertSame(['b-aktif'], $this->pruneSurvivors());
        $this->assertSame(1, $other->tokens()->count());
    }

    public function test_prune_is_scheduled_daily(): void
    {
        $events = collect(app(Schedule::class)->events())
            ->filter(fn ($event) => str_contains($event->command, 'sanctum:prune-expired'));

        $this->assertCount(1, $events);
        $this->assertSame('45 3 * * *', $events->first()->expression);
        $this->assertStringContainsString('--hours=24', $events->first()->command);
    }
}
