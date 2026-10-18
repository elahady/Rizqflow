<?php

namespace App\Livewire;

use App\Models\Account;
use App\Models\Bill;
use App\Models\Category;
use App\Models\Room;
use App\Models\Transaction;
use App\Support\RecurrenceSchedule;
use Illuminate\Contracts\View\View;
use Illuminate\Support\Carbon;
use Livewire\Component;

/**
 * Daftar dan kelola tagihan dan cicilan (S34): Gratis. Beda dari transaksi berulang (S33):
 * pembayarannya tidak pernah otomatis, selalu lewat tombol Bayar ({@see pay()}). Pengingat jatuh
 * tempo (notifikasi) tidak ditangani di web, sama seperti notifikasi malam lainnya.
 */
class Tagihan extends Component
{
    private const NOTE_MAX = 200;

    public bool $showForm = false;

    public ?int $editingId = null;

    public string $name = '';

    public string $amount = '';

    public ?int $accountId = null;

    public ?int $roomId = null;

    public ?int $categoryId = null;

    public string $note = '';

    public string $frequency = ''; // '' = sekali bayar

    public string $dueDate = '';

    public string $totalInstallments = '';

    public string $alreadyPaid = '0';

    public ?string $error = null;

    public ?int $payingId = null;

    public string $payAmount = '';

