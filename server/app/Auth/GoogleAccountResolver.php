<?php

namespace App\Auth;

use App\Models\User;

/**
 * Temukan atau buat akun dari klaim ID token Google yang sudah diverifikasi ([GoogleIdTokenVerifier]).
 * Dipakai bersama oleh masuk web ([Auth\GoogleLoginController]) dan akun server Android (Tahap 9,
 * `Api\AccountController`) supaya satu akun yang sama dikenali lewat `sub` di kedua platform.
 */
class GoogleAccountResolver
{
    /** @param array{sub: string, email: string, name?: string} $claims */
    public function resolve(array $claims): User
    {
        $user = User::where('google_sub', $claims['sub'])->first()
            // Email sudah diverifikasi Google, jadi akun lama dengan email itu milik orang yang sama.
            ?? User::where('email', $claims['email'])->whereNull('google_sub')->first();

        if ($user) {
            $user->update(['google_sub' => $claims['sub'], 'email' => $claims['email']]);

            return $user;
        }

        return User::create([
            'google_sub' => $claims['sub'],
            'email' => $claims['email'],
            'name' => $claims['name'] ?? strstr($claims['email'], '@', true),
        ]);
    }
}
