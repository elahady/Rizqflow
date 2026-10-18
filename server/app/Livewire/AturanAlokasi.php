<?php

namespace App\Livewire;

use App\Models\Room;
use App\Support\Feature;
use Illuminate\Contracts\View\View;
use Illuminate\Support\Collection;
use Livewire\Component;

/**
 * Aturan alokasi (S12). Mode persentase dasar (Gratis): ubah pembagian rezeki ke tiap ruang aktif,
 * ruang terakhir menurut urutan S10 otomatis menerima sisa. Mode lanjutan — prioritas dan batas
 * atas (Pro, porting AllocationEngine.allocateWaterfall di :domain): prioritas memakai urutan
 * rooms.sort_order yang sama, ruang terakhir selalu tak terbatas. Berlaku untuk pemasukan
 * berikutnya; riwayat yang sudah tercatat tidak berubah. Web belum punya proses pencatatan
 * pemasukan yang benar-benar memanggil mesin alokasi ini — halaman ini murni konfigurasi, sama
 * seperti mode persentase sebelumnya.
 */
class AturanAlokasi extends Component
{
    /** @var array<int|string, int> persen per id ruang, kecuali ruang terakhir */
    public array $percents = [];

    /** @var array<int|string, string> batas atas (rupiah, string digit) per id ruang, kecuali ruang terakhir; kosong = tak terbatas */
    public array $caps = [];

    public string $mode = 'persentase';

    private array $originalPercents = [];

    private array $originalCaps = [];

    public ?string $error = null;

    public function mount(): void
    {
        $user = auth()->user();
        $this->mode = $user->allocation_mode === 'lanjutan' && $user->entitlements()->isEnabled(Feature::AdvancedAllocationRules)
            ? 'lanjutan'
            : 'persentase';

        foreach ($this->editableRooms() as $room) {
            $this->percents[$room->id] = $room->allocation_percent ?? 0;
            $this->caps[$room->id] = $room->allocation_cap_minor !== null ? (string) $room->allocation_cap_minor : '';
        }

        $this->originalPercents = $this->normalized($this->percents);
        $this->originalCaps = $this->caps;
    }

    public function switchMode(string $mode): void
    {
        $this->error = null;

        if ($mode === 'lanjutan' && ! auth()->user()->entitlements()->isEnabled(Feature::AdvancedAllocationRules)) {
            $this->error = 'Mode prioritas dan batas atas adalah fitur Pro.';

            return;
        }

        $this->mode = $mode;
        auth()->user()->update(['allocation_mode' => $mode]);
    }

    public function save(): void
    {
        $this->error = null;

        if ($this->mode === 'lanjutan') {
            $this->saveCaps();
        } else {
            $this->savePercents();
        }
    }

    private function savePercents(): void
    {
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

        $this->originalPercents = $normalized;
        session()->flash('status', 'Aturan alokasi disimpan. Berlaku untuk pemasukan berikutnya; riwayat tidak berubah.');
    }

    private function saveCaps(): void
    {
        $user = auth()->user();

        foreach ($this->editableRooms() as $room) {
            $raw = trim((string) ($this->caps[$room->id] ?? ''));
            if ($raw === '') {
                $room->update(['allocation_cap_minor' => null]);

                continue;
            }

            $cap = (int) $raw;
            if ($cap < 0) {
                $this->error = 'Batas atas tidak boleh negatif.';

                return;
            }

            $room->update(['allocation_cap_minor' => $cap]);
        }

        $this->lastRoom()?->update(['allocation_cap_minor' => null]);
        $this->originalCaps = $this->caps;
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
        $isDirty = $this->mode === 'lanjutan' ? $this->caps !== $this->originalCaps : $normalized !== $this->originalPercents;

        return view('livewire.aturan-alokasi', [
            'editableRooms' => $this->editableRooms(),
            'lastRoom' => $this->lastRoom(),
            'remaining' => $remaining,
            'isDirty' => $isDirty,
            'canUseAdvanced' => auth()->user()->entitlements()->isEnabled(Feature::AdvancedAllocationRules),
        ])->layout('components.layout', ['title' => 'Aturan alokasi — Rizqflow']);
    }
}
