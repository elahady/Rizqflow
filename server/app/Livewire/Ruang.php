<?php

namespace App\Livewire;

use App\Models\Room;
use Illuminate\Contracts\View\View;
use Livewire\Component;

/**
 * Detail ruang (S11): jatah vs terpakai, pos-pos bulan ini, transaksi terbaru. Daftar dan kelola
 * ruang (S10, tambah/urutkan/arsipkan) ada di RuangDaftar lewat route('ruang') tanpa parameter.
 */
class Ruang extends Component
{
    public Room $room;

    public function mount(Room $room): void
    {
        abort_unless($room->user_id === auth()->id(), 403);

        $this->room = $room;
    }

    public function render(): View
    {
        $transactions = $this->room->transactions()->orderByDesc('occurred_on')->limit(5)->get();

        return view('livewire.ruang', [
            'room' => $this->room,
            'categories' => $this->room->categoryBreakdown(now()),
            'transactions' => $transactions,
        ])->layout('components.layout', ['title' => $this->room->name.' — Rizqflow']);
    }
}
