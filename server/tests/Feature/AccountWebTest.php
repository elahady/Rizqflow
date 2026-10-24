<?php

namespace Tests\Feature;

use App\Mail\AccountExistsMail;
use App\Mail\CodeMail;
use App\Models\EmailCode;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Session\TokenMismatchException;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Hash;
use Illuminate\Support\Facades\Mail;
use Illuminate\Support\Facades\Route;
use Tests\TestCase;

/** Masuk email+sandi, Daftar, verifikasi email, lupa/atur ulang kata sandi, dan saklar email_codes_enabled. */
class AccountWebTest extends TestCase
{
    use RefreshDatabase;

    private const PASSWORD = 'kopi-susu-gula-aren';

    protected function setUp(): void
    {
        parent::setUp();
        Mail::fake();
        config(['rizqflow.email_codes_enabled' => true]);
    }

    private function registerData(array $overrides = []): array
    {
        return $overrides + [
            'name' => 'Budi',
            'email' => 'budi@example.com',
            'password' => self::PASSWORD,
            'password_confirmation' => self::PASSWORD,
        ];
    }

    private function lastCode(string $purpose = EmailCode::PURPOSE_VERIFY): string
    {
        return Mail::sent(CodeMail::class, fn (CodeMail $m) => $m->purpose === $purpose)->last()->code;
    }

    private function verifiedUser(array $attrs = []): User
    {
        return User::factory()->create(['email' => 'budi@example.com', 'name' => 'Budi', 'password' => self::PASSWORD] + $attrs);
    }

    private function googleOnlyUser(): User
    {
        $user = User::factory()->create(['email' => 'budi@example.com', 'name' => 'Budi', 'google_sub' => 'g-1']);
        $user->forceFill(['password' => null])->save();

        return $user;
    }

    // --- Masuk ---

    public function test_login_with_verified_email_and_password_works(): void
    {
        $user = $this->verifiedUser();

        $this->post(route('login.attempt'), ['email' => 'budi@example.com', 'password' => self::PASSWORD])
            ->assertRedirect(route('denah'));

        $this->assertAuthenticatedAs($user);
    }

    public function test_login_email_is_normalized(): void
    {
        $user = $this->verifiedUser();

        $this->post(route('login.attempt'), ['email' => '  Budi@Example.COM ', 'password' => self::PASSWORD])
            ->assertRedirect(route('denah'));

        $this->assertAuthenticatedAs($user);
    }

    public function test_login_returns_to_the_intended_page(): void
    {
        $this->verifiedUser();

        $this->get(route('profil'))->assertRedirect(route('login'));
        $this->post(route('login.attempt'), ['email' => 'budi@example.com', 'password' => self::PASSWORD])
            ->assertRedirect(route('profil'));
    }

    public function test_wrong_login_has_the_same_message_for_known_unknown_and_google_only_emails(): void
    {
        $this->verifiedUser();
        User::factory()->create(['email' => 'g@example.com', 'google_sub' => 'g-2', 'password' => null]);

        foreach (['budi@example.com', 'asing@example.com', 'g@example.com'] as $email) {
            $this->post(route('login.attempt'), ['email' => $email, 'password' => 'salah-banget-sandinya'])
                ->assertSessionHasErrors(['email' => 'Email atau kata sandi salah.']);
        }
        $this->assertGuest();
    }

    public function test_google_only_account_cannot_log_in_with_a_password(): void
    {
        $this->googleOnlyUser();

        $this->post(route('login.attempt'), ['email' => 'budi@example.com', 'password' => self::PASSWORD])
            ->assertSessionHasErrors(['email' => 'Email atau kata sandi salah.']);
        $this->assertGuest();
    }

    public function test_login_page_shows_the_error_in_the_card(): void
    {
        $this->from(route('login'))->followingRedirects()->post(route('login.attempt'), ['email' => 'budi@example.com', 'password' => 'salah-banget-sandinya'])
            ->assertSee('Email atau kata sandi salah.')
            ->assertSee('au-error', false);
    }

