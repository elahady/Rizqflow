<?php

namespace App\Http\Controllers\Auth;

use App\Auth\AccountService;
use App\Http\Controllers\Controller;
use App\Models\User;
use Illuminate\Http\RedirectResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Auth;
use Illuminate\Validation\ValidationException;
use Illuminate\View\View;

class AuthenticatedSessionController extends Controller
{
    public function __construct(private AccountService $accounts) {}

    public function create(): View
    {
        return view('auth.login');
    }

    public function store(Request $request): RedirectResponse
    {
        $credentials = $request->validate([
            'email' => ['required', 'string', 'email'],
            'password' => ['required', 'string'],
        ]);

        [$status, $detail] = $this->accounts->checkLogin($credentials['email'], $credentials['password'], (string) $request->ip());

        if ($status === AccountService::LOGIN_THROTTLED) {
            throw ValidationException::withMessages([
                'email' => 'Terlalu banyak percobaan. Coba lagi dalam '.$detail.' detik.',
            ]);
        }

        if ($status === AccountService::LOGIN_INVALID) {
            throw ValidationException::withMessages(['email' => 'Email atau kata sandi salah.']);
        }

        // Kata sandi benar tapi email belum diverifikasi: tidak masuk, arahkan ke halaman verifikasi.
        if ($status === AccountService::LOGIN_UNVERIFIED) {
            return $this->unverified($detail);
        }

        Auth::login($detail, remember: true);
        $request->session()->regenerate();

        return redirect()->intended(route('denah'));
    }

    private function unverified(User $user): RedirectResponse
    {
        // Pengiriman kode mati (belum ada pengirim email): tidak ada halaman verifikasi untuk dituju.
        if (! config('rizqflow.email_codes_enabled')) {
            throw ValidationException::withMessages([
                'email' => 'Emailmu belum diverifikasi dan pengiriman kode belum tersedia. Masuk dengan Google untuk sementara.',
            ]);
        }

        $this->accounts->sendVerification($user);

        return redirect()->route('verification.show')
            ->with('verification_email', $user->email)
            ->with('status', 'Emailmu belum diverifikasi. Masukkan kode 6 angka yang kami kirim ke '.$user->email.'.');
    }

    public function destroy(Request $request): RedirectResponse
    {
        Auth::logout();
        $request->session()->invalidate();
        $request->session()->regenerateToken();

        // Pesan tampil sebagai notice di kartu masuk (components/auth-layout).
        return redirect()->route('login')->with('status', 'Kamu sudah keluar.');
    }
}
