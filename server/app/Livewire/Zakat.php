<?php

namespace App\Livewire;

use Illuminate\Contracts\View\View;
use Livewire\Component;

/**
 * Beranda zakat + kartu haul (S14/S16). Profil harta tunggal untuk scaffold ini —
 * multi-profil harta (Pro) belum diimplementasikan.
 */
class Zakat extends Component
{
    public function render(): View
    {
        $profile = auth()->user()->zakatProfiles()->first();

        return view('livewire.zakat', [
            'profile' => $profile,
        ])->layout('components.layout', ['title' => 'Zakat dan Haul — Rizqflow']);
    }
}