    public function test_unverified_account_cannot_log_in_and_is_sent_to_verification(): void
    {
        $this->post(route('register.store'), $this->registerData());
        $this->travel(61)->seconds();

        $this->post(route('login.attempt'), ['email' => 'budi@example.com', 'password' => self::PASSWORD])
            ->assertRedirect(route('verification.show'))
            ->assertSessionHas('verification_email', 'budi@example.com');

        $this->assertGuest();
        Mail::assertSent(CodeMail::class, 2); // daftar + kode baru saat coba masuk
    }

    public function test_unverified_account_with_wrong_password_learns_nothing(): void
    {
        $this->post(route('register.store'), $this->registerData());

        $this->post(route('login.attempt'), ['email' => 'budi@example.com', 'password' => 'salah-banget-sandinya'])
            ->assertSessionHasErrors(['email' => 'Email atau kata sandi salah.']);
    }

    public function test_repeated_failed_logins_are_locked_out(): void
    {
        $this->verifiedUser();

        foreach (range(1, 5) as $i) {
            $this->post(route('login.attempt'), ['email' => 'budi@example.com', 'password' => 'salah-banget-sandinya']);
        }

        $this->post(route('login.attempt'), ['email' => 'budi@example.com', 'password' => self::PASSWORD])
            ->assertSessionHasErrors('email');
        $this->assertStringContainsString('Terlalu banyak', session('errors')->first('email'));
        $this->assertGuest();
    }

    public function test_logout_shows_the_notice_and_session_expiry_shows_its_own(): void
    {
        $this->actingAs($this->verifiedUser())->post(route('logout'))
            ->assertRedirect(route('login'))
            ->assertSessionHas('status', 'Kamu sudah keluar.');

        $this->withSession(['status' => 'Sesimu berakhir karena lama tidak dipakai. Masuk lagi untuk melanjutkan.'])
            ->get(route('login'))
            ->assertSee('Sesimu berakhir karena lama tidak dipakai.');
    }

    public function test_expired_csrf_token_redirects_to_login_with_the_session_notice(): void
    {
        Route::middleware('web')->post('/uji-sesi-berakhir', fn () => throw new TokenMismatchException('CSRF token mismatch.'));

        $this->post('/uji-sesi-berakhir')
            ->assertRedirect(route('login'))
            ->assertSessionHas('status', fn (string $s) => str_contains($s, 'Sesimu berakhir'));
    }

    // --- Daftar dan verifikasi ---

    public function test_register_page_has_the_form(): void
    {
        $this->get(route('register'))->assertOk()
            ->assertSee('name="name"', false)
            ->assertSee('name="email"', false)
            ->assertSee('name="password_confirmation"', false)
            ->assertSee(route('login'), false);
    }

    public function test_register_creates_an_unverified_account_does_not_log_in_and_sends_a_code(): void
    {
        $this->post(route('register.store'), $this->registerData(['email' => 'Budi@Example.com']))
            ->assertRedirect(route('verification.show'))
            ->assertSessionHas('verification_email', 'budi@example.com');

        $this->assertGuest();
        $user = User::sole();
        $this->assertSame('budi@example.com', $user->email);
        $this->assertNull($user->email_verified_at);
        $this->assertTrue(Hash::check(self::PASSWORD, $user->password));
        Mail::assertSent(CodeMail::class, 1);
        Mail::assertSent(CodeMail::class, fn (CodeMail $m) => $m->hasTo('budi@example.com') && strlen($m->code) === 6);
    }

