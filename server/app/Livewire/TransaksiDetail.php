<?php

namespace App\Livewire;

use App\Models\Account;
use App\Models\Category;
use App\Models\Room;
use App\Models\Transaction;
use Illuminate\Contracts\View\View;
use Illuminate\Support\Carbon;
use Livewire\Component;

/**
 * Detail dan ubah transaksi (S09): pengeluaran dan pemasukan bisa diubah dan dihapus, mengikuti
 * aturan di LedgerService.updateTransaction di :domain (akun/ruang/kategori terarsip boleh tetap
 * dipakai selama tidak diganti). Transfer belum ada di model data web, jadi tidak ditangani di sini.
 * Ruang dan alokasi pemasukan tidak bisa diedit dari sini, sama seperti mobile — web belum punya
 * alokasi pemasukan per ruang sama sekali, jadi ubah nominal pemasukan tidak perlu menghitung ulang apa pun.
 * Saldo akun (statis sejak Kelola, lihat app/Models/Account.php) kini ikut bergerak lewat ubah dan hapus di sini.
 */
class TransaksiDetail extends Component
{
    private const NOTE_MAX = 200;

    private const SOURCE_MAX = 40;

    public Transaction $transaction;

    public bool $editing = false;

    public string $amount = '';

    public string $note = '';

    public string $occurredOn = '';

    public ?int $accountId = null;

    public ?int $roomId = null;

    public ?int $categoryId = null;

    public string $source = '';

    public ?string $error = null;

    public function mount(Transaction $transaction): void
    {
        abort_unless($transaction->user_id === auth()->id(), 403);

        $this->transaction = $transaction;
        $this->fillFromTransaction();
    }

    private function fillFromTransaction(): void
    {
        $this->amount = (string) $this->transaction->amount_minor;
        $this->note = $this->transaction->note ?? '';
        $this->occurredOn = $this->transaction->occurred_on->toDateString();
        $this->accountId = $this->transaction->account_id;

        if ($this->transaction->type === 'pengeluaran') {
            $this->roomId = $this->transaction->room_id;
            $this->categoryId = $this->transaction->category_id;
        } else {
            $this->source = $this->transaction->category ?? '';
        }
    }

    public function updatedRoomId(): void
    {
        // Berganti ruang mengosongkan kategori — kategori ruang lama tidak cocok lagi.
        $room = $this->roomId ? Room::find($this->roomId) : null;
        $ownCategoryIds = $room ? $room->categories()->pluck('id')->all() : [];

        if (! in_array($this->categoryId, $ownCategoryIds, true)) {
            $this->categoryId = $room?->categories()->where('archived', false)->orderBy('sort_order')->value('id');
        }
    }

    public function startEdit(): void
    {
        $this->editing = true;
        $this->error = null;
    }

    public function cancelEdit(): void
    {
        $this->editing = false;
        $this->error = null;
        $this->fillFromTransaction();
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

        try {
            $date = Carbon::parse($this->occurredOn)->startOfDay();
        } catch (\Exception) {
            $this->error = 'Tanggal tidak sah.';

            return;
        }

        $account = $user->accounts()->find($this->accountId);
        if (! $account) {
            $this->error = 'Pilih akun.';

            return;
        }
        if ($account->archived && $account->id !== $this->transaction->account_id) {
            $this->error = 'Akun ini sudah diarsipkan — pilih akun lain.';

            return;
        }

        $oldAmount = $this->transaction->amount_minor;
        $oldAccountId = $this->transaction->account_id;

        if ($this->transaction->type === 'pengeluaran') {
            $room = $user->rooms()->find($this->roomId);
            if (! $room) {
                $this->error = 'Pilih ruang.';

                return;
            }
            if ($room->archived && $room->id !== $this->transaction->room_id) {
                $this->error = 'Ruang ini sudah diarsipkan — pilih ruang lain.';

                return;
            }

            $category = $this->categoryId ? Category::where('room_id', $room->id)->find($this->categoryId) : null;
            if (! $category) {
                $this->error = 'Pilih kategori yang sesuai dengan ruang.';

                return;
            }
            if ($category->archived && $category->id !== $this->transaction->category_id) {
                $this->error = 'Kategori ini sudah diarsipkan — pilih kategori lain.';

                return;
            }

            $this->transaction->update([
                'amount_minor' => $amount,
                'room_id' => $room->id,
                'category_id' => $category->id,
                'category' => $category->name,
                'account_id' => $account->id,
                'note' => $note !== '' ? $note : null,
                'occurred_on' => $date,
            ]);
        } else {
            $source = trim($this->source);
            if (mb_strlen($source) > self::SOURCE_MAX) {
                $this->error = 'Sumber paling banyak '.self::SOURCE_MAX.' karakter.';

                return;
            }

            $this->transaction->update([
                'amount_minor' => $amount,
                'category' => $source !== '' ? $source : null,
                'account_id' => $account->id,
                'note' => $note !== '' ? $note : null,
                'occurred_on' => $date,
            ]);
        }

        $this->applyBalanceDelta($this->transaction->type, $oldAccountId, $oldAmount, $account->id, $amount);

        $this->editing = false;
    }

