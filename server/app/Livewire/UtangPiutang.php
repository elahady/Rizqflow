<?php

namespace App\Livewire;

use App\Models\Account;
use App\Models\Debt;
use App\Models\DebtPayment;
use App\Support\Rupiah;
use Illuminate\Contracts\View\View;
use Illuminate\Support\Carbon;
use Livewire\Component;

/**
 * Utang-piutang (S35): lihat Debt/DebtService di :domain untuk acuan lengkap. Perpindahan uangnya
 * (pinjaman awal dan pelunasan) menggerakkan Account.balance_minor langsung di sini — **tidak**
 * pernah lewat tabel transactions (tidak ada jenis transaksi pinjaman di model data web, konsisten
 * dengan transfer yang juga belum ada), supaya tidak ikut terhitung di jatah ruang mana pun.
 */
class UtangPiutang extends Component
{
    private const NOTE_MAX = 200;

    public bool $showForm = false;

    public string $direction = 'piutang';

    public string $party = '';

    public string $amount = '';

    public string $startDate = '';

    public string $dueDate = '';

    public string $note = '';

    public bool $collectible = true;

    public bool $recordMovement = true;

    public ?int $accountId = null;

    public ?string $error = null;

    public ?int $expandedId = null;

    public ?int $editingId = null;

    public string $editParty = '';

    public string $editDueDate = '';

    public string $editNote = '';

    public bool $editCollectible = true;

    public ?string $editError = null;

    public ?int $payingId = null;

    public string $payAmount = '';

    public bool $payUpdateBalance = true;

    public ?int $payAccountId = null;

    public ?string $payError = null;

