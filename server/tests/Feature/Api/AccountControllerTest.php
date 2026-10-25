<?php

namespace Tests\Feature\Api;

use App\Auth\GoogleIdTokenVerifier;
use App\Mail\CodeMail;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\Mail;
use Laravel\Sanctum\PersonalAccessToken;
use Tests\TestCase;

class AccountControllerTest extends TestCase
{
    use RefreshDatabase;

    private const PASSWORD = 'kopi-susu-gula-aren';

    /** Akun email+sandi terverifikasi; sandinya di-hash oleh cast `hashed`, sama seperti akun web. */
    private function passwordUser(array $attrs = []): User
    {
        return User::factory()->create(['email' => 'budi@example.com', 'name' => 'Budi', 'password' => self::PASSWORD] + $attrs);
    }

    private function loginBody(array $overrides = []): array
    {
        return $overrides + ['email' => 'budi@example.com', 'password' => self::PASSWORD, 'device_id' => 'pixel-1'];
    }

    /** Token "valid-<sub>" sah dengan klaim di bawah; token lain ditolak. Sama pola dengan GoogleLoginTest. */
    private function fakeGoogle(array $claims = []): void
    {
        $this->app->instance(GoogleIdTokenVerifier::class, new class($claims) implements GoogleIdTokenVerifier
        {
            public function __construct(private array $claims) {}

            public function verify(string $idToken): ?array
            {
                if (! str_starts_with($idToken, 'valid-')) {
                    return null;
                }

                return $this->claims + [
                    'sub' => substr($idToken, 6),
                    'email' => 'roziq@example.com',
                    'email_verified' => true,
                    'name' => 'Roziq',
                ];
            }
        });
    }

    public function test_login_creates_account_and_returns_api_token(): void
    {
        $this->fakeGoogle();

        $response = $this->postJson('/api/auth/google', ['credential' => 'valid-123', 'device_id' => 'pixel-1']);

        $response->assertOk()->assertJsonStructure(['token', 'user' => ['id', 'name', 'email']]);
        $user = User::sole();
        $this->assertSame('123', $user->google_sub);
        $this->assertSame('roziq@example.com', $response->json('user.email'));
    }

    public function test_login_is_stateless_and_does_not_create_web_session(): void
    {
        $this->fakeGoogle();

        $this->postJson('/api/auth/google', ['credential' => 'valid-123']);

        $this->assertGuest();
    }

    public function test_returning_device_is_found_by_sub_like_web_login(): void
    {
        $existing = User::factory()->create(['google_sub' => '123', 'email' => 'lama@example.com']);
        $this->fakeGoogle(['email' => 'baru@example.com']);

        $this->postJson('/api/auth/google', ['credential' => 'valid-123']);

        $this->assertSame(1, User::count());
        $this->assertSame('baru@example.com', $existing->fresh()->email);
    }

    public function test_relogin_on_same_device_revokes_previous_token_for_that_device(): void
    {
        $this->fakeGoogle();

        $first = $this->postJson('/api/auth/google', ['credential' => 'valid-123', 'device_id' => 'pixel-1'])->json('token');
        $this->postJson('/api/auth/google', ['credential' => 'valid-123', 'device_id' => 'pixel-1']);

        $this->withHeader('Authorization', "Bearer {$first}")
            ->deleteJson('/api/account')
            ->assertUnauthorized();
    }

    public function test_invalid_credential_is_rejected(): void
    {
        $this->fakeGoogle();

        $this->postJson('/api/auth/google', ['credential' => 'palsu'])
            ->assertUnprocessable()
            ->assertJsonValidationErrors('credential');

        $this->assertSame(0, User::count());
    }

    public function test_delete_account_revokes_token_and_removes_user(): void
    {
        $this->fakeGoogle();
        $token = $this->postJson('/api/auth/google', ['credential' => 'valid-123'])->json('token');

        $this->withHeader('Authorization', "Bearer {$token}")
            ->deleteJson('/api/account')
            ->assertNoContent();

        $this->assertSame(0, User::count());
        $this->assertSame(0, PersonalAccessToken::count());
    }

    public function test_delete_account_requires_a_valid_token(): void
    {
        $this->deleteJson('/api/account')->assertUnauthorized();
    }

