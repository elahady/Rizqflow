<?php

namespace App\Auth;

use App\Models\EmailCode;
use App\Models\User;
use Illuminate\Support\Facades\Hash;
use Illuminate\Support\Facades\RateLimiter;

/**
 * Aturan akun email+sandi di web: daftar, verifikasi, masuk, atur ulang dan ganti kata sandi, dengan
 * batas laju terpusat. Satu email (dinormalisasi huruf kecil) = satu akun, apa pun jalur masuknya
 * (sandi atau Google, lihat [GoogleAccountResolver]). Pola dari habitflow (AccountService).
 */
class AccountService
{
    public const LOGIN_OK = 'ok';

    public const LOGIN_INVALID = 'invalid';

    public const LOGIN_UNVERIFIED = 'unverified';

    public const LOGIN_THROTTLED = 'throttled';

    public const PASSWORD_OK = 'ok';

    public const PASSWORD_WRONG = 'wrong';

    public const PASSWORD_THROTTLED = 'throttled';

    private const LOGIN_IP_MAX = 5;

    private const LOGIN_EMAIL_MAX = 10;

    public function __construct(private EmailCodeService $codes) {}

    public static function normalizeEmail(string $email): string
    {
        return mb_strtolower(trim($email));
    }

    /**
     * Daftar akun email. Jawaban ke pemanggil selalu sama (anti-enumerasi), jadi tidak ada nilai
     * kembali. Email baru atau yang masih menunggu verifikasi: akun dibuat/ditimpa (nama dan sandi
     * pendaftar terbaru) lalu kode dikirim. Email yang sudah punya akun terverifikasi (sandi maupun
     * Google): akunnya tidak disentuh; pemiliknya dikirimi email pemberitahuan saja.
     */
    public function register(string $name, string $email, string $password): void
    {
        $email = self::normalizeEmail($email);
        $user = User::where('email', $email)->first();

        if ($user?->hasVerifiedEmail()) {
            if ($this->codes->allowSend($email, 'exists', 3)) {
                $this->codes->sendAccountExists($user);
            }

            return;
        }

        $user ??= new User(['email' => $email]);
        $user->fill(['name' => $name, 'password' => $password])->save();

        $this->sendVerification($user);
    }

    /** Kirim kode verifikasi kalau akunnya ada, belum terverifikasi, dan batas kirim belum tercapai. */
    public function sendVerification(?User $user): void
    {
        if ($user && ! $user->hasVerifiedEmail() && $this->codes->allowSend($user->email, 'verify', 5)) {
            $this->codes->issue($user, EmailCode::PURPOSE_VERIFY);
        }
    }

    /** Lupa kata sandi: kode hanya untuk akun yang emailnya terverifikasi. Diam kalau tidak. */
    public function sendPasswordReset(string $email): void
    {
        $user = User::where('email', self::normalizeEmail($email))->first();

        if ($user?->hasVerifiedEmail() && $this->codes->allowSend($user->email, 'reset', 3, withCooldown: false)) {
            $this->codes->issue($user, EmailCode::PURPOSE_RESET);
        }
    }

    /**
     * Periksa kode verifikasi email. Mengembalikan [hasil EmailCodeService::*, user atau null];
     * kalau OK, email sudah ditandai terverifikasi dan kodenya hangus.
     */
    public function verifyEmail(string $email, string $code): array
    {
        $user = User::where('email', self::normalizeEmail($email))->first();

        if (! $user || $user->hasVerifiedEmail()) {
            return [EmailCodeService::INVALID, null];
        }

        $result = $this->codes->verify($user, EmailCode::PURPOSE_VERIFY, $code);
        if ($result !== EmailCodeService::OK) {
            return [$result, null];
        }

        $user->forceFill(['email_verified_at' => now()])->save();

        return [EmailCodeService::OK, $user];
    }

    /**
     * Atur ulang kata sandi dengan kode. Kalau OK: kata sandi berganti, token API dan semua sesi web
     * dicabut. Pemanggil sudah memvalidasi kata sandi baru (sebelum kode diperiksa, supaya sandi
     * lemah tidak menghanguskan kode).
     */
    public function resetPassword(string $email, string $code, string $password): string
    {
        $user = User::where('email', self::normalizeEmail($email))->first();

        if (! $user?->hasVerifiedEmail()) {
            return EmailCodeService::INVALID;
        }

        $result = $this->codes->verify($user, EmailCode::PURPOSE_RESET, $code);
        if ($result !== EmailCodeService::OK) {
            return $result;
        }

        $user->forceFill(['password' => $password])->save();
        $user->tokens()->delete();
        $user->revokeWebSessions();

        return EmailCodeService::OK;
    }

