<?php

namespace App\Http\Controllers\Auth;

use App\Auth\AccountService;
use App\Http\Controllers\Controller;
use App\Rules\StrongPassword;
use Illuminate\Http\RedirectResponse;
use Illuminate\Http\Request;
use Illuminate\View\View;

/**
 * Daftar web: akun baru belum terverifikasi dan kode 6 angka dikirim ke email; masuk baru setelah
 * kode benar di halaman verifikasi. Jawabannya SAMA untuk email baru dan email yang sudah punya akun
 * (anti-enumerasi); pemilik akun lama diberi tahu lewat email, lihat [AccountService::register].
 */
class RegisteredUserController extends Controller
{
    public function __construct(private AccountService $accounts) {}

    public function create(): View
    {
        return view('auth.register');
    }

    public function store(Request $request): RedirectResponse
    {
        $data = $request->validate([
            'name' => ['required', 'string', 'max:255'],
            'email' => ['required', 'string', 'email', 'max:255'],
            'password' => ['required', 'string', 'confirmed', new StrongPassword],
        ]);

        $this->accounts->register($data['name'], $data['email'], $data['password']);
        $email = AccountService::normalizeEmail($data['email']);

        return redirect()->route('verification.show')
            ->with('verification_email', $email)
            ->with('status', 'Kalau emailnya bisa dipakai, kami mengirim kode 6 angka ke '.$email.'. Cek juga folder spam. Kalau kamu sudah punya akun, masuk saja.');
    }
}