    // --- Masuk dengan email+sandi (Tahap 14) ---

    public function test_password_login_issues_api_token_with_expiry_like_google_login(): void
    {
        $this->passwordUser();

        $response = $this->postJson('/api/auth/login', $this->loginBody());

        $response->assertOk()->assertJsonStructure(['token', 'expires_at', 'user' => ['id', 'name', 'email']]);
        $this->assertSame('budi@example.com', $response->json('user.email'));
        $this->assertSame(1, PersonalAccessToken::count());
        $token = PersonalAccessToken::sole();
        $this->assertSame('android:pixel-1', $token->name);
        $this->assertNotNull($token->expires_at);
        $this->assertGuest();
    }

    public function test_token_from_password_login_is_accepted_by_protected_endpoints(): void
    {
        $this->passwordUser();
        $token = $this->postJson('/api/auth/login', $this->loginBody())->json('token');

        $this->withHeader('Authorization', "Bearer {$token}")
            ->postJson('/api/auth/refresh')
            ->assertOk()
            ->assertJsonStructure(['token', 'expires_at']);
    }

    public function test_password_login_ignores_email_case_and_surrounding_spaces_like_web(): void
    {
        $this->passwordUser();

        $this->postJson('/api/auth/login', $this->loginBody(['email' => '  Budi@Example.COM ']))->assertOk();
    }

    public function test_wrong_password_is_rejected_with_the_same_message_for_every_kind_of_email(): void
    {
        $this->passwordUser();
        User::factory()->create(['email' => 'g@example.com', 'google_sub' => 'g-2', 'password' => null]);

        foreach (['budi@example.com', 'asing@example.com', 'g@example.com'] as $email) {
            $this->postJson('/api/auth/login', $this->loginBody(['email' => $email, 'password' => 'salah-banget-sandinya']))
                ->assertUnprocessable()
                ->assertJsonPath('errors.email.0', 'Email atau kata sandi salah.');
        }
        $this->assertSame(0, PersonalAccessToken::count());
    }

    public function test_unverified_account_gets_no_token_and_a_code_is_sent(): void
    {
        Mail::fake();
        config(['rizqflow.email_codes_enabled' => true]);
        $this->passwordUser(['email_verified_at' => null]);

        $response = $this->postJson('/api/auth/login', $this->loginBody());

        $response->assertUnprocessable()->assertJsonValidationErrors('email');
        $this->assertStringContainsString('belum diverifikasi', $response->json('errors.email.0'));
        $this->assertSame(0, PersonalAccessToken::count());
        Mail::assertSent(CodeMail::class, 1);
    }

    public function test_unverified_account_is_refused_without_a_code_when_email_codes_are_disabled(): void
    {
        Mail::fake();
        config(['rizqflow.email_codes_enabled' => false]);
        $this->passwordUser(['email_verified_at' => null]);

        $this->postJson('/api/auth/login', $this->loginBody())
            ->assertUnprocessable()
            ->assertJsonValidationErrors('email');
        $this->assertSame(0, PersonalAccessToken::count());
        Mail::assertNothingSent();
    }

    public function test_password_relogin_on_same_device_revokes_previous_token(): void
    {
        $this->passwordUser();

        $first = $this->postJson('/api/auth/login', $this->loginBody())->json('token');
        $this->postJson('/api/auth/login', $this->loginBody());

        $this->withHeader('Authorization', "Bearer {$first}")
            ->deleteJson('/api/account')
            ->assertUnauthorized();
        $this->assertSame(1, PersonalAccessToken::count());
    }

    public function test_repeated_wrong_passwords_lock_out_even_the_right_one(): void
    {
        $this->passwordUser();

        foreach (range(1, 5) as $i) {
            $this->postJson('/api/auth/login', $this->loginBody(['password' => 'salah-banget-sandinya']));
        }

        $response = $this->postJson('/api/auth/login', $this->loginBody());

        $response->assertUnprocessable()->assertJsonValidationErrors('email');
        $this->assertStringContainsString('Terlalu banyak', $response->json('errors.email.0'));
        $this->assertSame(0, PersonalAccessToken::count());
    }
}
