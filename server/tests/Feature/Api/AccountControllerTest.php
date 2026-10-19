<?php

namespace Tests\Feature\Api;

use App\Auth\GoogleIdTokenVerifier;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Laravel\Sanctum\PersonalAccessToken;
use Tests\TestCase;

class AccountControllerTest extends TestCase
{
    use RefreshDatabase;

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
}
