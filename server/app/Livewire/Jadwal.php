<?php

namespace App\Livewire;

use Illuminate\Contracts\View\View;
use Livewire\Component;

/**
 * Jadwal gabungan tagihan dan transaksi berulang (menu Uang › Jadwal, docs/design/web-mockup).
 * Stub fase 1 desain web baru: sementara menautkan halaman Tagihan dan Transaksi berulang yang sudah ada.
 */
class Jadwal extends Component
{
    public function render(): View
    {
        return view('livewire.jadwal')
            ->layout('components.layout', ['title' => 'Jadwal — Rizqflow']);
    }
}
