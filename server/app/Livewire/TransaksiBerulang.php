<?php

namespace App\Livewire;

use App\Models\Category;
use App\Models\RecurringRule;
use App\Models\Room;
use App\Support\RecurrenceSchedule;
use Illuminate\Contracts\View\View;
use Illuminate\Support\Carbon;
use Livewire\Component;

/**
 * Daftar dan kelola transaksi berulang (S33): Gratis, pemasukan dan pengeluaran saja (transfer
 * belum ada di model data transaksi web). Pencatatan kemunculannya sendiri dijalankan oleh
 * App\Support\RecurringRunner dari Denah::mount(), bukan di sini — halaman ini murni kelola aturan.
 */
class TransaksiBerulang extends Component
{
    private const NOTE_MAX = 200;

    private const SOURCE_MAX = 40;

    public bool $showForm = false;

    public ?int $editingId = null;

    public string $type = 'pengeluaran';

    public string $amount = '';

    public ?int $accountId = null;

    public ?int $roomId = null;

    public ?int $categoryId = null;

    public string $source = '';

    public string $note = '';

    public string $frequency = 'bulanan';

    public string $startDate = '';

    public string $endDate = '';

    public ?string $error = null;

    public function updatedRoomId(): void
    {
        $room = $this->roomId ? Room::find($this->roomId) : null;
        $ownCategoryIds = $room ? $room->categories()->pluck('id')->all() : [];

        if (! in_array($this->categoryId, $ownCategoryIds, true)) {
            $this->categoryId = $room?->categories()->where('archived', false)->orderBy('sort_order')->value('id');
        }
    }

    public function startAdd(): void
    {
        $user = auth()->user();
        $this->editingId = null;
        $this->type = 'pengeluaran';
        $this->amount = '';
        $this->accountId = $user->accounts()->where('archived', false)->orderBy('sort_order')->value('id');
        $this->roomId = $user->rooms()->active()->orderBy('sort_order')->value('id');
        $this->categoryId = $this->roomId ? Room::find($this->roomId)->categories()->where('archived', false)->orderBy('sort_order')->value('id') : null;
        $this->source = '';
        $this->note = '';
        $this->frequency = 'bulanan';
        $this->startDate = Carbon::today()->toDateString();
        $this->endDate = '';
        $this->error = null;
        $this->showForm = true;
    }

    public function startEdit(int $id): void
    {
        $rule = auth()->user()->recurringRules()->findOrFail($id);

        $this->editingId = $rule->id;
        $this->type = $rule->type;
        $this->amount = (string) $rule->amount_minor;
        $this->accountId = $rule->account_id;
        $this->roomId = $rule->room_id;
        $this->categoryId = $rule->category_id;
        $this->source = $rule->source ?? '';
        $this->note = $rule->note ?? '';
        $this->frequency = $rule->frequency;
        $this->startDate = $rule->start_date->toDateString();
        $this->endDate = $rule->end_date?->toDateString() ?? '';
        $this->error = null;
        $this->showForm = true;
    }

    public function cancelForm(): void
    {
        $this->showForm = false;
        $this->error = null;
    }