    public function test_register_validates_input(): void
    {
        $this->post(route('register.store'), $this->registerData(['password' => 'password123', 'password_confirmation' => 'password123']))
            ->assertSessionHasErrors('password');
        $this->post(route('register.store'), $this->registerData(['password_confirmation' => 'beda-sekali-sandinya']))
            ->assertSessionHasErrors('password');
        $this->post(route('register.store'), $this->registerData(['email' => 'bukan-email']))
            ->assertSessionHasErrors('email');
        $this->post(route('register.store'), $this->registerData(['name' => '']))
            ->assertSessionHasErrors('name');

        $this->assertSame(0, User::count());
        Mail::assertNothingSent();
    }

    public function test_register_answers_the_same_for_new_password_google_and_unverified_emails(): void
    {
        $this->verifiedUser(['email' => 'sandi@example.com']);
        User::factory()->create(['email' => 'google@example.com', 'google_sub' => 'g-3', 'password' => null]);

        $responses = [];
        foreach (['baru@example.com', 'sandi@example.com', 'google@example.com'] as $email) {
            $r = $this->post(route('register.store'), $this->registerData(['email' => $email]));
            $r->assertRedirect(route('verification.show'));
            $responses[] = [$r->headers->get('Location'), str_replace($email, 'X', session('status'))];
        }

        $this->assertSame($responses[0], $responses[1]);
        $this->assertSame($responses[0], $responses[2]);
    }

    public function test_register_with_an_existing_account_does_not_touch_it_and_notifies_the_owner(): void
    {
        $existing = $this->verifiedUser();

        $this->post(route('register.store'), $this->registerData(['name' => 'Penyerang', 'password' => 'sandi-penyerang-1', 'password_confirmation' => 'sandi-penyerang-1']));

        $existing->refresh();
        $this->assertSame('Budi', $existing->name);
        $this->assertTrue(Hash::check(self::PASSWORD, $existing->password));
        $this->assertSame(1, User::count());
        Mail::assertSent(AccountExistsMail::class, fn (AccountExistsMail $m) => $m->hasTo('budi@example.com') && $m->hasPassword);
        Mail::assertNotSent(CodeMail::class);
    }

    public function test_register_with_a_google_account_email_points_to_google_and_profile_without_leaking(): void
    {
        $google = $this->googleOnlyUser();

        $this->post(route('register.store'), $this->registerData())->assertRedirect(route('verification.show'));

        $this->assertFalse($google->fresh()->hasPassword());
        $this->assertGuest();
        Mail::assertSent(AccountExistsMail::class, fn (AccountExistsMail $m) => ! $m->hasPassword);
        $mail = (new AccountExistsMail('Budi', false))->render();
        $this->assertStringContainsString('Google', $mail);
        $this->assertStringContainsString('Profil', $mail);
    }

    public function test_register_again_before_verifying_replaces_the_pending_account_and_the_old_code(): void
    {
        $this->post(route('register.store'), $this->registerData());
        $first = $this->lastCode();
        $this->travel(61)->seconds();

        $this->post(route('register.store'), $this->registerData(['name' => 'Budi Baru']));

        $this->assertSame(1, User::count());
        $this->assertSame('Budi Baru', User::sole()->name);
        $second = $this->lastCode();
        if ($first !== $second) {
            $this->post(route('verification.verify'), ['email' => 'budi@example.com', 'code' => $first])
                ->assertSessionHasErrors('code');
        }
        $this->post(route('verification.verify'), ['email' => 'budi@example.com', 'code' => $second])
            ->assertRedirect(route('denah'));
    }

    public function test_verification_page_shows_the_email_code_field_and_resend(): void
    {
        $this->withSession(['verification_email' => 'budi@example.com'])
            ->get(route('verification.show'))->assertOk()
            ->assertSee('value="budi@example.com"', false)
            ->assertSee('name="code"', false)
            ->assertSee('Kirim ulang kode')
            ->assertSee(route('verification.resend'), false);
    }

    public function test_correct_code_verifies_and_logs_in(): void
    {
        $this->post(route('register.store'), $this->registerData());

        $this->post(route('verification.verify'), ['email' => 'budi@example.com', 'code' => $this->lastCode()])
            ->assertRedirect(route('denah'));

        $this->assertAuthenticatedAs(User::sole());
        $this->assertNotNull(User::sole()->email_verified_at);
    }

