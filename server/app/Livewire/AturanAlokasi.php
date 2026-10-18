<?php

namespace App\Livewire;

use App\Models\Room;
use Illuminate\Contracts\View\View;
use Illuminate\Support\Collection;
use Livewire\Component;

/**
 * Aturan alokasi (S12), mode persentase dasar: ubah pembagian rezeki ke tiap ruang aktif.
 * Ruang terakhir menurut urutan S10 tidak punya kolom sendiri — otomatis menerima sisa, sama
 * seperti onboarding S03. Berlaku untuk pemasukan berikutnya; riwayat yang sudah tercatat tidak
 * berubah. Mode lanjutan (prioritas, batas atas, sisa mengalir) adalah fitur Pro terpisah.
 */
class AturanAlokasi extends Component
{
    /** @var array<int|string, int> persen per id ruang, kecuali ruang terakhir */
    public array $percents = [];

    private array $original = [];

    public ?string $error = null;

    public function mount(): void
    {
        foreach ($this->editableRooms() as $room) {
            $this->percents[$room->id] = $room->allocation_percent ?? 0;
        }

        $this->original = $this->normalized($this->percents);
    }

    public function save(): void
    {
        $this->error = null;
        $normalized = $this->normalized($this->percents);

        foreach ($normalized as $percent) {
            if ($percent < 0 || $percent > 100) {
                $this->error = 'Persentase harus di antara 0 dan 100.';

                return;
            }
        }

        $sum = array_sum($normalized);
        if ($sum > 100) {
            $this->error = 'Total persentase melebihi 100%.';

            return;
        }

        $user = auth()->user();
        foreach ($normalized as $roomId => $percent) {
            $user->rooms()->where('id', $roomId)->update(['allocation_percent' => $percent]);
        }

        $last = $this->lastRoom();
        if ($last) {
            $last->update(['allocation_percent' => 100 - $sum]);
        }

        $this->original = $normalized;
        session()->flash('status', 'Aturan alokasi disimpan. Berlaku untuk pemasukan berikutnya; riwayat tidak berubah.');
    }

    /**
     * Membandingkan nilai, bukan tipe kunci — input dari form web bisa datang sebagai string.
     */
    private function normalized(array $percents): array
    {
        $result = [];
        foreach ($percents as $roomId => $percent) {
            $result[(int) $roomId] = (int) $percent;
        }
        ksort($result);

        return $result;
    }

    private function activeRooms(): Collection
    {
        return auth()->user()->rooms()->active()->get();
    }

    private function editableRooms(): Collection
    {
        return $this->activeRooms()->slice(0, -1)->values();
    }

    private function lastRoom(): ?Room
    {
        return $this->activeRooms()->last();
    }

    public function render(): View
    {
        $normalized = $this->normalized($this->percents);
        $remaining = 100 - array_sum($normalized);

        return view('livewire.aturan-alokasi', [
            'editableRooms' => $this->editableRooms(),
            'lastRoom' => $this->lastRoom(),
            'remaining' => $remaining,
            'isDirty' => $normalized !== $this->original,
        ])->layout('components.layout', ['title' => 'Aturan alokasi — Rizqflow']);
    }
}
