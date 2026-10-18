<?php

namespace App\Livewire;

use Illuminate\Contracts\View\View;
use Livewire\Component;

/**
 * Daftar transaksi (S08) dikelompokkan per tanggal. Panel "Catat" di S06 belum fungsional
 * di scaffold ini (tampilan saja) — logika pencatatan sungguhan menyusul.
 */
class Transaksi extends Component
{
    public function render(): View
    {
        $user = auth()->user();

        $transactions = $user->transactions()->with(['room', 'account'])->orderByDesc('occurred_on')->get()->groupBy(
            fn ($t) => $t->occurred_on->translatedFormat('j F')
        );

        return view('livewire.transaksi', [
            'transactions' => $transactions,
            'rooms' => $user->rooms,
        ])->layout('components.layout', ['title' => 'Transaksi — Rizqflow']);
    }
}