    public function test_after_verifying_the_password_login_works(): void
    {
        $this->post(route('register.store'), $this->registerData());
        $this->post(route('verification.verify'), ['email' => 'budi@example.com', 'code' => $this->lastCode()]);
        auth()->logout();

        $this->post(route('login.attempt'), ['email' => 'budi@example.com', 'password' => self::PASSWORD])
            ->assertRedirect(route('denah'));
        $this->assertAuthenticatedAs(User::sole());
    }

    public function test_wrong_code_does_not_verify(): void
    {
        $this->post(route('register.store'), $this->registerData());
        $wrong = $this->lastCode() === '000000' ? '111111' : '000000';

        $this->post(route('verification.verify'), ['email' => 'budi@example.com', 'code' => $wrong])
            ->assertSessionHasErrors(['code' => 'Kode salah.']);

        $this->assertGuest();
        $this->assertNull(User::sole()->email_verified_at);
    }

    public function test_expired_code_is_rejected_with_its_own_message(): void
    {
        $this->post(route('register.store'), $this->registerData());
        $code = $this->lastCode();

        $this->travel(11)->minutes();
        $this->post(route('verification.verify'), ['email' => 'budi@example.com', 'code' => $code])
            ->assertSessionHasErrors(['code' => 'Kode sudah kedaluwarsa. Minta kode baru.']);
        $this->assertGuest();
    }

    public function test_code_still_works_just_before_expiry(): void
    {
        $this->post(route('register.store'), $this->registerData());
        $code = $this->lastCode();

        $this->travel(9)->minutes();
        $this->post(route('verification.verify'), ['email' => 'budi@example.com', 'code' => $code])
            ->assertRedirect(route('denah'));
    }

    public function test_code_is_single_use(): void
    {
        $this->post(route('register.store'), $this->registerData());
        $code = $this->lastCode();

        $this->post(route('verification.verify'), ['email' => 'budi@example.com', 'code' => $code])->assertRedirect(route('denah'));
        auth()->logout();
        $this->post(route('verification.verify'), ['email' => 'budi@example.com', 'code' => $code])->assertSessionHasErrors('code');
        $this->assertGuest();
    }

    public function test_a_code_is_burned_after_five_wrong_attempts(): void
    {
        $this->post(route('register.store'), $this->registerData());
        $code = $this->lastCode();
        $wrong = $code === '000000' ? '111111' : '000000';

        foreach (range(1, 5) as $i) {
            $this->post(route('verification.verify'), ['email' => 'budi@example.com', 'code' => $wrong]);
        }

        // Kode yang benar pun sudah hangus: harus minta kode baru.
        $this->post(route('verification.verify'), ['email' => 'budi@example.com', 'code' => $code])
            ->assertSessionHasErrors('code');
        $this->assertGuest();
    }

    public function test_code_attempts_are_rate_limited_per_email(): void
    {
        $this->post(route('register.store'), $this->registerData());

        foreach (range(1, 10) as $i) {
            $this->post(route('verification.verify'), ['email' => 'budi@example.com', 'code' => '000000']);
        }

        $this->post(route('verification.verify'), ['email' => 'budi@example.com', 'code' => $this->lastCode()])
            ->assertSessionHasErrors('code');
        $this->assertStringContainsString('Terlalu banyak', session('errors')->first('code'));
        $this->assertGuest();
    }

    public function test_verifying_an_unknown_or_already_verified_email_fails_like_a_wrong_code(): void
    {
        $this->verifiedUser();

        $this->post(route('verification.verify'), ['email' => 'asing@example.com', 'code' => '123456'])
            ->assertSessionHasErrors(['code' => 'Kode salah.']);
        $this->post(route('verification.verify'), ['email' => 'budi@example.com', 'code' => '123456'])
            ->assertSessionHasErrors(['code' => 'Kode salah.']);
        $this->assertGuest();
    }

