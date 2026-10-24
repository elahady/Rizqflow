<?php

namespace App\Livewire;

use Illuminate\Contracts\View\View;
use Livewire\Component;

/**
 * Impor dan ekspor (menu Pengaturan): Impor CSV, Ekspor CSV, dan PDF digabung di satu halaman
 * (docs/design/web-mockup/project/Navigasi.dc.html). Stub fase 1: sementara menautkan halaman yang ada.
 */
class ImporEkspor extends Component
{
    public function render(): View
    {
        return view('livewire.impor-ekspor')
            ->layout('components.layout', ['title' => 'Impor dan ekspor — Rizqflow']);
    }
}