    /**
     * Batas percobaan kode per email (10 per 15 menit) dan per IP (30 per 15 menit) supaya tebak-tebak
     * 6 angka tidak praktis. Mengembalikan detik tunggu kalau terkunci, atau null. Setiap panggilan
     * yang lolos dihitung sebagai satu percobaan.
     */
    public function codeAttemptWait(string $scope, string $ip, string $email): ?int
    {
        $emailKey = "code-try:$scope:email:$email";
        $ipKey = "code-try:$scope:ip:$ip";

        if (RateLimiter::tooManyAttempts($emailKey, 10) || RateLimiter::tooManyAttempts($ipKey, 30)) {
            return max(RateLimiter::availableIn($emailKey), RateLimiter::availableIn($ipKey));
        }

        RateLimiter::hit($emailKey, 900);
        RateLimiter::hit($ipKey, 900);

        return null;
    }

    /**
     * Cek email dan kata sandi dengan batas gagal (5 per menit per IP, 10 per 15 menit per email).
     * Mengembalikan [status LOGIN_*, user atau detik tunggu atau null]. Tidak membuat sesi: itu
     * urusan pemanggil.
     */
    public function checkLogin(string $email, string $password, string $ip): array
    {
        $email = self::normalizeEmail($email);
        $ipKey = 'login-fail:ip:'.$ip;
        $emailKey = 'login-fail:email:'.$email;

        if (RateLimiter::tooManyAttempts($ipKey, self::LOGIN_IP_MAX) || RateLimiter::tooManyAttempts($emailKey, self::LOGIN_EMAIL_MAX)) {
            return [self::LOGIN_THROTTLED, max(RateLimiter::availableIn($ipKey), RateLimiter::availableIn($emailKey))];
        }

        $user = User::where('email', $email)->first();

        // Email tak dikenal atau akun tanpa kata sandi (hanya Google): tetap hitung hash supaya
        // waktu jawab tidak membocorkan apakah emailnya terdaftar.
        $passwordOk = $user?->hasPassword()
            ? Hash::check($password, $user->password)
            : (Hash::check($password, Hash::make('pemerata-waktu')) && false);

        if (! $passwordOk) {
            RateLimiter::hit($ipKey, 60);
            RateLimiter::hit($emailKey, 900);

            return [self::LOGIN_INVALID, null];
        }

        if (! $user->hasVerifiedEmail()) {
            return [self::LOGIN_UNVERIFIED, $user];
        }

        RateLimiter::clear($emailKey);

        return [self::LOGIN_OK, $user];
    }

    /**
     * Tetapkan atau ganti kata sandi akun yang sedang masuk. Akun tanpa sandi (dibuat lewat Google)
     * menetapkan sandi pertama tanpa sandi lama; selain itu sandi lama wajib benar, salah 5 kali
     * dikunci 15 menit. Saat mengganti sandi yang sudah ada, sesi web lain dan SEMUA token API
     * dicabut (sesi $currentSessionId dipertahankan; perangkat Android masuk ulang dengan Google).
     * Menetapkan sandi pertama tidak memutus apa pun: itu bukan perubahan kredensial yang ada.
     * Mengembalikan [status PASSWORD_*, detik tunggu atau null].
     */
    public function changePassword(User $user, ?string $current, string $new, ?string $currentSessionId = null): array
    {
        $hadPassword = $user->hasPassword();

        if ($hadPassword) {
            $key = 'password-change-fail:'.$user->id;
            if (RateLimiter::tooManyAttempts($key, 5)) {
                return [self::PASSWORD_THROTTLED, RateLimiter::availableIn($key)];
            }

            if ($current === null || ! Hash::check($current, $user->password)) {
                RateLimiter::hit($key, 900);

                return [self::PASSWORD_WRONG, null];
            }
            RateLimiter::clear($key);
        }

        $user->forceFill(['password' => $new])->save();
        if ($hadPassword) {
            $user->tokens()->delete();
            $user->revokeWebSessions($currentSessionId);
        }

        return [self::PASSWORD_OK, null];
    }
}
