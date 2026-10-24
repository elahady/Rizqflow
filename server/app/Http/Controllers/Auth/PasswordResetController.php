<?php

namespace App\Http\Controllers\Auth;

use App\Auth\AccountService;
use App\Auth\EmailCodeService;
use App\Http\Controllers\Controller;
use App\Rules\StrongPassword;
use Illuminate\Http\RedirectResponse;
use Illuminate\Http\Request;
use Illuminate\Validation\ValidationException;
use Illuminate\View\View;

/** Lupa dan atur ulang kata sandi di web, kode 6 angka lewat email. */
class PasswordResetController extends Controller
{
    public function __construct(private AccountService $accounts) {}

    public function forgot(): View
    {
        return view('auth.forgot-password');
    }

    public function sendCode(Request $request): RedirectResponse
    {
        $data = $request->validate(['email' => ['required', 'string', 'email']]);

        $this->accounts->sendPasswordReset($data['email']);

        // Jawaban sama apakah email terdaftar atau tidak.
        return redirect()->route('password.reset')
            ->with('reset_email', AccountService::normalizeEmail($data['email']))
            ->with('status', 'Kalau email itu terdaftar, kode 6 angka sudah dikirim. Berlaku 10 menit.');
    }

    public function reset(): View
    {
        return view('auth.reset-password', ['email' => session('reset_email', old('email'))]);
    }

    public function update(Request $request): RedirectResponse
    {
        $data = $request->validate([
            'email' => ['required', 'string', 'email'],
            'code' => ['required', 'string'],
            'password' => ['required', 'string', 'confirmed', new StrongPassword],
        ]);
        $email = AccountService::normalizeEmail($data['email']);

        if ($wait = $this->accounts->codeAttemptWait('reset', (string) $request->ip(), $email)) {
            throw ValidationException::withMessages(['code' => 'Terlalu banyak percobaan. Coba lagi dalam '.$wait.' detik.']);
        }

        $result = $this->accounts->resetPassword($email, trim($data['code']), $data['password']);

        if ($result !== EmailCodeService::OK) {
            return back()->withInput(['email' => $email])
                ->with('reset_email', $email)
                ->withErrors(['code' => $result === EmailCodeService::EXPIRED
                    ? 'Kode sudah kedaluwarsa. Minta kode baru.'
                    : 'Kode salah.']);
        }

        return redirect()->route('login')->with('status', 'Kata sandi diganti. Silakan masuk dengan kata sandi baru.');
    }
}
