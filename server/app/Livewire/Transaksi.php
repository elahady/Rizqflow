<?php

namespace App\Livewire;

use App\Models\Account;
use App\Models\Category;
use App\Models\Room;
use App\Models\Transaction;
use App\Models\User;
use App\Support\Ledger;
use App\Support\LedgerException;
use App\Support\Period;
use App\Support\Rupiah;
use App\Support\SmartInput\CatatContext;
use App\Support\SmartInput\CatatDraft;
use App\Support\SmartInput\ParsedEntry;
use App\Support\SmartInput\SmartField;
use App\Support\SmartInput\SmartInputContext;
use App\Support\SmartInput\SmartInputParser;
use Carbon\CarbonImmutable;
use Illuminate\Contracts\View\View;
use Illuminate\Database\Eloquent\Relations\HasMany;
use Illuminate\Support\Carbon;
use Illuminate\Support\Collection;
use Livewire\Attributes\Url;
use Livewire\Component;

/**
 * Transaksi (tabel kerja): catat pengeluaran lewat baris cepat, daftar transaksi sebulan (atau
 * semua periode saat mencari) dengan filter, tab Tak terlacak, dan aksi massal. Tetap Gratis karena
 * ini akses ke data milik pengguna sendiri, bukan insight premium.
 *
 * Semua query dan aksi dibatasi ke data pengguna yang masuk: ID terpilih selalu diambil ulang lewat
 * `$user->transactions()` (Ledger tidak memeriksa pemilik), dan pilihan pos/akun lewat relasi pengguna.
 * Baris cepat memakai SmartInputParser: nominal, akun, pos, dan tanggal yang terbaca mengisi kolomnya
 * selama belum diubah manual. Pemasukan dan transfer tidak disimpan dari sini (pemasukan lewat
 * Catat rezeki masuk, transfer belum didukung di web).
 */
class Transaksi extends Component
{
    private const PAGE = 50;

    private const FILTERS = ['search', 'tab', 'ruang', 'pos', 'akun', 'jenis', 'bulan'];

    #[Url]
    public ?string $bulan = null;

    /** 'semua' atau 'tak' (pengeluaran di pos sistem Tak terlacak). */
    #[Url]
    public string $tab = 'semua';

    #[Url(as: 'cari')]
    public string $search = '';

    #[Url]
    public string $ruang = '';

    #[Url]
    public string $pos = '';

    #[Url]
    public string $akun = '';

    /** '' (semua), 'pemasukan', atau 'pengeluaran'. */
    #[Url]
    public string $jenis = '';

    public int $limit = self::PAGE;

    // --- Baris catat cepat ---

    public string $quickDate = '';

    public string $quickNote = '';

    public ?int $quickCategoryId = null;

    public ?int $quickAccountId = null;

    public string $quickAmount = '';

    /** Kolom yang sudah diubah manual (amount/account/pos/date): tidak ditimpa hasil baca parser. */
    public array $touched = [];

    /** Hasil baca terakhir (ParsedEntry::toArray) atau null bila catatan kosong/tak terbaca. */
    public ?array $parsed = null;

    public ?string $quickError = null;

    /** Fokus ke kolom catatan saat dibuka dari kotak perintah (?catat=). */
    public bool $focusNote = false;

    // --- Pilihan & aksi massal ---

    /** @var list<int> */
    public array $selected = [];

    /** '' | 'pos' | 'akun' */
    public string $bulkMode = '';

    public string $bulkCategoryId = '';

    public string $bulkAccountId = '';

    public ?string $notice = null;

    public string $noticeTone = 'good';

    public function mount(): void
    {
        $this->quickNote = mb_substr(trim(request()->string('catat')->toString()), 0, Ledger::NOTE_MAX);
        $this->focusNote = request()->has('catat');
        $this->applyParse();
    }

    // ------------------------------------------------------------------ filter

    public function updated(string $name): void
    {
        if (in_array($name, self::FILTERS, true)) {
            $this->selected = [];
            $this->bulkMode = '';
            $this->limit = self::PAGE;
        }
    }

    public function updatedRuang(): void
    {
        if ($this->pos === '') {
            return;
        }
        $inRoom = $this->ruang !== '' && Category::where('room_id', (int) $this->ruang)->whereKey((int) $this->pos)->exists();
        if (! $inRoom) {
            $this->pos = '';
        }
    }

