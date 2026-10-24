<?php

namespace App\Auth;

use App\Models\User;

/**
 * Temukan atau buat akun dari klaim ID token Google yang sudah diverifikasi ([GoogleIdTokenVerifier]).
 * Dipakai bersama oleh masuk web ([Auth\GoogleLoginController]) dan akun server Android (Tahap 9,
 * `Api\AccountController`) supaya satu akun yang sama dikenali lewat `sub` di kedua platform.
 *
 * Email (dinormalisasi huruf kecil) = satu akun: akun yang dibuat lewat Daftar email+sandi dan akun
 * Google dengan email yang sama digabung. Email dari Google sudah terbukti milik pemegang akun
 * Google, jadi akun hasil gabungan langsung berstatus terverifikasi.
 */
class GoogleAccountResolver
{
    /** @param array{sub: string, email: string, name?: string} $claims */
    public function resolve(array $claims): User
    {
        $email = AccountService::normalizeEmail($claims['email']);

        $user = User::where('google_sub', $claims['sub'])->first()
            // Email sudah diverifikasi Google, jadi akun lama dengan email itu milik orang yang sama.
            ?? User::where('email', $email)->whereNull('google_sub')->first();

        if ($user) {
            // Akun daftar yang belum terverifikasi dibuat siapa saja yang mengetik email ini: kata
            // sandinya tidak dipercaya, jadi dibuang dan kodenya dihanguskan. Pemilik email yang
            // sebenarnya (pemegang akun Google ini) bisa menetapkan sandi lagi di Profil.
            if (! $user->hasVerifiedEmail()) {
                $user->forceFill(['password' => null]);
                $user->emailCodes()->whereNull('consumed_at')->update(['consumed_at' => now()]);
            }

            $user->forceFill([
                'google_sub' => $claims['sub'],
                'email' => $email,
                'email_verified_at' => $user->email_verified_at ?? now(),
            ])->save();

            return $user;
        }

        $user = User::create([
            'google_sub' => $claims['sub'],
            'email' => $email,
            'name' => $claims['name'] ?? strstr($email, '@', true),
        ]);
        $user->forceFill(['email_verified_at' => now()])->save();

        return $user;
    }
}