    public function test_resend_answers_the_same_for_known_and_unknown_emails_and_sends_only_for_pending_accounts(): void
    {
        $this->post(route('register.store'), $this->registerData());
        $this->travel(61)->seconds();

        $known = $this->post(route('verification.resend'), ['email' => 'budi@example.com']);
        $statusKnown = session('status');
        $unknown = $this->post(route('verification.resend'), ['email' => 'asing@example.com']);

        $this->assertSame($statusKnown, session('status'));
        $this->assertSame($known->headers->get('Location'), $unknown->headers->get('Location'));
        Mail::assertSent(CodeMail::class, 2); // daftar + kirim ulang untuk akun yang menunggu
    }

    public function test_resend_has_a_cooldown_and_an_hourly_cap(): void
    {
        $this->post(route('register.store'), $this->registerData());

        $this->post(route('verification.resend'), ['email' => 'budi@example.com']); // dalam jeda 60 detik
        Mail::assertSent(CodeMail::class, 1);

        foreach (range(1, 10) as $i) {
            $this->travel(61)->seconds();
            $this->post(route('verification.resend'), ['email' => 'budi@example.com']);
        }
        Mail::assertSent(CodeMail::class, 5); // batas 5 per jam
    }

    public function test_resending_invalidates_the_previous_code(): void
    {
        $this->post(route('register.store'), $this->registerData());
        $old = $this->lastCode();
        $this->travel(61)->seconds();
        $this->post(route('verification.resend'), ['email' => 'budi@example.com']);
        $new = $this->lastCode();

        if ($old !== $new) {
            $this->post(route('verification.verify'), ['email' => 'budi@example.com', 'code' => $old])->assertSessionHasErrors('code');
        }
        $this->post(route('verification.verify'), ['email' => 'budi@example.com', 'code' => $new])->assertRedirect(route('denah'));
    }

    public function test_the_plain_code_is_never_stored(): void
    {
        $this->post(route('register.store'), $this->registerData());

        $record = EmailCode::sole();
        $this->assertNotSame($this->lastCode(), $record->code_hash);
        $this->assertSame(64, strlen($record->code_hash));
    }

    // --- Lupa dan atur ulang kata sandi ---

    public function test_forgot_page_is_reachable_from_login_and_has_the_form(): void
    {
        $this->get(route('login'))->assertSee(route('password.forgot'), false);
        $this->get(route('password.forgot'))->assertOk()->assertSee('name="email"', false);
    }

    public function test_forgot_answers_the_same_for_every_email_and_mails_only_verified_accounts(): void
    {
        $this->verifiedUser();
        $this->googleOnlyUser2();
        User::factory()->unverified()->create(['email' => 'pending@example.com']);

        $answers = [];
        foreach (['budi@example.com', 'asing@example.com', 'pending@example.com', 'g@example.com'] as $email) {
            $r = $this->post(route('password.send'), ['email' => $email]);
            $r->assertRedirect(route('password.reset'));
            $answers[] = str_replace($email, 'X', session('status'));
        }

        $this->assertCount(1, array_unique($answers));
        Mail::assertSent(CodeMail::class, 2); // budi dan akun Google yang emailnya terverifikasi
        Mail::assertSent(CodeMail::class, fn (CodeMail $m) => $m->hasTo('budi@example.com') && $m->purpose === EmailCode::PURPOSE_RESET);
        Mail::assertNotSent(CodeMail::class, fn (CodeMail $m) => $m->hasTo('pending@example.com') || $m->hasTo('asing@example.com'));
    }

    private function googleOnlyUser2(): void
    {
        User::factory()->create(['email' => 'g@example.com', 'google_sub' => 'g-9', 'password' => null]);
    }