    public function setTab(string $tab): void
    {
        $this->tab = $tab === 'tak' ? 'tak' : 'semua';
        $this->updated('tab');
    }

    public function resetFilters(): void
    {
        $this->search = '';
        $this->ruang = '';
        $this->pos = '';
        $this->akun = '';
        $this->jenis = '';
        $this->updated('search');
    }

    public function showMore(): void
    {
        $this->limit += self::PAGE;
    }

    // ------------------------------------------------------------------ baris cepat

    public function updatedQuickNote(): void
    {
        $this->applyParse();
    }

    public function updatedQuickAmount(): void
    {
        $this->touched['amount'] = trim($this->quickAmount) !== '';
        if (! $this->touched['amount']) {
            $this->applyParse();
        }
    }

    public function updatedQuickAccountId(): void
    {
        $this->touched['account'] = true;
    }

    public function updatedQuickCategoryId(): void
    {
        $this->touched['pos'] = true;
    }

    public function updatedQuickDate(): void
    {
        $this->touched['date'] = true;
    }

    /**
     * Menyimpan pengeluaran dari baris cepat. $stay (Shift+Enter): ruang/pos, akun, dan tanggal
     * dipertahankan untuk catatan berikutnya dan fokus kembali ke kolom catatan; tanpa itu (Enter)
     * semua isian kembali ke bawaan.
     */
    public function save(bool $stay = false): void
    {
        $this->quickError = null;
        $this->notice = null;
        $user = auth()->user();

        $entry = $this->readNote($user);
        if ($entry !== null && $entry->kind === ParsedEntry::INCOME) {
            $this->quickError = 'Catatan ini terbaca sebagai pemasukan. Catat lewat "Rezeki masuk" supaya dialirkan ke ruang.';

            return;
        }
        if ($entry !== null && $entry->kind === ParsedEntry::TRANSFER) {
            $this->quickError = 'Transfer antar akun belum didukung di web. Catat transfer lewat aplikasi Android.';

            return;
        }

        $category = $this->quickCategoryId
            ? Category::with('room')->whereIn('room_id', $user->rooms()->pluck('id'))->find($this->quickCategoryId)
            : null;
        if (! $category) {
            $this->quickError = 'Pilih ruang dan pos.';

            return;
        }
        $account = $this->quickAccountId ? $user->accounts()->find($this->quickAccountId) : null;
        if (! $account) {
            $this->quickError = 'Pilih akun.';

            return;
        }
        try {
            $date = Carbon::createFromFormat('!Y-m-d', $this->quickDate);
            if ($date === false || $date->format('Y-m-d') !== $this->quickDate) {
                throw new \InvalidArgumentException;
            }
        } catch (\Exception) {
            $this->quickError = 'Tanggal tidak sah.';

            return;
        }

        $amount = $this->digits($this->quickAmount);
        $text = trim($this->quickNote);
        $note = $entry !== null ? $entry->note : $text;

        try {
            $transaction = Ledger::recordExpense($user, $amount, $category, $account, $date, $note === '' ? null : $note);
        } catch (LedgerException $e) {
            $this->quickError = $e->getMessage();

            return;
        }

        $this->setNotice('Tersimpan: '.Rupiah::signed($transaction->amount_minor, Transaction::TYPE_EXPENSE).' · '.$category->room->name.' › '.$category->name.' · '.$account->name.'.', 'good');

        $this->quickNote = '';
        $this->quickAmount = '';
        $this->parsed = null;
        if ($stay) {
            // Pilihan manual (tanggal, pos, akun) tetap; nominal dibaca ulang dari catatan berikutnya.
            unset($this->touched['amount']);
            $this->dispatch('catat-fokus');
        } else {
            $this->touched = [];
            $this->quickDate = '';
            $this->quickAccountId = null;
            $this->quickCategoryId = null;
            $this->applyParse();
        }
    }

    // ------------------------------------------------------------------ pilihan & aksi massal