    public ?string $payError = null;

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
        $this->name = '';
        $this->amount = '';
        $this->accountId = $user->accounts()->where('archived', false)->orderBy('sort_order')->value('id');
        $this->roomId = $user->rooms()->active()->orderBy('sort_order')->value('id');
        $this->categoryId = $this->roomId ? Room::find($this->roomId)->categories()->where('archived', false)->orderBy('sort_order')->value('id') : null;
        $this->note = '';
        $this->frequency = '';
        $this->dueDate = Carbon::today()->toDateString();
        $this->totalInstallments = '';
        $this->alreadyPaid = '0';
        $this->error = null;
        $this->showForm = true;
    }

    public function startEdit(int $id): void
    {
        $bill = auth()->user()->bills()->findOrFail($id);

        $this->editingId = $bill->id;
        $this->name = $bill->name;
        $this->amount = (string) $bill->amount_minor;
        $this->accountId = $bill->account_id;
        $this->roomId = $bill->room_id;
        $this->categoryId = $bill->category_id;
        $this->note = $bill->note ?? '';
        $this->frequency = $bill->frequency ?? '';
        $this->dueDate = $bill->next_due->toDateString();
        $this->totalInstallments = $bill->total_installments !== null ? (string) $bill->total_installments : '';
        $this->alreadyPaid = (string) $bill->paid_count;
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

        $name = trim($this->name);
        if ($name === '' || mb_strlen($name) > Bill::NAME_MAX) {
            $this->error = 'Nama tagihan 1 sampai '.Bill::NAME_MAX.' karakter.';

            return;
        }

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

        $frequency = $this->frequency !== '' ? $this->frequency : null;
        $alreadyPaid = max((int) $this->alreadyPaid, 0);
        $total = $this->totalInstallments !== '' ? max((int) $this->totalInstallments, 0) : null;

        if ($total !== null && ($frequency === null || $total < 1 || $alreadyPaid >= $total)) {
            $this->error = 'Jumlah cicilan minimal 1, hanya untuk tagihan berulang, dan harus lebih dari yang sudah dibayar.';

            return;
        }
        if ($frequency === null && $alreadyPaid > 0) {
            $this->error = 'Sekali bayar tidak punya cicilan yang sudah terbayar.';

            return;
        }

        $account = $user->accounts()->where('archived', false)->find($this->accountId);
        if (! $account) {
            $this->error = 'Pilih akun.';

            return;
        }

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

        try {
            $dueDate = Carbon::parse($this->dueDate)->startOfDay();
        } catch (\Exception) {
            $this->error = 'Tanggal jatuh tempo tidak sah.';

            return;
        }

        $current = $this->editingId ? $user->bills()->find($this->editingId) : null;
        // Jatuh tempo yang diubah menjadi jangkar jadwal yang baru; bila tidak berubah, jangkar lama dipertahankan.
        $startDate = (! $current || ! $dueDate->equalTo($current->next_due) || $current->frequency !== $frequency)
            ? $dueDate
            : $current->start_date;

        $data = [
            'user_id' => $user->id,
            'name' => $name,
            'amount_minor' => $amount,
            'account_id' => $account->id,
            'room_id' => $room->id,
            'category_id' => $category->id,
            'note' => $note !== '' ? $note : null,
            'frequency' => $frequency,
            'start_date' => $startDate,
            'next_due' => $dueDate,
            'total_installments' => $frequency !== null ? $total : null,
            'paid_count' => $alreadyPaid,
        ];

        if ($current) {
            $current->update($data);
        } else {
            Bill::create($data + ['active' => true]);
        }

        $this->showForm = false;
    }

    public function pause(int $id): void
    {
        auth()->user()->bills()->findOrFail($id)->update(['active' => false]);
    }

    public function resume(int $id): void
    {
        auth()->user()->bills()->findOrFail($id)->update(['active' => true]);
    }

    public function delete(int $id): void
    {
        auth()->user()->bills()->findOrFail($id)->delete();
    }

    public function startPay(int $id): void
    {
        $bill = auth()->user()->bills()->findOrFail($id);
        $this->payingId = $bill->id;
        $this->payAmount = (string) $bill->amount_minor;
        $this->payError = null;
    }

    public function cancelPay(): void
    {
        $this->payingId = null;
        $this->payError = null;
    }

    public function pay(): void
    {
        $this->payError = null;
        $bill = auth()->user()->bills()->find($this->payingId);
        if (! $bill || $bill->isFinished()) {
            $this->payingId = null;

            return;
        }

        $amount = (int) $this->payAmount;
        if ($amount <= 0) {
            $this->payError = 'Nominal wajib lebih dari nol.';

            return;
        }

        if (! $bill->isUsable()) {
            $this->payError = 'Akun, ruang, atau kategori tagihan ini sudah diarsipkan.';

            return;
        }

        $paidCount = $bill->paid_count + 1;
        $noteLabel = $bill->total_installments !== null ? "{$bill->name} ({$paidCount}/{$bill->total_installments})" : $bill->name;

        Transaction::create([
            'user_id' => $bill->user_id,
            'room_id' => $bill->room_id,
            'category_id' => $bill->category_id,
            'account_id' => $bill->account_id,
            'bill_id' => $bill->id,
            'type' => 'pengeluaran',
            'amount_minor' => $amount,
            'category' => $bill->category->name,
            'note' => mb_substr($noteLabel, 0, self::NOTE_MAX),
            'occurred_on' => Carbon::today(),
        ]);

        Account::where('id', $bill->account_id)->decrement('balance_minor', $amount);

        $updated = ['paid_count' => $paidCount];
        $willBeFinished = $bill->frequency === null ? $paidCount >= 1 : ($bill->total_installments !== null && $paidCount >= $bill->total_installments);
        if (! $willBeFinished) {
            $updated['next_due'] = RecurrenceSchedule::next($bill->start_date, $bill->frequency, $bill->next_due);
        }
        $bill->update($updated);

        $this->payingId = null;
    }

    public function render(): View
    {
        $user = auth()->user();
        $today = Carbon::today();

        $rank = fn (Bill $bill) => $bill->isFinished() ? 2 : (! $bill->active ? 1 : 0);
        $bills = $user->bills()->with(['account', 'room', 'category'])->get()
            ->sortBy([
                [fn ($a, $b) => $rank($a) <=> $rank($b), SORT_ASC],
                [fn ($a, $b) => $a->next_due <=> $b->next_due, SORT_ASC],
                [fn ($a, $b) => strcasecmp($a->name, $b->name), SORT_ASC],
            ])
            ->values();

        $rooms = $user->rooms()->active()->orderBy('sort_order')->get();
        $categories = $this->roomId ? (Room::find($this->roomId)?->categories()->where('archived', false)->orderBy('sort_order')->get() ?? collect()) : collect();

        return view('livewire.tagihan', [
            'bills' => $bills,
            'today' => $today,
            'accounts' => $user->accounts()->where('archived', false)->orderBy('sort_order')->get(),
            'rooms' => $rooms,
            'categories' => $categories,
        ])->layout('components.layout', ['title' => 'Tagihan dan cicilan — Rizqflow']);
    }
}
