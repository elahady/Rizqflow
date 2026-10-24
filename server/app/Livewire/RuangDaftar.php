<?php

namespace App\Livewire;

use App\Models\Category;
use App\Models\Room;
use App\Support\Entitlements;
use App\Support\MonthLedger;
use Illuminate\Contracts\View\View;
use Livewire\Component;

/**
 * Daftar dan kelola ruang (S10): tambah, urutkan (naik/turun = prioritas sisa pembulatan), dan
 * arsipkan. Ruang terarsip dipulihkan dari bagian "Diarsipkan"; riwayat transaksinya tidak hilang.
 * Batas 5 ruang gratis (Entitlements::FREE_ROOM_LIMIT) ditegakkan di addRoom() — sebelumnya dicatat
 * sebagai keterbatasan sementara di roadmap.md, sekarang ditutup lewat App\Support\Entitlements.
 */
class RuangDaftar extends Component
{
    private const TYPES = ['menunaikan', 'menumbuhkan', 'mencukupi'];

    public bool $showAddForm = false;

    public string $name = '';

    public string $type = 'mencukupi';

    public ?string $error = null;

    public function addRoom(): void
    {
        $this->error = null;
        $name = trim($this->name);

        if ($name === '') {
            $this->error = 'Nama ruang wajib diisi.';

            return;
        }

        if (! in_array($this->type, self::TYPES, true)) {
            $this->error = 'Tipe ruang tidak dikenal.';

            return;
        }

        $user = auth()->user();
        $activeCount = $user->rooms()->active()->count();

        if (! $user->entitlements()->canAddRoom($activeCount)) {
            $this->error = 'Ruang gratis paling banyak '.Entitlements::FREE_ROOM_LIMIT.'. Upgrade ke Pro untuk ruang tak terbatas.';

            return;
        }

        $maxSort = (int) $user->rooms()->max('sort_order');

        // Slot 1–3 untuk ruang inti (dibuat lewat seed/onboarding), 4–8 untuk ruang custom, ruang
        // ke-9 dan seterusnya netral. Warna melekat ke ruang dan tidak diputar ulang dari ruang
        // yang sudah diarsipkan, jadi dicek dari seluruh riwayat slot, bukan hanya yang aktif.
        $usedSlots = $user->rooms()->pluck('slot')->all();
        $slot = 0;
        for ($candidate = 4; $candidate <= 8; $candidate++) {
            if (! in_array($candidate, $usedSlots, true)) {
                $slot = $candidate;
                break;
            }
        }

        $room = Room::create([
            'user_id' => $user->id,
            'name' => $name,
            'icon' => 'ruang',
            'type' => $this->type,
            'slot' => $slot,
            'sort_order' => $maxSort + 1,
        ]);

        Category::create(['room_id' => $room->id, 'name' => 'Lain-lain', 'sort_order' => 0]);
        Category::create(['room_id' => $room->id, 'name' => 'Tak terlacak', 'is_system' => true, 'sort_order' => 1]);

        $this->reset(['name', 'showAddForm']);
        $this->type = 'mencukupi';
    }

    public function archiveRoom(int $roomId): void
    {
        $room = auth()->user()->rooms()->findOrFail($roomId);

        // Persen alokasi ruang yang diarsipkan kembali ke 0%; total alokasi ruang yang tersisa
        // perlu diatur ulang ke 100% lewat Aturan alokasi, sama seperti di Android.
        $room->update(['archived' => true, 'allocation_percent' => null]);
    }

    public function restoreRoom(int $roomId): void
    {
        $user = auth()->user();
        $room = $user->rooms()->findOrFail($roomId);
        $maxSort = (int) $user->rooms()->max('sort_order');

        $room->update(['archived' => false, 'sort_order' => $maxSort + 1]);
    }

    public function moveUp(int $roomId): void
    {
        $this->swapWithNeighbor($roomId, -1);
    }

    public function moveDown(int $roomId): void
    {
        $this->swapWithNeighbor($roomId, 1);
    }

    private function swapWithNeighbor(int $roomId, int $direction): void
    {
        $rooms = auth()->user()->rooms()->active()->get();
        $index = $rooms->search(fn (Room $r) => $r->id === $roomId);

        if ($index === false) {
            return;
        }

        $neighborIndex = $index + $direction;

        if ($neighborIndex < 0 || $neighborIndex >= $rooms->count()) {
            return;
        }

        $room = $rooms[$index];
        $neighbor = $rooms[$neighborIndex];
        [$roomOrder, $neighborOrder] = [$room->sort_order, $neighbor->sort_order];

        $room->update(['sort_order' => $neighborOrder]);
        $neighbor->update(['sort_order' => $roomOrder]);
    }

    public function render(): View
    {
        $user = auth()->user();
        $rooms = $user->rooms()->active()->get();

        // Statistik bulan berjalan per ruang (jatah, terpakai, status) dari MonthLedger.
        $ledger = MonthLedger::for($user, now());

        return view('livewire.ruang-daftar', [
            'rooms' => $rooms,
            'roomMonths' => $rooms->mapWithKeys(fn (Room $room) => [$room->id => $ledger->room($room)]),
            'archivedRooms' => $user->rooms()->where('archived', true)->orderByDesc('updated_at')->get(),
            'allocationTotal' => $rooms->sum('allocation_percent'),
        ])->layout('components.layout', ['title' => 'Ruang — Rizqflow']);
    }
}