    public function toggleRow(int $id): void
    {
        if (in_array($id, $this->selected, true)) {
            $this->selected = array_values(array_diff($this->selected, [$id]));

            return;
        }
        // Hanya milik pengguna ini yang boleh dipilih.
        if (auth()->user()->transactions()->reorder()->whereKey($id)->exists()) {
            $this->selected[] = $id;
        }
    }

    public function toggleAll(): void
    {
        $user = auth()->user();
        $ids = $this->listQuery($user, $this->month())->limit($this->limit)->pluck('id')->map(fn ($id) => (int) $id)->all();
        $everySelected = $ids !== [] && array_diff($ids, $this->selected) === [];

        $this->selected = $everySelected
            ? array_values(array_diff($this->selected, $ids))
            : array_values(array_unique(array_merge($this->selected, $ids)));
    }

    public function clearSelection(): void
    {
        $this->selected = [];
        $this->bulkMode = '';
    }

    public function setBulkMode(string $mode): void
    {
        $this->bulkMode = $this->bulkMode === $mode || ! in_array($mode, ['pos', 'akun'], true) ? '' : $mode;
    }

    /** Pindahkan pengeluaran terpilih ke pos (dan ruang) lain; pemasukan dilewati. */
    public function applyBulkCategory(): void
    {
        $this->notice = null;
        $user = auth()->user();
        $category = $this->bulkCategoryId !== ''
            ? Category::with('room')->whereIn('room_id', $user->rooms()->pluck('id'))->find((int) $this->bulkCategoryId)
            : null;
        if (! $category) {
            $this->setNotice('Pilih ruang dan pos tujuan dulu.', 'warn');

            return;
        }

        $done = 0;
        $skipped = 0;
        $errors = [];
        foreach ($this->selectedTransactions($user) as $transaction) {
            if (! $transaction->isExpense()) {
                $skipped++;

                continue;
            }
            try {
                Ledger::changeExpenseCategory($transaction, $category);
                $done++;
            } catch (LedgerException $e) {
                $errors[$e->getMessage()] = true;
            }
        }

        $message = $done.' pengeluaran dipindahkan ke '.$category->room->name.' › '.$category->name.'.';
        $this->finishBulk($message, $skipped, array_keys($errors), $done);
    }

    /** Pindahkan transaksi terpilih (pemasukan maupun pengeluaran) ke akun lain. */
    public function applyBulkAccount(): void
    {
        $this->notice = null;
        $user = auth()->user();
        $account = $this->bulkAccountId !== '' ? $user->accounts()->find((int) $this->bulkAccountId) : null;
        if (! $account) {
            $this->setNotice('Pilih akun tujuan dulu.', 'warn');

            return;
        }

        $done = 0;
        $errors = [];
        foreach ($this->selectedTransactions($user) as $transaction) {
            try {
                Ledger::changeAccount($transaction, $account);
                $done++;
            } catch (LedgerException $e) {
                $errors[$e->getMessage()] = true;
            }
        }

        $this->finishBulk($done.' transaksi dipindahkan ke akun '.$account->name.'.', 0, array_keys($errors), $done);
    }

    public function deleteSelected(): void
    {
        $this->notice = null;
        $done = 0;
        foreach ($this->selectedTransactions(auth()->user()) as $transaction) {
            Ledger::delete($transaction);
            $done++;
        }

        $this->finishBulk($done.' transaksi dihapus. Saldo akun disesuaikan.', 0, [], $done);
    }

    // ------------------------------------------------------------------ render

