<?php

namespace App\Livewire;

use App\Models\Account;
use App\Models\Category;
use App\Models\Transaction;
use App\Support\Ledger;
use App\Support\LedgerException;
use Illuminate\Contracts\View\View;
use Illuminate\Support\Carbon;
use Illuminate\Support\Facades\DB;
use Livewire\Component;

/**
 * Detail dan ubah transaksi (S09): pengeluaran dan pemasukan bisa diubah dan dihapus, mengikuti
 * aturan di LedgerService.updateTransaction di :domain (akun/ruang/kategori terarsip boleh tetap
 * dipakai selama tidak diganti). Transfer belum ada di model data web, jadi tidak ditangani di sini.
 * Ruang dan alokasi pemasukan tidak bisa diedit dari sini, sama seperti mobile. Nominal, pos, akun,
 * dan hapus lewat App\Support\Ledger: ubah nominal pemasukan menghitung ulang potret alokasinya
 * dengan persentase saat dicatat, dan saldo akun ikut bergerak.
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
        $room = $this->roomId ? auth()->user()->rooms()->find($this->roomId) : null;
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

            $changes = ['note' => $note !== '' ? $note : null, 'occurred_on' => $date];
            $apply = function () use ($amount, $category, $account) {
                Ledger::changeExpenseAmount($this->transaction, $amount);
                Ledger::changeExpenseCategory($this->transaction, $category);
                Ledger::changeAccount($this->transaction, $account);
            };
        } else {
            $source = trim($this->source);
            if (mb_strlen($source) > self::SOURCE_MAX) {
                $this->error = 'Sumber paling banyak '.self::SOURCE_MAX.' karakter.';

                return;
            }

            $changes = [
                'category' => $source !== '' ? $source : null,
                'note' => $note !== '' ? $note : null,
                'occurred_on' => $date,
            ];
            $apply = function () use ($amount, $account) {
                Ledger::changeIncomeAmount($this->transaction, $amount);
                Ledger::changeAccount($this->transaction, $account);
            };
        }

        // Kolom biasa (catatan, tanggal, sumber) langsung disimpan; nominal, pos, dan akun lewat Ledger
        // supaya saldo akun dan potret alokasi pemasukan ikut benar. Semuanya satu transaksi database.
        try {
            DB::transaction(function () use ($changes, $apply) {
                $this->transaction->update($changes);
                $apply();
            });
        } catch (LedgerException $e) {
            $this->transaction->refresh();
            $this->error = $e->getMessage();

            return;
        }

        $this->editing = false;
    }

    public function delete(): void
    {
        // Saldo akun dibalik dan potret alokasi pemasukan ikut terhapus.
        Ledger::delete($this->transaction);

        $this->redirect(route('transaksi'));
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
