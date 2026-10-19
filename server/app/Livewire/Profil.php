<?php

namespace App\Livewire;

use Illuminate\Contracts\View\View;
use Illuminate\Support\Facades\Auth;
use Livewire\Component;

/**
 * Hapus akun dari web (Tahap 9, server-sync.md: syarat Google Play bila ada pembuatan akun).
 * Padanannya di Android ada di `Api\AccountController::destroy`. Ini menghapus akun SERVER saja —
 * data di ponsel pengguna tidak disentuh, sesuai prinsip "data di ponsel adalah sumber kebenaran".
 */
class Profil extends Component
{
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
