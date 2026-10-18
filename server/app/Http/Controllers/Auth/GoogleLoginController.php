<?php

namespace App\Http\Controllers\Auth;

use App\Auth\GoogleIdTokenVerifier;
use App\Http\Controllers\Controller;
use App\Models\User;
use Illuminate\Http\RedirectResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Auth;
use Illuminate\Validation\ValidationException;

/**
 * Masuk dengan Google (keputusan 2026-10-18: web ikut akun Google Android). Tombol Google Identity
 * Services di halaman masuk mengirim ID token; akun dicari lewat `sub`, dibuat bila belum ada.
 */
class GoogleLoginController extends Controller
{
    public function store(Request $request, GoogleIdTokenVerifier $verifier): RedirectResponse
    {
        $request->validate(['credential' => ['required', 'string']]);

        $claims = $verifier->verify($request->string('credential'));
        if ($claims === null || ($claims['email_verified'] ?? false) !== true || empty($claims['email'])) {
            throw ValidationException::withMessages(['google' => 'Masuk dengan Google gagal. Coba lagi.']);
        }

        $user = User::where('google_sub', $claims['sub'])->first()
            // Email sudah diverifikasi Google, jadi akun lama dengan email itu milik orang yang sama.
            ?? User::where('email', $claims['email'])->whereNull('google_sub')->first();

        if ($user) {
            $user->update(['google_sub' => $claims['sub'], 'email' => $claims['email']]);
        } else {
            $user = User::create([
                'google_sub' => $claims['sub'],
                'email' => $claims['email'],
                'name' => $claims['name'] ?? strstr($claims['email'], '@', true),
            ]);
        }

        Auth::login($user, remember: true);
        $request->session()->regenerate();

        return redirect()->intended(route('denah'));
    }
}
