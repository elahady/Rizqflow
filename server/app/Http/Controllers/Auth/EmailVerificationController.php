<?php

namespace App\Http\Controllers\Auth;

use App\Auth\AccountService;
use App\Auth\EmailCodeService;
use App\Http\Controllers\Controller;
use App\Models\User;
use Illuminate\Http\RedirectResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Auth;
use Illuminate\Validation\ValidationException;
use Illuminate\View\View;

/** Halaman verifikasi email dengan kode 6 angka, pasangan dari Daftar dan Masuk. */
class EmailVerificationController extends Controller
{
    public function __construct(private AccountService $accounts) {}

    public function show(): View
    {
        return view('auth.verify-email', ['email' => session('verification_email', old('email'))]);
    }

    public function verify(Request $request): RedirectResponse
    {
        $data = $request->validate([
            'email' => ['required', 'string', 'email'],
            'code' => ['required', 'string'],
        ]);
        $email = AccountService::normalizeEmail($data['email']);

        if ($wait = $this->accounts->codeAttemptWait('verify', (string) $request->ip(), $email)) {
            throw ValidationException::withMessages(['code' => 'Terlalu banyak percobaan. Coba lagi dalam '.$wait.' detik.']);
        }

        [$result, $user] = $this->accounts->verifyEmail($email, trim($data['code']));

        if ($result !== EmailCodeService::OK) {
            return back()->withInput(['email' => $email])
                ->with('verification_email', $email)
                ->withErrors(['code' => $result === EmailCodeService::EXPIRED
                    ? 'Kode sudah kedaluwarsa. Minta kode baru.'
                    : 'Kode salah.']);
        }

        Auth::login($user, remember: true);
        $request->session()->regenerate();

        return redirect()->route('denah')->with('status', 'Email terverifikasi. Selamat datang!');
    }

    public function resend(Request $request): RedirectResponse
    {
        $data = $request->validate(['email' => ['required', 'string', 'email']]);
        $email = AccountService::normalizeEmail($data['email']);

        $this->accounts->sendVerification(User::where('email', $email)->first());

        // Jawaban sama untuk email terdaftar atau tidak, dan juga kalau batas kirim tercapai.
        return back()->withInput(['email' => $email])
            ->with('verification_email', $email)
            ->with('status', 'Kalau email itu menunggu verifikasi, kode baru sudah dikirim. Tunggu semenit sebelum meminta lagi.');
    }
}