    public function save(): void
    {
        $this->error = null;
        $user = auth()->user();

        $amount = (int) $this->amount;
        if ($amount <= 0) {
            $this->error = 'Nominal wajib lebih dari nol.';

            return;
        }

        $note = trim($this->note);
        if (mb_strlen($note) > self::NOTE_MAX) {
            $this->error = 'Catatan paling banyak '.self::NOTE_MAX.' karakter.';

            return;
        }

        $source = trim($this->source);
        if ($this->type === 'pemasukan' && mb_strlen($source) > self::SOURCE_MAX) {
            $this->error = 'Sumber paling banyak '.self::SOURCE_MAX.' karakter.';

            return;
        }

        $account = $user->accounts()->where('archived', false)->find($this->accountId);
        if (! $account) {
            $this->error = 'Pilih akun.';

            return;
        }

        $room = null;
        $category = null;
        if ($this->type === 'pengeluaran') {
            $room = $user->rooms()->active()->find($this->roomId);
            if (! $room) {
                $this->error = 'Pilih ruang.';

                return;
            }
            $category = $this->categoryId ? Category::where('room_id', $room->id)->where('archived', false)->find($this->categoryId) : null;
            if (! $category) {
                $this->error = 'Pilih kategori yang sesuai dengan ruang.';

                return;
            }
        }

        if (! in_array($this->frequency, ['harian', 'mingguan', 'bulanan'], true)) {
            $this->error = 'Frekuensi tidak dikenal.';

            return;
        }

        try {
            $startDate = Carbon::parse($this->startDate)->startOfDay();
        } catch (\Exception) {
            $this->error = 'Tanggal mulai tidak sah.';

            return;
        }

        $endDate = null;
        if ($this->endDate !== '') {
            try {
                $endDate = Carbon::parse($this->endDate)->startOfDay();
            } catch (\Exception) {
                $this->error = 'Tanggal akhir tidak sah.';

                return;
            }
            if ($endDate->lessThan($startDate)) {
                $this->error = 'Tanggal akhir tidak boleh sebelum tanggal mulai.';

                return;
            }
        }

        $today = Carbon::today();
        $current = $this->editingId ? $user->recurringRules()->find($this->editingId) : null;
        $scheduleChanged = ! $current || $current->frequency !== $this->frequency || ! $current->start_date->equalTo($startDate);

        if ($scheduleChanged && $startDate->lessThan($today)) {
            $this->error = 'Tanggal mulai tidak boleh sebelum hari ini.';

            return;
        }

        $nextDue = $scheduleChanged
            ? RecurrenceSchedule::firstOnOrAfter($startDate, $this->frequency, $today)
            : $current->next_due;

        $data = [
            'user_id' => $user->id,
            'type' => $this->type,
            'amount_minor' => $amount,
            'account_id' => $account->id,
            'room_id' => $room?->id,
            'category_id' => $category?->id,
            'source' => $this->type === 'pemasukan' && $source !== '' ? $source : null,
            'note' => $note !== '' ? $note : null,
            'frequency' => $this->frequency,
            'start_date' => $startDate,
            'next_due' => $nextDue,
            'end_date' => $endDate,
        ];

        if ($current) {
            $current->update($data);
        } else {
            RecurringRule::create($data + ['active' => true]);
        }

        $this->showForm = false;
    }

    public function pause(int $id): void
    {
        auth()->user()->recurringRules()->findOrFail($id)->update(['active' => false]);
    }

    public function resume(int $id): void
    {
        $rule = auth()->user()->recurringRules()->findOrFail($id);
        $today = Carbon::today();

        $nextDue = $rule->next_due->lessThan($today)
            ? RecurrenceSchedule::firstOnOrAfter($rule->start_date, $rule->frequency, $today)
            : $rule->next_due;

        $rule->update(['active' => true, 'next_due' => $nextDue]);
    }

    public function delete(int $id): void
    {
        auth()->user()->recurringRules()->findOrFail($id)->delete();
    }

    public function render(): View
    {
        $user = auth()->user();

        $rules = $user->recurringRules()->with(['account', 'room', 'category'])
            ->orderByRaw('active = 0')
            ->orderBy('next_due')
            ->get();

        $rooms = $user->rooms()->active()->orderBy('sort_order')->get();
        $categories = $this->roomId ? (Room::find($this->roomId)?->categories()->where('archived', false)->orderBy('sort_order')->get() ?? collect()) : collect();

        return view('livewire.transaksi-berulang', [
            'rules' => $rules,
            'accounts' => $user->accounts()->where('archived', false)->orderBy('sort_order')->get(),
            'rooms' => $rooms,
            'categories' => $categories,
        ])->layout('components.layout', ['title' => 'Transaksi berulang — Rizqflow']);
    }
}
