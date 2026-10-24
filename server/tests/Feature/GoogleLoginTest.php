<?php

namespace Tests\Feature;

use App\Auth\GoogleIdTokenVerifier;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Tests\TestCase;

class GoogleLoginTest extends TestCase
{
    use RefreshDatabase;

    /** Token "valid-<sub>" sah dengan klaim di bawah; token lain ditolak. */
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

    public function test_first_google_login_creates_account_keyed_by_sub(): void
    {
        $this->fakeGoogle();

        $this->post(route('login.google'), ['credential' => 'valid-123'])->assertRedirect(route('denah'));

        $user = User::sole();
        $this->assertSame('123', $user->google_sub);
        $this->assertSame('roziq@example.com', $user->email);
        $this->assertSame('Roziq', $user->name);
        $this->assertNull($user->password);
        $this->assertAuthenticatedAs($user);
    }

    public function test_returning_user_is_found_by_sub_even_after_email_change(): void
    {
        $existing = User::factory()->create(['google_sub' => '123', 'email' => 'lama@example.com']);
        $this->fakeGoogle(['email' => 'baru@example.com']);

        $this->post(route('login.google'), ['credential' => 'valid-123']);

        $this->assertSame(1, User::count());
        $this->assertSame('baru@example.com', $existing->fresh()->email);
        $this->assertAuthenticatedAs($existing);
    }

    public function test_existing_account_with_same_email_is_linked(): void
    {
        $existing = User::factory()->create(['email' => 'roziq@example.com']);
        $this->fakeGoogle();

        $this->post(route('login.google'), ['credential' => 'valid-123']);

        $this->assertSame('123', $existing->fresh()->google_sub);
        $this->assertAuthenticatedAs($existing);
    }

    public function test_invalid_token_is_rejected(): void
    {
        $this->fakeGoogle();

        $this->from(route('login'))->post(route('login.google'), ['credential' => 'palsu'])
            ->assertRedirect(route('login'))
            ->assertSessionHasErrors('google');

        $this->assertGuest();
        $this->assertSame(0, User::count());
    }

    public function test_unverified_email_is_rejected(): void
    {
        $this->fakeGoogle(['email_verified' => false]);

        $this->post(route('login.google'), ['credential' => 'valid-123'])->assertSessionHasErrors('google');

        $this->assertGuest();
    }

    public function test_login_page_shows_google_button_with_shared_client_id(): void
    {
        config(['services.google.client_id' => 'abc.apps.googleusercontent.com']);

        $this->get(route('login'))
            ->assertOk()
            ->assertSee('data-client_id="abc.apps.googleusercontent.com"', false)
            ->assertSee('accounts.google.com/gsi/client');
    }

    public function test_google_login_merges_into_password_account_with_same_email_and_keeps_its_password(): void
    {
        $existing = User::factory()->create(['email' => 'roziq@example.com', 'password' => 'kopi-susu-gula-aren']);
        $this->fakeGoogle();

        $this->post(route('login.google'), ['credential' => 'valid-123'])->assertRedirect(route('denah'));

        $this->assertSame(1, User::count());
        $this->assertAuthenticatedAs($existing);
        $existing->refresh();
        $this->assertSame('123', $existing->google_sub);
        $this->assertTrue($existing->hasPassword());
        $this->assertTrue($existing->hasVerifiedEmail());
    }

    public function test_google_email_is_matched_ignoring_case(): void
    {
        $existing = User::factory()->create(['email' => 'roziq@example.com']);
        $this->fakeGoogle(['email' => 'Roziq@Example.com']);

        $this->post(route('login.google'), ['credential' => 'valid-123']);

        $this->assertSame(1, User::count());
        $this->assertAuthenticatedAs($existing);
        $this->assertSame('roziq@example.com', $existing->fresh()->email);
    }

    public function test_google_account_is_created_verified_and_without_password(): void
    {
        $this->fakeGoogle();

        $this->post(route('login.google'), ['credential' => 'valid-123']);

        $user = User::sole();
        $this->assertTrue($user->hasVerifiedEmail());
        $this->assertFalse($user->hasPassword());
    }

    public function test_google_login_onto_unverified_password_account_drops_the_untrusted_password(): void
    {
        // Seseorang mendaftar dengan email ini (belum diverifikasi) dan memasang sandinya sendiri.
        $squatted = User::factory()->unverified()->create(['email' => 'roziq@example.com', 'password' => 'sandi-penyerang-1']);
        $squatted->emailCodes()->create(['purpose' => 'verify', 'code_hash' => 'x', 'expires_at' => now()->addMinutes(5)]);
        $this->fakeGoogle();

        $this->post(route('login.google'), ['credential' => 'valid-123']);

        $squatted->refresh();
        $this->assertAuthenticatedAs($squatted);
        $this->assertTrue($squatted->hasVerifiedEmail());
        $this->assertFalse($squatted->hasPassword());
        $this->assertSame(0, $squatted->emailCodes()->whereNull('consumed_at')->count());
    }
}