    public function startAdd(): void
    {
        $this->direction = 'piutang';
        $this->party = '';
        $this->amount = '';
        $this->startDate = Carbon::today()->toDateString();
        $this->dueDate = '';
        $this->note = '';
        $this->collectible = true;
        $this->recordMovement = true;
        $this->accountId = auth()->user()->accounts()->where('archived', false)->orderBy('sort_order')->value('id');
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

        $party = trim($this->party);
        if ($party === '' || mb_strlen($party) > Debt::PARTY_MAX) {
            $this->error = 'Nama pihak 1 sampai '.Debt::PARTY_MAX.' karakter.';

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

        try {
            $startDate = Carbon::parse($this->startDate)->startOfDay();
        } catch (\Exception) {
            $this->error = 'Tanggal pinjam tidak sah.';

            return;
        }

        $dueDate = null;
        if ($this->dueDate !== '') {
            try {
                $dueDate = Carbon::parse($this->dueDate)->startOfDay();
            } catch (\Exception) {
                $this->error = 'Jatuh tempo tidak sah.';

                return;
            }
            if ($dueDate->lessThan($startDate)) {
                $this->error = 'Jatuh tempo tidak boleh sebelum tanggal pinjam.';

                return;
            }
        }

        $accountId = null;
        if ($this->recordMovement) {
            $account = $user->accounts()->where('archived', false)->find($this->accountId);
            if (! $account) {
                $this->error = 'Pilih akun.';

                return;
            }
            $accountId = $account->id;
        }

        $debt = Debt::create([
            'user_id' => $user->id,
            'direction' => $this->direction,
            'party' => $party,
            'principal_minor' => $amount,
            'account_id' => $accountId,
            'start_date' => $startDate,
            'due_date' => $dueDate,
            'note' => $note !== '' ? $note : null,
            'collectible' => $this->collectible,
        ]);

        if ($accountId !== null) {
            Account::where('id', $accountId)->increment('balance_minor', $this->movementDelta($debt, $amount));
        }

        $this->showForm = false;
    }

    public function toggleDetail(int $id): void
    {
        $this->expandedId = $this->expandedId === $id ? null : $id;
        $this->editingId = null;
        $this->payingId = null;
    }

    public function startEditDebt(int $id): void
    {
        $debt = auth()->user()->debts()->findOrFail($id);
        $this->editingId = $debt->id;
        $this->editParty = $debt->party;
        $this->editDueDate = $debt->due_date?->toDateString() ?? '';
        $this->editNote = $debt->note ?? '';
        $this->editCollectible = $debt->collectible;
        $this->editError = null;
    }

    public function cancelEditDebt(): void
    {
        $this->editingId = null;
        $this->editError = null;
    }

    public function updateDebt(): void
    {
        $this->editError = null;
        $debt = auth()->user()->debts()->findOrFail($this->editingId);

        $party = trim($this->editParty);
        if ($party === '' || mb_strlen($party) > Debt::PARTY_MAX) {
            $this->editError = 'Nama pihak 1 sampai '.Debt::PARTY_MAX.' karakter.';

            return;
        }

        $note = trim($this->editNote);
        if (mb_strlen($note) > self::NOTE_MAX) {
            $this->editError = 'Catatan paling banyak '.self::NOTE_MAX.' karakter.';

            return;
        }

        $dueDate = null;
        if ($this->editDueDate !== '') {
            try {
                $dueDate = Carbon::parse($this->editDueDate)->startOfDay();
            } catch (\Exception) {
                $this->editError = 'Jatuh tempo tidak sah.';

                return;
            }
            if ($dueDate->lessThan($debt->start_date)) {
                $this->editError = 'Jatuh tempo tidak boleh sebelum tanggal pinjam.';

                return;
            }
        }

        $debt->update([
            'party' => $party,
            'due_date' => $dueDate,
            'note' => $note !== '' ? $note : null,
            'collectible' => $this->editCollectible,
        ]);

        $this->editingId = null;
    }

    public function startRepay(int $id): void
    {
        $debt = auth()->user()->debts()->findOrFail($id);
        $this->payingId = $debt->id;
        $this->payAmount = (string) $debt->outstandingMinor();
        $this->payUpdateBalance = true;
        $this->payAccountId = auth()->user()->accounts()->where('archived', false)->orderBy('sort_order')->value('id');
        $this->payError = null;
    }

    public function cancelRepay(): void
    {
        $this->payingId = null;
        $this->payError = null;
    }

    public function repay(): void
    {
        $this->payError = null;
        $debt = auth()->user()->debts()->find($this->payingId);
        if (! $debt) {
            $this->payingId = null;

            return;
        }

        $amount = (int) $this->payAmount;
        if ($amount <= 0) {
            $this->payError = 'Nominal wajib lebih dari nol.';

            return;
        }

        $outstanding = $debt->outstandingMinor();
        if ($amount > $outstanding) {
            $this->payError = 'Nominal tidak boleh melebihi sisa '.Rupiah::format($outstanding).'.';

            return;
        }

        $accountId = null;
        if ($this->payUpdateBalance) {
            $account = auth()->user()->accounts()->where('archived', false)->find($this->payAccountId);
            if (! $account) {
                $this->payError = 'Pilih akun.';

                return;
            }
            $accountId = $account->id;
        }

        DebtPayment::create([
            'debt_id' => $debt->id,
            'amount_minor' => $amount,
            'paid_on' => Carbon::today(),
            'account_id' => $accountId,
        ]);

        if ($accountId !== null) {
            Account::where('id', $accountId)->increment('balance_minor', $this->repaymentDelta($debt, $amount));
        }

        $this->payingId = null;
    }

    public function deletePayment(int $paymentId): void
    {
        $payment = DebtPayment::whereHas('debt', fn ($q) => $q->where('user_id', auth()->id()))->findOrFail($paymentId);
        $debt = $payment->debt;

        if ($payment->account_id) {
            Account::where('id', $payment->account_id)->increment('balance_minor', -$this->repaymentDelta($debt, $payment->amount_minor));
        }

        $payment->delete();
    }

    public function deleteDebt(int $id): void
    {
        $debt = auth()->user()->debts()->findOrFail($id);

        foreach ($debt->payments as $payment) {
            if ($payment->account_id) {
                Account::where('id', $payment->account_id)->increment('balance_minor', -$this->repaymentDelta($debt, $payment->amount_minor));
            }
        }

        if ($debt->account_id) {
            Account::where('id', $debt->account_id)->increment('balance_minor', -$this->movementDelta($debt, $debt->principal_minor));
        }

        $debt->delete();
        $this->expandedId = null;
    }

    /** Piutang: uang keluar (meminjamkan). Utang: uang masuk (dipinjam). */
    private function movementDelta(Debt $debt, int $amount): int
    {
        return $debt->direction === 'piutang' ? -$amount : $amount;
    }

    /** Pelunasan piutang: uang masuk (diterima kembali). Pelunasan utang: uang keluar (dibayar). */
    private function repaymentDelta(Debt $debt, int $amount): int
    {
        return $debt->direction === 'piutang' ? $amount : -$amount;
    }

    public function render(): View
    {
        $user = auth()->user();
        $debts = $user->debts()->with(['account', 'payments.account'])->get()
            ->sortBy([
                [fn ($a, $b) => (int) $a->isSettled() <=> (int) $b->isSettled(), SORT_ASC],
                [fn ($a, $b) => ($a->due_date ?? Carbon::create(9999, 12, 31)) <=> ($b->due_date ?? Carbon::create(9999, 12, 31)), SORT_ASC],
                [fn ($a, $b) => $b->start_date <=> $a->start_date, SORT_ASC],
                [fn ($a, $b) => strcasecmp($a->party, $b->party), SORT_ASC],
            ])
            ->values();

        $open = $debts->reject(fn (Debt $d) => $d->isSettled());

        return view('livewire.utang-piutang', [
            'debts' => $debts,
            'today' => Carbon::today(),
            'receivable' => $open->where('direction', 'piutang')->sum(fn (Debt $d) => $d->outstandingMinor()),
            'payable' => $open->where('direction', 'utang')->sum(fn (Debt $d) => $d->outstandingMinor()),
            'accounts' => $user->accounts()->where('archived', false)->orderBy('sort_order')->get(),
        ])->layout('components.layout', ['title' => 'Utang-piutang — Rizqflow']);
    }
}