    public function test_reset_with_the_code_changes_the_password_and_allows_login_with_it(): void
    {
        $user = $this->verifiedUser();
        $this->post(route('password.send'), ['email' => 'budi@example.com']);

        $this->post(route('password.update'), [
            'email' => 'budi@example.com',
            'code' => $this->lastCode(EmailCode::PURPOSE_RESET),
            'password' => 'sandi-baru-yang-aman',
            'password_confirmation' => 'sandi-baru-yang-aman',
        ])->assertRedirect(route('login'))->assertSessionHas('status', 'Kata sandi diganti. Silakan masuk dengan kata sandi baru.');

        $this->assertTrue(Hash::check('sandi-baru-yang-aman', $user->fresh()->password));
        $this->post(route('login.attempt'), ['email' => 'budi@example.com', 'password' => self::PASSWORD])->assertSessionHasErrors('email');
        $this->post(route('login.attempt'), ['email' => 'budi@example.com', 'password' => 'sandi-baru-yang-aman'])->assertRedirect(route('denah'));
    }

    public function test_reset_works_for_a_google_account_and_gives_it_a_password(): void
    {
        $user = $this->googleOnlyUser();
        $this->post(route('password.send'), ['email' => 'budi@example.com']);

        $this->post(route('password.update'), [
            'email' => 'budi@example.com',
            'code' => $this->lastCode(EmailCode::PURPOSE_RESET),
            'password' => 'sandi-baru-yang-aman',
            'password_confirmation' => 'sandi-baru-yang-aman',
        ])->assertRedirect(route('login'));

        $this->assertTrue($user->fresh()->hasPassword());
        $this->assertSame('g-1', $user->fresh()->google_sub);
    }

    public function test_reset_revokes_api_tokens_and_web_sessions(): void
    {
        config(['session.driver' => 'database']);
        $user = $this->verifiedUser();
        $user->createToken('android:hp');
        $oldRemember = $user->remember_token;
        DB::table('sessions')->insert(['id' => 'sesi-lama', 'user_id' => $user->id, 'payload' => 'x', 'last_activity' => time()]);
        $this->post(route('password.send'), ['email' => 'budi@example.com']);

        $this->post(route('password.update'), [
            'email' => 'budi@example.com',
            'code' => $this->lastCode(EmailCode::PURPOSE_RESET),
            'password' => 'sandi-baru-yang-aman',
            'password_confirmation' => 'sandi-baru-yang-aman',
        ])->assertRedirect(route('login'));

        $this->assertSame(0, $user->tokens()->count());
        $this->assertSame(0, DB::table('sessions')->where('user_id', $user->id)->count());
        $this->assertNotSame($oldRemember, $user->fresh()->remember_token);
    }

    public function test_reset_with_a_wrong_code_fails_and_keeps_the_old_password(): void
    {
        $user = $this->verifiedUser();
        $this->post(route('password.send'), ['email' => 'budi@example.com']);
        $wrong = $this->lastCode(EmailCode::PURPOSE_RESET) === '000000' ? '111111' : '000000';

        $this->post(route('password.update'), [
            'email' => 'budi@example.com', 'code' => $wrong,
            'password' => 'sandi-baru-yang-aman', 'password_confirmation' => 'sandi-baru-yang-aman',
        ])->assertSessionHasErrors(['code' => 'Kode salah.']);

        $this->assertTrue(Hash::check(self::PASSWORD, $user->fresh()->password));
    }

    public function test_reset_with_an_expired_code_fails(): void
    {
        $this->verifiedUser();
        $this->post(route('password.send'), ['email' => 'budi@example.com']);
        $code = $this->lastCode(EmailCode::PURPOSE_RESET);

        $this->travel(11)->minutes();
        $this->post(route('password.update'), [
            'email' => 'budi@example.com', 'code' => $code,
            'password' => 'sandi-baru-yang-aman', 'password_confirmation' => 'sandi-baru-yang-aman',
        ])->assertSessionHasErrors(['code' => 'Kode sudah kedaluwarsa. Minta kode baru.']);
    }

