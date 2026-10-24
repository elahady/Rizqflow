<?php

namespace Tests\Feature\Api;

use App\Auth\ApiTokenService;
use App\Livewire\Profil;
use App\Mail\CodeMail;
use App\Models\EmailCode;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Log\Events\MessageLogged;
use Illuminate\Support\Facades\Event;
use Illuminate\Support\Facades\Mail;
use Laravel\Sanctum\Events\TokenAuthenticated;
use Laravel\Sanctum\NewAccessToken;
use Laravel\Sanctum\PersonalAccessToken;
use Livewire\Features\SupportTesting\Testable;
use Livewire\Livewire;
use PHPUnit\Framework\Attributes\DataProvider;
use RuntimeException;
use Tests\Support\UsesApiTokens;
use Tests\TestCase;

/**
 * Siklus hidup token API (Tahap 12): refresh memutar token, logout mencabut token ini saja, DELETE
 * /api/auth/tokens mencabut semuanya, ubah/reset kata sandi mencabut semua token, dan hanya refresh
 * yang dibatasi lajunya (logout dan cabut-semua tidak bisa dihalangi). Masa berlaku dan prune diuji di [ApiTokenExpiryTest]. Hari "sekarang" dipatok
 * 24 Okt 2026 10:00 UTC.
 */
class ApiTokenLifecycleTest extends TestCase
{
    use RefreshDatabase, UsesApiTokens;

    private const PASSWORD = 'kopi-susu-gula-aren';

    protected function setUp(): void
    {
        parent::setUp();
        $this->travelTo('2026-10-24 10:00:00');
    }

    // --- refresh ------------------------------------------------------------------------------

    public function test_refresh_returns_a_new_working_token_with_a_full_new_expiry(): void
    {
        $user = $this->syncUser();
        $old = $this->tokenFor($user); // berlaku sampai 23 Nov
        $this->travelTo('2026-11-13 10:00:00'); // 20 hari kemudian, token lama masih sah

        $response = $this->api('POST', '/api/auth/refresh', $old)->assertOk();

        $this->assertSame(['expires_at', 'token'], collect(array_keys($response->json()))->sort()->values()->all());
        $response->assertJsonPath('expires_at', now()->addDays(90)->utc()->format('Y-m-d\TH:i:s\Z'));
        $this->assertNotSame($old, $response->json('token'));
        $this->assertSame(
            $response->json('expires_at'),
            PersonalAccessToken::sole()->expires_at->utc()->format('Y-m-d\TH:i:s\Z'),
        );

        $this->api('GET', '/api/sync/pull', $response->json('token'))->assertOk();
    }

    public function test_refresh_honours_the_configured_ttl(): void
    {
        config(['rizqflow.api_token_ttl_days' => 14]);
        $old = $this->tokenFor($this->syncUser());

        $this->api('POST', '/api/auth/refresh', $old)->assertOk()->assertJsonPath('expires_at', '2026-11-07T10:00:00Z');
    }

    public function test_refresh_revokes_the_old_token(): void
    {
        $old = $this->tokenFor($this->syncUser());

        $this->api('POST', '/api/auth/refresh', $old)->assertOk();

        $this->api('GET', '/api/sync/pull', $old)->assertUnauthorized();
        $this->api('POST', '/api/auth/refresh', $old)->assertUnauthorized();
    }

    public function test_refresh_keeps_the_device_name_and_abilities_and_leaves_other_tokens_alone(): void
    {
        $user = User::factory()->create();
        $other = User::factory()->create();
        $old = $this->tokenFor($user, 'android:pixel-1', abilities: ['sync:read', 'sync:write']);
        $this->tokenFor($user, 'android:tablet');
        $this->tokenFor($other, 'android:pixel-1');

        $this->api('POST', '/api/auth/refresh', $old)->assertOk();

        $this->assertSame(2, $user->tokens()->count());
        $rotated = $user->tokens()->where('name', 'android:pixel-1')->sole();
        $this->assertSame(['sync:read', 'sync:write'], $rotated->abilities);
        $this->assertSame(1, $user->tokens()->where('name', 'android:tablet')->count());
        $this->assertSame(1, $other->tokens()->count());
    }