    public function render(): View
    {
        $user = auth()->user();
        $month = $this->month();
        $searching = trim($this->search) !== '';
        $tab = $this->tab === 'tak' ? 'tak' : 'semua';

        $rooms = $user->rooms()->get();
        $activeRooms = $rooms->where('archived', false)->values();
        $categories = Category::whereIn('room_id', $rooms->pluck('id'))->orderBy('sort_order')->orderBy('id')->get()->groupBy('room_id');
        $accounts = $user->accounts()->get();
        $activeAccounts = $accounts->where('archived', false)->values();

        // Tab dan judul selalu menghitung bulan terpilih, tidak tergantung filter lain.
        $monthBase = $user->transactions()->reorder()->inMonth($month);
        $monthCount = (clone $monthBase)->count();
        $untrackedCount = (clone $monthBase)->untracked()->count();

        $query = $this->listQuery($user, $month);
        $total = (clone $query)->count();
        $sums = (clone $query)->reorder()->selectRaw('type, SUM(amount_minor) as total')->groupBy('type')->pluck('total', 'type');
        $rows = $query->with(['room', 'account', 'pos', 'allocationEntries.room'])->limit($this->limit)->get();

        $selectedRows = $this->selectedTransactions($user);
        $selectedIds = $selectedRows->pluck('id')->all();
        $selectedOut = (int) $selectedRows->where('type', Transaction::TYPE_EXPENSE)->sum('amount_minor');
        $selectedIn = (int) $selectedRows->where('type', Transaction::TYPE_INCOME)->sum('amount_minor');

        $this->selected = array_map('intval', $selectedIds);

        // Pos untuk filter: milik ruang terpilih, atau semua pos tak terarsip bila belum memilih ruang.
        $filterRooms = $this->ruang !== '' ? $rooms->where('id', (int) $this->ruang)->values() : $rooms;

        return view('livewire.transaksi', [
            'month' => $month,
            'searching' => $searching,
            'tab' => $tab,
            'monthCount' => $monthCount,
            'untrackedCount' => $untrackedCount,
            'rows' => $rows,
            'total' => $total,
            'sumOut' => (int) ($sums[Transaction::TYPE_EXPENSE] ?? 0),
            'sumIn' => (int) ($sums[Transaction::TYPE_INCOME] ?? 0),
            'rooms' => $rooms,
            'activeRooms' => $activeRooms,
            'filterRooms' => $filterRooms,
            'categories' => $categories,
            'accounts' => $accounts,
            'activeAccounts' => $activeAccounts,
            'selectedIds' => $selectedIds,
            'selectedOut' => $selectedOut,
            'selectedIn' => $selectedIn,
            'parsedView' => $this->parsedView($categories, $rooms, $accounts),
            'hasFilter' => $searching || $this->ruang !== '' || $this->pos !== '' || $this->akun !== '' || $this->jenis !== '',
        ])->layout('components.layout', ['title' => 'Transaksi — Rizqflow', 'period' => $month]);
    }

    // ------------------------------------------------------------------ bantu

    private function month(): CarbonImmutable
    {
        return Period::fromQuery($this->bulan);
    }

    /** Daftar sesuai tab dan filter: sebulan, atau semua periode bila ada teks cari. */
    private function listQuery(User $user, CarbonImmutable $month): HasMany
    {
        $query = $user->transactions()->reorder();

        $term = trim($this->search);
        if ($term === '') {
            $query->inMonth($month);
        } else {
            $amount = (int) preg_replace('/\D/', '', $term);
            $query->where(function ($q) use ($term, $amount) {
                $q->where('note', 'like', "%{$term}%");
                if ($amount > 0) {
                    $q->orWhere('amount_minor', $amount);
                }
            });
        }

        if ($this->tab === 'tak') {
            $query->untracked();
        }
        if ($this->ruang !== '') {
            $query->where('room_id', (int) $this->ruang);
        }
        if ($this->pos !== '') {
            $query->where('category_id', (int) $this->pos);
        }
        if ($this->akun !== '') {
            $query->where('account_id', (int) $this->akun);
        }
        if (in_array($this->jenis, [Transaction::TYPE_INCOME, Transaction::TYPE_EXPENSE], true)) {
            $query->where('type', $this->jenis);
        }

        return $query->orderByDesc('occurred_on')->orderByDesc('id');
    }

    /**
     * Transaksi terpilih yang benar-benar milik pengguna (ID lain dibuang diam-diam).
     *
     * @return Collection<int, Transaction>
     */
    private function selectedTransactions(User $user): Collection
    {
        $ids = array_values(array_unique(array_filter(array_map('intval', $this->selected), fn (int $id) => $id > 0)));

        return $ids === [] ? new Collection : $user->transactions()->reorder()->whereIn('id', $ids)->get();
    }

