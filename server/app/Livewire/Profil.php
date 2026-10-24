<?php

namespace App\Livewire;

use App\Auth\AccountService;
use App\Rules\StrongPassword;
use Illuminate\Contracts\View\View;
use Illuminate\Support\Facades\Auth;
use Livewire\Component;

/**
 * Profil: paket, kata sandi, dan hapus akun.
 *
 * Kata sandi: akun yang dibuat lewat Google belum punya sandi dan menetapkannya tanpa sandi lama;
 * akun yang sudah punya sandi mengubahnya dengan sandi lama (lihat [AccountService::changePassword]).
 * Tidak butuh email, jadi tetap tersedia walau pengiriman kode email dimatikan.
 *
 * Hapus akun dari web (Tahap 9, server-sync.md: syarat Google Play bila ada pembuatan akun).
 * Padanannya di Android ada di `Api\AccountController::destroy`. Ini menghapus akun SERVER saja —
 * data di ponsel pengguna tidak disentuh, sesuai prinsip "data di ponsel adalah sumber kebenaran".
 */
class Profil extends Component
{
    public string $current_password = '';

    public string $password = '';

    public string $password_confirmation = '';

    /** Pesan hasil terakhir pada bagian kata sandi: 'set' (sandi pertama) atau 'changed'. */
    public ?string $passwordSaved = null;

    public function savePassword(AccountService $accounts): void
    {
        $user = auth()->user();
        $this->passwordSaved = null;

        $this->validate([
            'password' => ['required', 'string', 'confirmed', new StrongPassword],
        ], [
            'password.required' => 'Isi kata sandi barunya.',
            'password.confirmed' => 'Konfirmasi kata sandi tidak sama.',
        ]);

        $hadPassword = $user->hasPassword();
        [$status, $wait] = $accounts->changePassword(
            $user,
            $hadPassword ? $this->current_password : null,
            $this->password,
            session()->getId(),
        );

        if ($status === AccountService::PASSWORD_THROTTLED) {
            $this->addError('current_password', 'Terlalu banyak percobaan. Coba lagi dalam '.$wait.' detik.');

            return;
        }

        if ($status === AccountService::PASSWORD_WRONG) {
            $this->addError('current_password', 'Kata sandi saat ini salah.');

            return;
        }

        $this->reset('current_password', 'password', 'password_confirmation');
        $this->resetErrorBag();
        $this->passwordSaved = $hadPassword ? 'changed' : 'set';
    }

    public function deleteAccount(): void
    {
        $user = auth()->user();

        Auth::logout();
        session()->invalidate();
        session()->regenerateToken();

        $user->tokens()->delete();
        $user->delete();

        $this->redirect(route('login'));
    }

    public function render(): View
    {
        return view('livewire.profil')->layout('components.layout', ['title' => 'Profil — Rizqflow']);
    }
}