    public function test_a_weak_new_password_is_rejected_without_burning_the_code(): void
    {
        $user = $this->verifiedUser();
        $this->post(route('password.send'), ['email' => 'budi@example.com']);
        $code = $this->lastCode(EmailCode::PURPOSE_RESET);

        $this->post(route('password.update'), [
            'email' => 'budi@example.com', 'code' => $code,
            'password' => 'password123', 'password_confirmation' => 'password123',
        ])->assertSessionHasErrors('password');

        $this->post(route('password.update'), [
            'email' => 'budi@example.com', 'code' => $code,
            'password' => 'sandi-baru-yang-aman', 'password_confirmation' => 'sandi-baru-yang-aman',
        ])->assertRedirect(route('login'));
        $this->assertTrue(Hash::check('sandi-baru-yang-aman', $user->fresh()->password));
    }

    public function test_a_verification_code_cannot_be_used_to_reset_a_password(): void
    {
        $this->post(route('register.store'), $this->registerData());
        $verifyCode = $this->lastCode();
        $user = User::sole();
        $user->forceFill(['email_verified_at' => now()])->save();

        $this->post(route('password.update'), [
            'email' => 'budi@example.com', 'code' => $verifyCode,
            'password' => 'sandi-baru-yang-aman', 'password_confirmation' => 'sandi-baru-yang-aman',
        ])->assertSessionHasErrors('code');
        $this->assertTrue(Hash::check(self::PASSWORD, $user->fresh()->password));
    }

    public function test_reset_attempts_are_rate_limited(): void
    {
        $this->verifiedUser();
        $this->post(route('password.send'), ['email' => 'budi@example.com']);

        foreach (range(1, 10) as $i) {
            $this->post(route('password.update'), [
                'email' => 'budi@example.com', 'code' => '000000',
                'password' => 'sandi-baru-yang-aman', 'password_confirmation' => 'sandi-baru-yang-aman',
            ]);
        }

        $this->post(route('password.update'), [
            'email' => 'budi@example.com', 'code' => $this->lastCode(EmailCode::PURPOSE_RESET),
            'password' => 'sandi-baru-yang-aman', 'password_confirmation' => 'sandi-baru-yang-aman',
        ])->assertSessionHasErrors('code');
        $this->assertStringContainsString('Terlalu banyak', session('errors')->first('code'));
    }

    public function test_forgot_is_capped_at_three_codes_per_hour(): void
    {
        $this->verifiedUser();

        foreach (range(1, 6) as $i) {
            $this->post(route('password.send'), ['email' => 'budi@example.com']);
        }

        Mail::assertSent(CodeMail::class, 3);
    }

    // --- Saklar email_codes_enabled ---

    public function test_when_the_switch_is_off_login_hides_register_and_forgot_links(): void
    {
        config(['rizqflow.email_codes_enabled' => false]);

        $this->get(route('login'))->assertOk()
            ->assertDontSee('Lupa kata sandi?')
            ->assertDontSee('Belum punya akun?')
            ->assertDontSee(route('register'), false)
            ->assertSee('name="password"', false);
    }

    public function test_when_the_switch_is_off_email_routes_redirect_to_login_with_a_friendly_notice(): void
    {
        config(['rizqflow.email_codes_enabled' => false]);

        $gets = [route('register'), route('password.forgot'), route('password.reset'), route('verification.show')];
        foreach ($gets as $url) {
            $this->get($url)->assertRedirect(route('login'))
                ->assertSessionHas('status', fn (string $s) => str_contains($s, 'belum tersedia'));
        }

        $this->post(route('register.store'), $this->registerData())->assertRedirect(route('login'));
        $this->post(route('password.send'), ['email' => 'budi@example.com'])->assertRedirect(route('login'));
        $this->post(route('verification.resend'), ['email' => 'budi@example.com'])->assertRedirect(route('login'));

        $this->assertSame(0, User::count());
        Mail::assertNothingSent();
    }