    /** @param list<string> $errors */
    private function finishBulk(string $message, int $skipped, array $errors, int $done): void
    {
        if ($skipped > 0) {
            $message .= ' '.$skipped.' pemasukan dilewati (hanya pengeluaran yang punya pos).';
        }
        if ($errors !== []) {
            $message .= ' Tidak semua berhasil: '.implode(' ', $errors);
        }

        $this->setNotice($message, $errors !== [] || ($done === 0 && $skipped > 0) ? 'warn' : 'good');
        $this->selected = [];
        $this->bulkMode = '';
        $this->bulkCategoryId = '';
        $this->bulkAccountId = '';
    }

    private function setNotice(string $message, string $tone): void
    {
        $this->notice = $message;
        $this->noticeTone = $tone;
    }

    private function digits(string $text): int
    {
        $digits = preg_replace('/\D/', '', $text) ?? '';

        return $digits === '' ? 0 : (int) $digits;
    }

    private function readNote(User $user): ?ParsedEntry
    {
        $text = trim($this->quickNote);
        if ($text === '') {
            return null;
        }

        return SmartInputParser::parse($text, SmartInputContext::forUser($user, CarbonImmutable::now()->startOfDay()));
    }

    /**
     * Membaca catatan dan mengisi nominal, akun, pos, dan tanggal yang belum diubah manual. Yang
     * tidak terbaca kembali ke bawaan (akun dan pos terakhir dipakai, tanggal hari ini).
     */
    private function applyParse(): void
    {
        $user = auth()->user();
        $today = CarbonImmutable::now()->startOfDay();
        $smart = SmartInputContext::forUser($user, $today);
        $catat = CatatContext::forUser($user, $smart);

        $text = trim($this->quickNote);
        $entry = $text !== '' ? SmartInputParser::parse($text, $smart) : null;
        $this->parsed = $entry?->toArray();

        $draft = $entry !== null && $entry->kind === ParsedEntry::EXPENSE
            ? SmartInputParser::toDraft($entry, $catat, $today)
            : CatatDraft::start($catat, $today, ParsedEntry::EXPENSE);
        $expense = $entry !== null && $entry->kind === ParsedEntry::EXPENSE;

        if (empty($this->touched['amount'])) {
            $this->quickAmount = $expense ? Rupiah::plain($entry->amountMinor) : '';
        }
        if (empty($this->touched['account'])) {
            $this->quickAccountId = $draft->accountId;
        }
        if (empty($this->touched['pos'])) {
            $this->quickCategoryId = $draft->categoryId;
        }
        if (empty($this->touched['date'])) {
            $this->quickDate = $draft->date->toDateString();
        }
    }

    /**
     * Bahan baris "Terbaca otomatis": hanya yang benar-benar dikenali parser.
     *
     * @param  Collection<int, Collection<int, Category>>  $categories  pos per id ruang
     * @param  Collection<int, Room>  $rooms
     * @param  Collection<int, Account>  $accounts
     * @return array{kind: string, amount: ?string, account: ?string, pos: ?string, from_history: bool, date: ?string, amount_minor: int, note: string}|null
     */
    private function parsedView(Collection $categories, Collection $rooms, Collection $accounts): ?array
    {
        if ($this->parsed === null) {
            return null;
        }
        $p = $this->parsed;
        $has = fn (SmartField $f) => in_array($f->value, $p['understood'], true);

        $pos = null;
        if ($p['room_id'] !== null && ($has(SmartField::Category) || $has(SmartField::Room))) {
            $room = $rooms->firstWhere('id', $p['room_id']);
            $category = $has(SmartField::Category) && $p['category_id'] !== null
                ? $categories->get($p['room_id'], collect())->firstWhere('id', $p['category_id'])
                : null;
            if ($room) {
                $pos = $room->name.($category ? ' › '.$category->name : '');
            }
        }

        return [
            'kind' => $p['kind'],
            'amount_minor' => $p['amount_minor'],
            'amount' => $has(SmartField::Amount) ? Rupiah::format($p['amount_minor']) : null,
            'account' => $has(SmartField::Account) && $p['account_id'] !== null ? $accounts->firstWhere('id', $p['account_id'])?->name : null,
            'pos' => $pos,
            'from_history' => (bool) $p['from_history'],
            'date' => $has(SmartField::Date) ? CarbonImmutable::parse($p['date'])->locale('id')->translatedFormat('j F Y') : null,
            'note' => $p['note'],
        ];
    }
}
