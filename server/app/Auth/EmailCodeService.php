<?php

namespace App\Auth;

use App\Mail\AccountExistsMail;
use App\Mail\CodeMail;
use App\Models\EmailCode;
use App\Models\User;
use Illuminate\Support\Facades\Mail;
use Illuminate\Support\Facades\RateLimiter;
use Throwable;

/**
 * Kode 6 angka lewat email: terbit, kirim, dan periksa. Kode asli tidak pernah disimpan, hanya
 * HMAC-nya (dikunci APP_KEY dan terikat ke user dan tujuan, jadi hash yang bocor tidak bisa
 * dibalik dengan tabel sejuta kemungkinan tanpa kunci). Pola ini dari habitflow.
 */
class EmailCodeService
{
    public const OK = 'ok';

    public const INVALID = 'code_invalid';

    public const EXPIRED = 'code_expired';

    /** Terbitkan kode baru (kode lama untuk tujuan yang sama hangus) lalu kirim. */
    public function issue(User $user, string $purpose): void
    {
        EmailCode::where('user_id', $user->id)
            ->where('purpose', $purpose)
            ->whereNull('consumed_at')
            ->update(['consumed_at' => now()]);

        $code = str_pad((string) random_int(0, 999999), 6, '0', STR_PAD_LEFT);
        $ttl = (int) config('rizqflow.email_code_ttl_minutes');

        EmailCode::create([
            'user_id' => $user->id,
            'purpose' => $purpose,
            'code_hash' => $this->hash($user, $purpose, $code),
            'expires_at' => now()->addMinutes($ttl),
        ]);

        $this->deliver($user->email, new CodeMail($purpose, $code, $user->name, $ttl));
    }

    /** Kabari pemilik email yang sudah punya akun bahwa ada yang mencoba mendaftar dengannya. */
    public function sendAccountExists(User $user): void
    {
        $this->deliver($user->email, new AccountExistsMail($user->name, $user->hasPassword()));
    }

    /**
     * Kirim sinkron (tanpa antrean: web ini tidak menjalankan worker). Kegagalan pengirim dicatat dan
     * tidak dilempar, supaya jawaban halaman tetap sama dan tidak membocorkan apa pun; pengguna
     * bisa minta kirim ulang.
     */
    private function deliver(string $email, CodeMail|AccountExistsMail $mail): void
    {
        try {
            Mail::to($email)->send($mail);
        } catch (Throwable $e) {
            report($e);
        }
    }

    /** Periksa kode. Benar: kode hangus (sekali pakai). Salah: percobaan bertambah, dan hangus di batas. */
    public function verify(User $user, string $purpose, string $code): string
    {
        $record = EmailCode::where('user_id', $user->id)
            ->where('purpose', $purpose)
            ->whereNull('consumed_at')
            ->latest('id')
            ->first();

        if (! $record) {
            return self::INVALID;
        }

        if ($record->expires_at->isPast()) {
            $record->forceFill(['consumed_at' => now()])->save();

            return self::EXPIRED;
        }

        if (! hash_equals($record->code_hash, $this->hash($user, $purpose, $code))) {
            $record->attempts++;
            if ($record->attempts >= config('rizqflow.email_code_max_attempts')) {
                $record->consumed_at = now();
            }
            $record->save();

            return self::INVALID;
        }

        $record->forceFill(['consumed_at' => now()])->save();

        return self::OK;
    }

    /**
     * Batas kirim per email: jeda 60 detik dan paling banyak $perHour per jam. Mengembalikan false
     * kalau harus ditahan. Dipakai bersama oleh daftar, kirim ulang, dan lupa kata sandi.
     */
    public function allowSend(string $email, string $bucket, int $perHour, bool $withCooldown = true): bool
    {
        $hourKey = "email-send:$bucket:hour:$email";
        $coolKey = "email-send:$bucket:cool:$email";

        if (RateLimiter::tooManyAttempts($hourKey, $perHour)) {
            return false;
        }
        if ($withCooldown && RateLimiter::tooManyAttempts($coolKey, 1)) {
            return false;
        }

        RateLimiter::hit($hourKey, 3600);
        if ($withCooldown) {
            RateLimiter::hit($coolKey, 60);
        }

        return true;
    }

    private function hash(User $user, string $purpose, string $code): string
    {
        return hash_hmac('sha256', $user->id.'|'.$purpose.'|'.$code, (string) config('app.key'));
    }
}
