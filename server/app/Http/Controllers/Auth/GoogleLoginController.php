<?php

namespace App\Http\Controllers\Auth;

use App\Auth\GoogleAccountResolver;
use App\Auth\GoogleIdTokenVerifier;
use App\Http\Controllers\Controller;
use Illuminate\Http\RedirectResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Auth;
use Illuminate\Validation\ValidationException;

/**
 * Masuk dengan Google (keputusan 2026-10-18: web ikut akun Google Android). Tombol Google Identity
 * Services di halaman masuk mengirim ID token; akun dicari/dibuat lewat [GoogleAccountResolver],
 * dipakai bersama dengan akun server Android (Tahap 9, `Api\AccountController`).
 */
class GoogleLoginController extends Controller
{
    public function store(Request $request, GoogleIdTokenVerifier $verifier, GoogleAccountResolver $resolver): RedirectResponse
    {
        $request->validate(['credential' => ['required', 'string']]);

        $claims = $verifier->verify($request->string('credential'));
        if ($claims === null || ($claims['email_verified'] ?? false) !== true || empty($claims['email'])) {
            throw ValidationException::withMessages(['google' => 'Masuk dengan Google gagal. Coba lagi.']);
        }

        $user = $resolver->resolve($claims);

        Auth::login($user, remember: true);
        $request->session()->regenerate();

        return redirect()->intended(route('denah'));
    }
}