    public function test_repeated_refreshes_never_pile_up_tokens(): void
    {
        $user = $this->syncUser();
        $token = $this->tokenFor($user, 'android:pixel-1');

        for ($i = 0; $i < 5; $i++) {
            $token = $this->api('POST', '/api/auth/refresh', $token)->assertOk()->json('token');
            $this->assertSame(1, $user->tokens()->count());
        }

        $this->api('GET', '/api/sync/pull', $token)->assertOk();
    }

    public function test_refresh_with_an_expired_token_is_401_and_issues_nothing(): void
    {
        $user = User::factory()->create();
        $expired = $this->tokenFor($user, expiresAt: now()->subMinute());

        $this->api('POST', '/api/auth/refresh', $expired)->assertUnauthorized()->assertJsonStructure(['message']);

        $this->assertSame(1, $user->tokens()->count());
        $this->assertTrue($user->tokens()->sole()->expires_at->isPast());
    }

    public function test_a_legacy_token_without_expires_at_can_be_refreshed_into_one_that_expires(): void
    {
        $user = $this->syncUser();
        $legacy = $user->createToken('android:lama')->plainTextToken;
        $this->travelTo(now()->addDays(30));

        $response = $this->api('POST', '/api/auth/refresh', $legacy)->assertOk();

        $this->assertSame(now()->addDays(90)->utc()->format('Y-m-d\TH:i:s\Z'), $response->json('expires_at'));
        $this->assertNotNull(PersonalAccessToken::sole()->expires_at);
        $this->assertSame('android:lama', PersonalAccessToken::sole()->name);
    }

    // --- refresh: dua permintaan bersamaan ------------------------------------------------------
    //
    // Proses tes PHP satu utas, jadi permintaan paralel sungguhan tidak bisa dijalankan. Yang diuji
    // adalah jendela balapannya: dua permintaan sama-sama lolos autentikasi dengan token yang sama
    // SEBELUM salah satunya memutar. Jaminannya ada di ApiTokenService::rotate: pencabutan adalah
    // DELETE atas id token dan hanya pemanggil yang melihat satu baris terhapus yang boleh menerbitkan.

    public function test_two_rotations_of_the_same_loaded_token_yield_exactly_one_new_token(): void
    {
        $user = User::factory()->create();
        $plain = $this->tokenFor($user, 'android:pixel-1');
        // Dua permintaan memuat token yang sama dari database sebelum yang mana pun memutarnya.
        $loadedByA = PersonalAccessToken::findToken($plain);
        $loadedByB = PersonalAccessToken::findToken($plain);
        $service = app(ApiTokenService::class);

        $winner = $service->rotate($user, $loadedByA);
        $loser = $service->rotate($user, $loadedByB);

        $this->assertInstanceOf(NewAccessToken::class, $winner);
        $this->assertNull($loser);
        $this->assertSame(1, $user->tokens()->count());
        $this->assertSame($winner->accessToken->getKey(), $user->tokens()->sole()->getKey());
    }

    public function test_a_refresh_that_loses_the_race_is_401_and_only_the_winners_token_survives(): void
    {
        $user = $this->syncUser();
        $plain = $this->tokenFor($user, 'android:pixel-1');

        // Begitu permintaan ini lolos autentikasi, permintaan saingan (token yang sama, sudah lolos
        // autentikasi juga) menyelesaikan refresh-nya lebih dulu; permintaan ini baru sampai ke controller.
        $winner = null;
        Event::listen(TokenAuthenticated::class, function (TokenAuthenticated $event) use (&$winner, $user) {
            $winner ??= app(ApiTokenService::class)->rotate($user, $event->token);
        });

        $this->api('POST', '/api/auth/refresh', $plain)->assertUnauthorized();

        $this->assertNotNull($winner);
        $this->assertSame(1, $user->tokens()->count());
        // Yang kalah tidak menerbitkan token; token pemenang berfungsi, token awal sudah tiada.
        Event::forget(TokenAuthenticated::class);
        $this->api('GET', '/api/sync/pull', $winner->plainTextToken)->assertOk();
        $this->api('GET', '/api/sync/pull', $plain)->assertUnauthorized();
    }

