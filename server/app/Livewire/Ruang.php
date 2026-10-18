<?php

namespace App\Livewire;

use App\Models\Room;
use Illuminate\Contracts\View\View;
use Livewire\Component;

/**
 * Detail ruang (S11): jatah vs terpakai, transaksi terbaru. Tanpa {room}, tampilkan
 * ruang pertama milik user (dipakai juga sebagai target link umum "Lihat ruang").
 */
class Ruang extends Component
{
    public ?int $roomId = null;

    public function mount(?Room $room = null): void
    {
        $this->roomId = $room?->id;
    }

    public function render(): View
    {
        $user = auth()->user();
        $room = $this->roomId
            ? $user->rooms()->findOrFail($this->roomId)
            : $user->rooms()->firstOrFail();

        $transactions = $room->transactions()->orderByDesc('occurred_on')->limit(5)->get();

        return view('livewire.ruang', [
            'room' => $room,
            'transactions' => $transactions,
        ])->layout('components.layout', ['title' => $room->name.' — Rizqflow']);
    }
}