    public function test_when_the_switch_is_off_password_login_still_works_for_verified_accounts(): void
    {
        config(['rizqflow.email_codes_enabled' => false]);
        $user = $this->verifiedUser();

        $this->post(route('login.attempt'), ['email' => 'budi@example.com', 'password' => self::PASSWORD])
            ->assertRedirect(route('denah'));
        $this->assertAuthenticatedAs($user);
    }

    public function test_when_the_switch_is_off_an_unverified_account_gets_a_friendly_error_and_no_mail(): void
    {
        $this->verifiedUser(['email_verified_at' => null]);
        config(['rizqflow.email_codes_enabled' => false]);

        $this->post(route('login.attempt'), ['email' => 'budi@example.com', 'password' => self::PASSWORD])
            ->assertSessionHasErrors('email');
        $this->assertStringContainsString('belum diverifikasi', session('errors')->first('email'));
        $this->assertGuest();
        Mail::assertNothingSent();
    }

    public function test_when_the_switch_is_off_google_login_and_profile_password_setting_still_work(): void
    {
        config(['rizqflow.email_codes_enabled' => false]);
        $user = $this->googleOnlyUser();

        $this->actingAs($user)->get(route('profil'))->assertOk()->assertSee('Tetapkan kata sandi');
    }

    /** Memuat config/rizqflow.php dengan env tertentu untuk menguji nilai bawaan saklar. */
    private function switchFor(array $env): bool
    {
        $keys = ['EMAIL_CODES_ENABLED', 'MAIL_MAILER', 'APP_ENV'];
        $backup = [];
        foreach ($keys as $k) {
            $backup[$k] = [$_ENV[$k] ?? null, $_SERVER[$k] ?? null, getenv($k)];
            unset($_ENV[$k], $_SERVER[$k]);
            putenv($k);
        }
        foreach ($env as $k => $v) {
            $_ENV[$k] = $_SERVER[$k] = $v;
            putenv("$k=$v");
        }

        try {
            return (bool) (require config_path('rizqflow.php'))['email_codes_enabled'];
        } finally {
            foreach ($keys as $k) {
                unset($_ENV[$k], $_SERVER[$k]);
                putenv($k);
                [$e, $s, $g] = $backup[$k];
                if ($e !== null) {
                    $_ENV[$k] = $e;
                }
                if ($s !== null) {
                    $_SERVER[$k] = $s;
                }
                if ($g !== false) {
                    putenv("$k=$g");
                }
            }
        }
    }

    public function test_switch_default_follows_mailer_and_environment(): void
    {
        $this->assertFalse($this->switchFor(['APP_ENV' => 'production']), 'produksi tanpa MAIL_MAILER (default log)');
        $this->assertFalse($this->switchFor(['APP_ENV' => 'production', 'MAIL_MAILER' => 'log']));
        $this->assertFalse($this->switchFor(['APP_ENV' => 'production', 'MAIL_MAILER' => 'array']));
        $this->assertTrue($this->switchFor(['APP_ENV' => 'production', 'MAIL_MAILER' => 'smtp']));
        $this->assertTrue($this->switchFor(['APP_ENV' => 'local', 'MAIL_MAILER' => 'log']));
        $this->assertTrue($this->switchFor(['APP_ENV' => 'testing', 'MAIL_MAILER' => 'array']));
    }

    public function test_switch_can_be_forced_through_env(): void
    {
        $this->assertTrue($this->switchFor(['APP_ENV' => 'production', 'EMAIL_CODES_ENABLED' => 'true']));
        $this->assertFalse($this->switchFor(['APP_ENV' => 'local', 'EMAIL_CODES_ENABLED' => 'false', 'MAIL_MAILER' => 'smtp']));
    }
}