    public function test_a_failure_while_issuing_the_new_token_keeps_the_old_one(): void
    {
        $user = User::factory()->create();
        $plain = $this->tokenFor($user);
        $service = new class extends ApiTokenService
        {
            public function issue(User $user, string $name, array $abilities = ['*']): NewAccessToken
            {
                throw new RuntimeException('gagal menerbitkan');
            }
        };

        try {
            $service->rotate($user, PersonalAccessToken::findToken($plain));
            $this->fail('Pengecualian penerbitan seharusnya merambat.');
        } catch (RuntimeException) {
            // Transaksi dibatalkan: pencabutan token lama ikut dibatalkan.
        }

        $this->assertNotNull(PersonalAccessToken::findToken($plain));
        $this->assertSame(1, $user->tokens()->count());
    }

    // --- logout --------------------------------------------------------------------------------

    public function test_logout_revokes_only_the_token_that_made_the_request(): void
    {
        $user = $this->syncUser();
        $other = $this->syncUser();
        $thisDevice = $this->tokenFor($user, 'android:pixel-1');
        $otherDevice = $this->tokenFor($user, 'android:tablet');
        $otherAccount = $this->tokenFor($other, 'android:pixel-1');

        $response = $this->api('POST', '/api/auth/logout', $thisDevice)->assertNoContent();

        $this->assertSame('', $response->getContent());
        $this->api('GET', '/api/sync/pull', $thisDevice)->assertUnauthorized();
        $this->api('GET', '/api/sync/pull', $otherDevice)->assertOk();
        $this->api('GET', '/api/sync/pull', $otherAccount)->assertOk();
        $this->assertSame(1, $user->tokens()->count());
    }

    public function test_logging_out_twice_is_401_the_second_time(): void
    {
        $token = $this->tokenFor(User::factory()->create());

        $this->api('POST', '/api/auth/logout', $token)->assertNoContent();
        $this->api('POST', '/api/auth/logout', $token)->assertUnauthorized();
    }

    // --- cabut semua token ------------------------------------------------------------------------

    public function test_delete_tokens_revokes_every_token_of_the_account_and_nobody_elses(): void
    {
        $user = $this->syncUser();
        $other = $this->syncUser();
        $tokens = [
            $this->tokenFor($user, 'android:pixel-1'),
            $this->tokenFor($user, 'android:tablet'),
            $this->tokenFor($user, 'android:lama', now()->addDay()),
        ];
        $otherAccount = $this->tokenFor($other);

        $response = $this->api('DELETE', '/api/auth/tokens', $tokens[0])->assertNoContent();

        $this->assertSame('', $response->getContent());
        $this->assertSame(0, $user->tokens()->count());
        foreach ($tokens as $token) {
            $this->api('GET', '/api/sync/pull', $token)->assertUnauthorized();
        }
        $this->api('GET', '/api/sync/pull', $otherAccount)->assertOk();
        $this->assertNotNull(User::find($user->id), 'Mencabut token tidak menghapus akun.');
    }

    // --- tanpa autentikasi ---------------------------------------------------------------------------

    public static function tokenRoutes(): array
    {
        return [
            'refresh' => ['POST', '/api/auth/refresh'],
            'logout' => ['POST', '/api/auth/logout'],
            'cabut semua token' => ['DELETE', '/api/auth/tokens'],
        ];
    }

