<?php

namespace App\Livewire;

use Illuminate\Contracts\View\View;
use Livewire\Component;

/**
 * Daftar transaksi (S08) dikelompokkan per tanggal, dengan pencarian (catatan dan nominal) dan
 * filter cepat (Semua/Pemasukan/Pengeluaran — tanpa Transfer, belum ada di model data transaksi
 * web). Tetap Gratis karena ini akses ke data milik pengguna sendiri, bukan insight premium. Panel
 * "Catat" di S06 belum fungsional di scaffold ini (tampilan saja) — logika pencatatan sungguhan menyusul.
 */
class Transaksi extends Component
{
    public string $search = '';

    public string $filter = 'semua';

    public function render(): View
    {
        $user = auth()->user();
        $query = $user->transactions()->with(['room', 'account']);

        if ($this->filter !== 'semua') {
            $query->where('type', $this->filter);
        }

        $term = trim($this->search);
        if ($term !== '') {
            $amount = (int) preg_replace('/\D/', '', $term);
            $query->where(function ($q) use ($term, $amount) {
                $q->where('note', 'like', "%{$term}%");
                if ($amount > 0) {
                    $q->orWhere('amount_minor', $amount);
                }
            });
        }

        // Label grup termasuk tahun supaya pencarian lintas tahun tidak menyatukan tanggal yang sama di tahun berbeda.
        $transactions = $query->orderByDesc('occurred_on')->get()->groupBy(
            fn ($t) => $t->occurred_on->translatedFormat('j F Y')
        );

        return view('livewire.transaksi', [
            'transactions' => $transactions,
            'rooms' => $user->rooms,
        ])->layout('components.layout', ['title' => 'Transaksi — Rizqflow']);
    }
}