    public function delete(): void
    {
        $type = $this->transaction->type;
        $accountId = $this->transaction->account_id;
        $amount = $this->transaction->amount_minor;

        $this->transaction->delete();

        if ($accountId) {
            // Hapus pengeluaran mengembalikan uangnya; hapus pemasukan mengambilnya kembali.
            $delta = $type === 'pengeluaran' ? $amount : -$amount;
            Account::where('id', $accountId)->increment('balance_minor', $delta);
        }

        $this->redirect(route('transaksi'));
    }

    /**
     * Pengeluaran mengurangi saldo akunnya, pemasukan menambahnya. Dipanggil setelah kolomnya
     * tersimpan supaya saldo akun lama dan baru sama-sama disesuaikan bila akunnya ikut berganti.
     */
    private function applyBalanceDelta(string $type, ?int $oldAccountId, int $oldAmount, int $newAccountId, int $newAmount): void
    {
        $sign = $type === 'pengeluaran' ? -1 : 1;

        if ($oldAccountId === $newAccountId) {
            Account::where('id', $newAccountId)->increment('balance_minor', $sign * ($newAmount - $oldAmount));

            return;
        }

        if ($oldAccountId) {
            Account::where('id', $oldAccountId)->increment('balance_minor', $sign * -$oldAmount);
        }
        Account::where('id', $newAccountId)->increment('balance_minor', $sign * $newAmount);
    }

    public function render(): View
    {
        $user = auth()->user();

        $rooms = $user->rooms()->active()->orderBy('sort_order')->get();
        if ($this->transaction->room_id && $rooms->doesntContain('id', $this->transaction->room_id)) {
            $used = Room::find($this->transaction->room_id);
            if ($used) {
                $rooms->push($used);
            }
        }

        $accounts = $user->accounts()->where('archived', false)->orderBy('sort_order')->get();
        if ($this->transaction->account_id && $accounts->doesntContain('id', $this->transaction->account_id)) {
            $used = Account::find($this->transaction->account_id);
            if ($used) {
                $accounts->push($used);
            }
        }

        $categories = collect();
        if ($this->transaction->type === 'pengeluaran' && $this->roomId) {
            $room = $rooms->firstWhere('id', $this->roomId) ?? Room::find($this->roomId);
            $categories = $room?->categories()->where('archived', false)->orderBy('sort_order')->get() ?? collect();
            if ($this->transaction->category_id && $categories->doesntContain('id', $this->transaction->category_id) && $room?->id === $this->transaction->room_id) {
                $used = Category::find($this->transaction->category_id);
                if ($used) {
                    $categories->push($used);
                }
            }
        }

        return view('livewire.transaksi-detail', [
            'rooms' => $rooms,
            'accounts' => $accounts,
            'categories' => $categories,
        ])->layout('components.layout', ['title' => 'Detail transaksi — Rizqflow']);
    }
}