    #[DataProvider('tokenRoutes')]
    public function test_without_a_valid_token_the_route_is_a_json_401(string $method, string $uri): void
    {
        $user = User::factory()->create();
        $this->tokenFor($user);

        foreach ([null, 'bukan-token-sungguhan', '9999|tidak-ada'] as $token) {
            $response = $this->api($method, $uri, $token);

            $response->assertStatus(401)->assertJsonStructure(['message']);
            $this->assertNull($response->headers->get('Location'));
        }
        $this->assertSame(1, $user->tokens()->count(), 'Permintaan tak sah tidak boleh mencabut token siapa pun.');
    }

    // --- batas laju ----------------------------------------------------------------------------------

    public function test_refresh_is_rate_limited_per_device_lineage_not_per_account(): void
    {
        $user = $this->syncUser();
        $bystander = $this->tokenFor($this->syncUser());
        $otherDevice = $this->tokenFor($user, 'android:tablet');
        $token = $this->tokenFor($user, 'android:pixel-1');

        for ($i = 0; $i < 10; $i++) {
            $token = $this->api('POST', '/api/auth/refresh', $token)->assertOk()->json('token');
        }

        $blocked = $this->api('POST', '/api/auth/refresh', $token)->assertStatus(429);
        $this->assertNotNull($blocked->headers->get('Retry-After'));
        // Token yang ditolak throttle tidak ikut dicabut dan tetap sah untuk jalur lain.
        $this->assertSame(2, $user->tokens()->count());
        $this->api('GET', '/api/sync/pull', $token)->assertOk();
        // Perangkat lain di akun yang sama dan akun lain tidak terpengaruh.
        $this->api('POST', '/api/auth/refresh', $otherDevice)->assertOk();
        $this->api('POST', '/api/auth/refresh', $bystander)->assertOk();
    }

    public function test_exhausting_the_refresh_limit_does_not_block_logout_or_revoke_all(): void
    {
        $user = $this->syncUser();
        // Pemegang token curian (garis keturunan pixel-1) menghabiskan jatah refresh...
        $stolen = $this->tokenFor($user, 'android:pixel-1');
        for ($i = 0; $i < 10; $i++) {
            $stolen = $this->api('POST', '/api/auth/refresh', $stolen)->assertOk()->json('token');
        }
        $this->api('POST', '/api/auth/refresh', $stolen)->assertStatus(429);

        // ...tetapi korban tetap bisa keluar: logout di perangkat itu, lalu cabut semua dari perangkat lain.
        $victim = $this->tokenFor($user, 'android:tablet');
        $this->api('POST', '/api/auth/logout', $stolen)->assertNoContent();
        $this->api('DELETE', '/api/auth/tokens', $victim)->assertNoContent();

        $this->assertSame(0, $user->tokens()->count());
    }

    public function test_logout_and_revoke_all_have_no_rate_limit_of_their_own(): void
    {
        $user = $this->syncUser();

        for ($i = 0; $i < 15; $i++) {
            $this->api('POST', '/api/auth/logout', $this->tokenFor($user))->assertNoContent();
            $this->api('DELETE', '/api/auth/tokens', $this->tokenFor($user))->assertNoContent();
        }

        $this->assertSame(0, $user->tokens()->count());
    }

    // --- log -------------------------------------------------------------------------------------------

    public function test_no_raw_token_is_ever_written_to_the_log(): void
    {
        $logged = [];
        Event::listen(MessageLogged::class, function (MessageLogged $event) use (&$logged) {
            $logged[] = $event->message.' '.json_encode($event->context);
        });
        $user = $this->syncUser();
        $first = $this->tokenFor($user, 'android:pixel-1');
        $second = $this->tokenFor($user, 'android:tablet');

        $rotated = $this->api('POST', '/api/auth/refresh', $first)->assertOk()->json('token');
        $this->api('POST', '/api/auth/refresh', $first)->assertUnauthorized(); // token yang sudah dicabut
        $this->api('POST', '/api/auth/logout', $second)->assertNoContent();
        $this->api('DELETE', '/api/auth/tokens', $rotated)->assertNoContent();

        $secrets = array_map(fn (string $t) => explode('|', $t, 2)[1], [$first, $second, $rotated]);
        foreach ($logged as $line) {
            foreach ($secrets as $secret) {
                $this->assertStringNotContainsString($secret, $line);
                $this->assertStringNotContainsString(hash('sha256', $secret), $line);
            }
        }
    }

    // --- kata sandi ------------------------------------------------------------------------------------

    private function userWithPasswordAndTokens(): array
    {
        $user = User::factory()->create(['password' => self::PASSWORD, 'email' => 'budi@example.com']);

        return [$user, [$this->tokenFor($user, 'android:pixel-1'), $this->tokenFor($user, 'android:tablet')]];
    }

    private function changePassword(User $user, string $current): Testable
    {
        return Livewire::actingAs($user)->test(Profil::class)
            ->set('current_password', $current)
            ->set('password', 'sandi-baru-yang-aman')
            ->set('password_confirmation', 'sandi-baru-yang-aman')
            ->call('savePassword');
    }

    public function test_changing_the_password_revokes_every_api_token_of_the_account(): void
    {
        [$user, $tokens] = $this->userWithPasswordAndTokens();
        $other = $this->tokenFor(User::factory()->create(['plans' => ['sync']]));

        $this->changePassword($user, self::PASSWORD)->assertHasNoErrors()->assertSet('passwordSaved', 'changed');

        $this->assertSame(0, $user->tokens()->count());
        // Sesi web yang sedang dipakai tidak diputus (diperiksa sebelum api() melupakan guard).
        $this->assertAuthenticatedAs($user);
        foreach ($tokens as $token) {
            $this->api('POST', '/api/auth/refresh', $token)->assertUnauthorized();
        }
        // Akun lain tidak tersentuh.
        $this->api('GET', '/api/sync/pull', $other)->assertOk();
    }

    public function test_a_wrong_current_password_revokes_nothing(): void
    {
        [$user, $tokens] = $this->userWithPasswordAndTokens();

        $this->changePassword($user, 'sandi-lama-yang-salah')->assertHasErrors(['current_password']);

        $this->assertSame(2, $user->tokens()->count());
        $this->api('POST', '/api/auth/refresh', $tokens[0])->assertOk();
    }

    public function test_setting_a_first_password_on_a_google_account_keeps_the_api_tokens(): void
    {
        $user = User::factory()->create(['google_sub' => 'g-1']);
        $user->forceFill(['password' => null])->save();
        $token = $this->tokenFor($user);

        Livewire::actingAs($user)->test(Profil::class)
            ->set('password', 'sandi-baru-yang-aman')
            ->set('password_confirmation', 'sandi-baru-yang-aman')
            ->call('savePassword')
            ->assertSet('passwordSaved', 'set');

        $this->assertSame(1, $user->tokens()->count());
        $this->api('POST', '/api/auth/refresh', $token)->assertOk();
    }

    public function test_resetting_the_password_with_an_email_code_revokes_every_api_token(): void
    {
        Mail::fake();
        config(['rizqflow.email_codes_enabled' => true]);
        [$user, $tokens] = $this->userWithPasswordAndTokens();
        $other = $this->tokenFor(User::factory()->create(['plans' => ['sync']]));
        $this->post(route('password.send'), ['email' => 'budi@example.com']);
        $code = Mail::sent(CodeMail::class, fn (CodeMail $m) => $m->purpose === EmailCode::PURPOSE_RESET)->last()->code;

        $this->post(route('password.update'), [
            'email' => 'budi@example.com',
            'code' => $code,
            'password' => 'sandi-baru-yang-aman',
            'password_confirmation' => 'sandi-baru-yang-aman',
        ])->assertRedirect(route('login'));

        $this->assertSame(0, $user->tokens()->count());
        foreach ($tokens as $token) {
            $this->api('POST', '/api/auth/refresh', $token)->assertUnauthorized();
        }
        $this->api('GET', '/api/sync/pull', $other)->assertOk();
    }
}
