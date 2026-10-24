<?php

namespace App\Support;

use App\Models\Account;
use App\Models\AllocationEntry;
use App\Models\Category;
use App\Models\Room;
use App\Models\Transaction;
use App\Models\User;
use Carbon\CarbonInterface;
use Illuminate\Support\Carbon;
use Illuminate\Support\Facades\DB;
use InvalidArgumentException;

/**
 * Mencatat, mengubah, dan menghapus transaksi web: porting bagian LedgerService di :domain
 * (domain/.../ledger/LedgerService.kt) yang dipakai web. Satu-satunya jalan yang menggerakkan
 * saldo akun (`accounts.balance_minor`) dan potret alokasi (`allocation_entries`) bersama-sama, di
 * dalam satu transaksi database.
 *
 * Pemasukan dialirkan dengan aturan alokasi saat itu dan **potretnya disimpan** (keputusan
 * 2026-09-21): mengubah aturan sesudahnya tidak mengubah riwayat, dan mengubah nominal menghitung
 * ulang dengan persentase potret itu. Tanpa aturan, pemasukan tetap satu transaksi dan seluruhnya
 * menjadi "belum dialirkan" (diturunkan, tidak disimpan).
 *
 * Penolakan berupa App\Support\LedgerException dengan pesan UI siap tampil. Metode yang menerima
 * Transaction tidak tahu siapa yang masuk: pemanggil wajib mengambil transaksinya lewat relasi
 * pengguna (mis. `$user->transactions()->findOrFail($id)`); akun, pos, tagihan, dan aturan berulang
 * yang ikut diberikan diperiksa milik pengguna transaksi itu.
 */
class Ledger
{
    public const NOTE_MAX = 200;

    public const SOURCE_MAX = 40;

    public const RECIPIENT_MAX = 100;

    public const RECEIPT_MAX = 60;

    /** Nominal satu transaksi paling besar (batas aman AllocationEngine, sekitar Rp 922 triliun). */
    public const MAX_AMOUNT_MINOR = AllocationEngine::MAX_AMOUNT_MINOR;

    public const MODE_PERCENTAGE = 'persentase';

    public const MODE_ADVANCED = 'lanjutan';

    /** Label pil aturan untuk mode lanjutan. */
    public const ADVANCED_LABEL = 'Prioritas';

    /** Kolom tambahan yang boleh diisi recordExpense lewat $extra. */
    public const EXPENSE_EXTRA_KEYS = ['bill_id', 'recurring_rule_id', 'recipient_institution', 'receipt_number'];

    /**
     * Mode aturan alokasi yang berlaku untuk pengguna ini. Mode lanjutan (Pro) hanya berlaku bila
     * dipilih (`users.allocation_mode`) **dan** paketnya membukanya lewat Entitlements, sama dengan
     * yang ditampilkan halaman Aturan alokasi; turun paket kembali ke persentase tanpa mengubah data.
     */
    public static function allocationMode(User $user): string
    {
        return $user->allocation_mode === self::MODE_ADVANCED && $user->entitlements()->isEnabled(Feature::AdvancedAllocationRules)
            ? self::MODE_ADVANCED
            : self::MODE_PERCENTAGE;
    }

    /**
     * Pratinjau "Alirkan rezeki": bagaimana $amountMinor akan dialirkan dengan aturan sekarang.
     * Tidak menyimpan apa pun. Nominal 0 boleh (semua bagian 0), untuk pratinjau saat kolom masih kosong.
     *
     * @return array{
     *     mode: string,
     *     rule_label: ?string,
     *     amount_minor: int,
     *     rooms: list<array{room: Room, share_bp: int, cap_minor: ?int, amount_minor: int}>,
     *     allocated_minor: int,
     *     unallocated_minor: int,
     *     rounding_minor: int,
     * }
     *   `rooms` urut prioritas (rooms.sort_order). Mode persentase: ruang aktif dengan persen > 0,
     *   `share_bp` = persen aturan × 100, `cap_minor` null. Mode lanjutan: semua ruang aktif,
     *   `cap_minor` = batas atas (null = tak terbatas), `share_bp` = persentase yang akan disimpan di
     *   potret (impliedShares). `rule_label`: "10 / 30 / 60" (persentase), "Prioritas" (lanjutan), atau
     *   null bila belum ada aturan. `rounding_minor`: sisa pembulatan yang dibagikan satu-satu ke ruang
     *   dengan pecahan terbesar (selalu 0 di mode lanjutan).
     *
     * @throws LedgerException nominal negatif atau di atas batas, atau aturan persentase melebihi 100%
     */
    public static function previewIncome(User $user, int $amountMinor): array
    {
        if ($amountMinor < 0) {
            throw new LedgerException(LedgerException::AMOUNT_NOT_POSITIVE, 'Nominal tidak boleh negatif.');
        }
        self::assertNotTooLarge($amountMinor);

        return self::plan($user, $amountMinor);
    }

    /**
     * Mencatat pemasukan, menyimpan potretnya (satu baris untuk setiap ruang yang persentasenya lebih
     * dari nol, termasuk yang nominalnya 0, docs/model-data.md), dan menambah saldo akun sebesar
     * seluruh nominal (bukan hanya bagian yang dialirkan). Sumber disimpan di kolom teks `category`.
     *
     * @throws LedgerException
     */
    public static function recordIncome(
        User $user,
        int $amountMinor,
        Account $account,
        CarbonInterface $date,
        ?string $source,
        ?string $note,
        ?int $recurringRuleId = null,
    ): Transaction {
        self::assertAmount($amountMinor);
        $note = self::clean($note, self::NOTE_MAX, LedgerException::NOTE_TOO_LONG, 'Catatan paling banyak '.self::NOTE_MAX.' karakter.');
        $source = self::clean($source, self::SOURCE_MAX, LedgerException::SOURCE_TOO_LONG, 'Sumber paling banyak '.self::SOURCE_MAX.' karakter.');
        self::assertAccountUsable((int) $user->id, $account);
        if ($recurringRuleId !== null && ! $user->recurringRules()->whereKey($recurringRuleId)->exists()) {
            throw new LedgerException(LedgerException::RECURRING_NOT_FOUND, 'Aturan berulang tidak ditemukan.');
        }

        return DB::transaction(function () use ($user, $amountMinor, $account, $date, $source, $note, $recurringRuleId) {
            $plan = self::plan($user, $amountMinor);

            $transaction = Transaction::create([
                'user_id' => $user->id,
                'room_id' => null,
                'category_id' => null,
                'account_id' => $account->id,
                'recurring_rule_id' => $recurringRuleId,
                'type' => Transaction::TYPE_INCOME,
                'amount_minor' => $amountMinor,
                'category' => $source,
                'note' => $note,
                'occurred_on' => self::day($date),
            ]);

            $rows = [];
            foreach ($plan['rooms'] as $share) {
                // Mode persentase: semua ruang di rencana punya persen > 0. Mode lanjutan: ruang yang
                // mendapat nominal tetap disimpan walau persentase turunannya 0 (lihat laporan port).
                if ($share['share_bp'] > 0 || $share['amount_minor'] > 0) {
                    $rows[] = ['room_id' => $share['room']->id, 'share_bp' => $share['share_bp'], 'amount_minor' => $share['amount_minor']];
                }
            }
            $entries = $transaction->allocationEntries()->createMany($rows);

            $account->increment('balance_minor', $amountMinor);

            return $transaction->setRelation('allocationEntries', $entries);
        });
    }

    /**
     * Mencatat pengeluaran di pos $category (ruangnya diturunkan dari pos) dan mengurangi saldo akun.
     * Kolom teks `category` diisi nama pos. $extra boleh berisi kolom tambahan: bill_id,
     * recurring_rule_id (keduanya harus milik pengguna), recipient_institution (maks 100 karakter),
     * dan receipt_number (maks 60 karakter) untuk rekap zakat.
     *
     * @param  array<string, mixed>  $extra
     *
     * @throws LedgerException
     */
    public static function recordExpense(
        User $user,
        int $amountMinor,
        Category $category,
        Account $account,
        CarbonInterface $date,
        ?string $note,
        array $extra = [],
    ): Transaction {
        self::assertAmount($amountMinor);
        $note = self::clean($note, self::NOTE_MAX, LedgerException::NOTE_TOO_LONG, 'Catatan paling banyak '.self::NOTE_MAX.' karakter.');
        self::assertCategoryUsable((int) $user->id, $category);
        self::assertAccountUsable((int) $user->id, $account);
        $extra = self::expenseExtra($user, $extra);

        return DB::transaction(function () use ($user, $amountMinor, $category, $account, $date, $note, $extra) {
            $transaction = Transaction::create([
                'user_id' => $user->id,
                'room_id' => $category->room_id,
                'category_id' => $category->id,
                'account_id' => $account->id,
                'type' => Transaction::TYPE_EXPENSE,
                'amount_minor' => $amountMinor,
                'category' => $category->name,
                'note' => $note,
                'occurred_on' => self::day($date),
            ] + $extra);

            $account->increment('balance_minor', -$amountMinor);

            return $transaction;
        });
    }

    /**
     * Mengubah nominal pemasukan (S09): potret dihitung ulang dengan persentase yang tersimpan saat
     * dicatat (bukan aturan sekarang, juga untuk pemasukan mode lanjutan), urut prioritas ruang
     * sekarang; saldo akun bergeser sebesar selisihnya. Pemasukan tanpa potret tetap tanpa potret.
     *
     * @throws LedgerException
     */
    public static function changeIncomeAmount(Transaction $income, int $newAmountMinor): Transaction
    {
        if (! $income->isIncome()) {
            throw new LedgerException(LedgerException::NOT_AN_INCOME, 'Transaksi ini bukan pemasukan.');
        }
        self::assertAmount($newAmountMinor);

        return DB::transaction(function () use ($income, $newAmountMinor) {
            $income->load('allocationEntries.room');
            $entries = $income->sortedAllocationEntries();
            $rules = $entries->map(fn (AllocationEntry $e) => ['room_id' => $e->room_id, 'share_bp' => $e->share_bp])->all();

            try {
                $result = AllocationEngine::allocate($newAmountMinor, $rules);
            } catch (InvalidArgumentException) {
                throw new LedgerException(LedgerException::RULES_INVALID, 'Potret alokasi pemasukan ini tidak sah, jadi nominalnya tidak bisa dihitung ulang.');
            }

            foreach ($entries as $i => $entry) {
                $entry->update(['amount_minor' => $result['shares'][$i]['amount_minor']]);
            }

            $delta = $newAmountMinor - $income->amount_minor;
            $income->update(['amount_minor' => $newAmountMinor]);
            self::moveBalance($income->account_id, $delta);

            return $income->setRelation('allocationEntries', $entries);
        });
    }

    /**
     * Mengubah nominal pengeluaran; saldo akun bergeser sebesar selisihnya.
     *
     * @throws LedgerException
     */
    public static function changeExpenseAmount(Transaction $expense, int $newAmountMinor): Transaction
    {
        if (! $expense->isExpense()) {
            throw new LedgerException(LedgerException::NOT_AN_EXPENSE, 'Transaksi ini bukan pengeluaran.');
        }
        self::assertAmount($newAmountMinor);

        return DB::transaction(function () use ($expense, $newAmountMinor) {
            $delta = $newAmountMinor - $expense->amount_minor;
            $expense->update(['amount_minor' => $newAmountMinor]);
            self::moveBalance($expense->account_id, -$delta);

            return $expense;
        });
    }

    /**
     * Memindahkan pengeluaran ke pos (dan ruangnya) lain, mis. edit massal di halaman Transaksi.
     * Saldo tidak berubah. Pos atau ruang terarsip hanya boleh bila memang sudah dipakai transaksi ini.
     *
     * @throws LedgerException
     */
    public static function changeExpenseCategory(Transaction $expense, Category $category): Transaction
    {
        if (! $expense->isExpense()) {
            throw new LedgerException(LedgerException::NOT_AN_EXPENSE, 'Transaksi ini bukan pengeluaran.');
        }

        $category->loadMissing('room');
        if (! $category->room || (int) $category->room->user_id !== (int) $expense->user_id) {
            throw new LedgerException(LedgerException::CATEGORY_NOT_FOUND, 'Pos tidak ditemukan.');
        }
        if ((int) $category->id === (int) $expense->category_id) {
            return $expense;
        }
        if ($category->archived) {
            throw new LedgerException(LedgerException::CATEGORY_ARCHIVED, 'Pos ini sudah diarsipkan — pilih pos lain.');
        }
        if ($category->room->archived && (int) $category->room_id !== (int) $expense->room_id) {
            throw new LedgerException(LedgerException::ROOM_ARCHIVED, 'Ruang ini sudah diarsipkan — pilih ruang lain.');
        }

        $expense->update([
            'room_id' => $category->room_id,
            'category_id' => $category->id,
            'category' => $category->name,
        ]);

        return $expense;
    }

    /**
     * Memindahkan transaksi (pemasukan atau pengeluaran) ke akun lain: akun lama dikembalikan,
     * akun baru menanggungnya. Akun terarsip hanya boleh bila memang sudah dipakai transaksi ini.
     *
     * @throws LedgerException
     */
    public static function changeAccount(Transaction $transaction, Account $account): Transaction
    {
        if ((int) $account->user_id !== (int) $transaction->user_id) {
            throw new LedgerException(LedgerException::ACCOUNT_NOT_FOUND, 'Akun tidak ditemukan.');
        }
        if ((int) $account->id === (int) $transaction->account_id) {
            return $transaction;
        }
        if ($account->archived) {
            throw new LedgerException(LedgerException::ACCOUNT_ARCHIVED, 'Akun ini sudah diarsipkan — pilih akun lain.');
        }

        return DB::transaction(function () use ($transaction, $account) {
            $effect = self::balanceEffect($transaction);
            self::moveBalance($transaction->account_id, -$effect);
            $transaction->update(['account_id' => $account->id]);
            $account->increment('balance_minor', $effect);

            return $transaction;
        });
    }

    /** Menghapus transaksi: pengaruhnya pada saldo akun dibalik, potret alokasinya ikut terhapus. */
    public static function delete(Transaction $transaction): void
    {
        DB::transaction(function () use ($transaction) {
            self::moveBalance($transaction->account_id, -self::balanceEffect($transaction));
            $transaction->allocationEntries()->delete();
            $transaction->delete();
        });
    }

    /**
     * Basis point sebagai label pil: [1000, 3000, 6000] menjadi "10 / 30 / 60", 250 menjadi "2,5".
     *
     * @param  iterable<int>  $bps
     */
    public static function shareLabel(iterable $bps): string
    {
        $parts = [];
        foreach ($bps as $bp) {
            $whole = intdiv($bp, 100);
            $fraction = $bp % 100;
            $parts[] = match (true) {
                $fraction === 0 => (string) $whole,
                $fraction % 10 === 0 => $whole.','.intdiv($fraction, 10),
                default => $whole.','.str_pad((string) $fraction, 2, '0', STR_PAD_LEFT),
            };
        }

        return implode(' / ', $parts);
    }

    /**
     * Rencana alokasi untuk $amountMinor dengan aturan sekarang (lihat previewIncome untuk bentuknya).
     */
    private static function plan(User $user, int $amountMinor): array
    {
        $mode = self::allocationMode($user);
        $rooms = $user->rooms()->active()->orderBy('id')->get(); // relasi sudah urut sort_order

        if ($mode === self::MODE_ADVANCED) {
            $caps = $rooms->map(fn (Room $r) => ['room_id' => $r->id, 'cap_minor' => $r->allocation_cap_minor])->values()->all();
            $waterfall = AllocationEngine::allocateWaterfall($amountMinor, $caps);
            $implied = AllocationEngine::impliedShares($amountMinor, $waterfall['shares']);

            $shares = [];
            foreach ($rooms->values() as $i => $room) {
                $shares[] = [
                    'room' => $room,
                    'share_bp' => $implied[$i]['share_bp'],
                    'cap_minor' => $room->allocation_cap_minor,
                    'amount_minor' => $waterfall['shares'][$i]['amount_minor'],
                ];
            }

            return self::planResult($mode, $rooms->isEmpty() ? null : self::ADVANCED_LABEL, $amountMinor, $shares, $waterfall['unallocated_minor'], 0);
        }

        $ruled = $rooms->filter(fn (Room $r) => (int) $r->allocation_percent > 0)->values();
        $rules = $ruled->map(fn (Room $r) => ['room_id' => $r->id, 'share_bp' => (int) $r->allocation_percent * 100])->all();
        if (array_sum(array_column($rules, 'share_bp')) > AllocationEngine::FULL_BP) {
            throw new LedgerException(LedgerException::RULES_INVALID, 'Total aturan alokasi melebihi 100%. Atur ulang di Aturan alokasi.');
        }

        $result = AllocationEngine::allocate($amountMinor, $rules);
        $shares = [];
        foreach ($ruled as $i => $room) {
            $shares[] = [
                'room' => $room,
                'share_bp' => $result['shares'][$i]['share_bp'],
                'cap_minor' => null,
                'amount_minor' => $result['shares'][$i]['amount_minor'],
            ];
        }

        $label = $rules === [] ? null : self::shareLabel(array_column($rules, 'share_bp'));

        return self::planResult($mode, $label, $amountMinor, $shares, $result['unallocated_minor'], $result['rounding_minor']);
    }

    private static function planResult(string $mode, ?string $label, int $amountMinor, array $shares, int $unallocated, int $rounding): array
    {
        return [
            'mode' => $mode,
            'rule_label' => $label,
            'amount_minor' => $amountMinor,
            'rooms' => $shares,
            'allocated_minor' => $amountMinor - $unallocated,
            'unallocated_minor' => $unallocated,
            'rounding_minor' => $rounding,
        ];
    }

    /** Pengaruh transaksi pada saldo akunnya: pemasukan menambah, pengeluaran mengurangi. */
    private static function balanceEffect(Transaction $transaction): int
    {
        return match ($transaction->type) {
            Transaction::TYPE_INCOME => $transaction->amount_minor,
            Transaction::TYPE_EXPENSE => -$transaction->amount_minor,
            default => 0,
        };
    }

    private static function moveBalance(?int $accountId, int $delta): void
    {
        if ($accountId !== null && $delta !== 0) {
            Account::whereKey($accountId)->increment('balance_minor', $delta);
        }
    }

    /** Tanggal transaksi tanpa jam, disimpan seperti baris lain ("Y-m-d 00:00:00"). */
    private static function day(CarbonInterface $date): Carbon
    {
        return Carbon::instance($date)->startOfDay();
    }

    private static function assertAmount(int $amountMinor): void
    {
        if ($amountMinor <= 0) {
            throw new LedgerException(LedgerException::AMOUNT_NOT_POSITIVE, 'Nominal wajib lebih dari nol.');
        }
        self::assertNotTooLarge($amountMinor);
    }

    private static function assertNotTooLarge(int $amountMinor): void
    {
        if ($amountMinor > self::MAX_AMOUNT_MINOR) {
            throw new LedgerException(LedgerException::AMOUNT_TOO_LARGE, 'Nominal terlalu besar.');
        }
    }

    private static function assertAccountUsable(int $userId, Account $account): void
    {
        if ((int) $account->user_id !== $userId) {
            throw new LedgerException(LedgerException::ACCOUNT_NOT_FOUND, 'Akun tidak ditemukan.');
        }
        if ($account->archived) {
            throw new LedgerException(LedgerException::ACCOUNT_ARCHIVED, 'Akun ini sudah diarsipkan — pilih akun lain.');
        }
    }

    private static function assertCategoryUsable(int $userId, Category $category): void
    {
        $category->loadMissing('room');
        if (! $category->room || (int) $category->room->user_id !== $userId) {
            throw new LedgerException(LedgerException::CATEGORY_NOT_FOUND, 'Pos tidak ditemukan.');
        }
        if ($category->room->archived) {
            throw new LedgerException(LedgerException::ROOM_ARCHIVED, 'Ruang ini sudah diarsipkan — pilih ruang lain.');
        }
        if ($category->archived) {
            throw new LedgerException(LedgerException::CATEGORY_ARCHIVED, 'Pos ini sudah diarsipkan — pilih pos lain.');
        }
    }

    /**
     * @param  array<string, mixed>  $extra
     * @return array<string, mixed>
     */
    private static function expenseExtra(User $user, array $extra): array
    {
        $unknown = array_diff(array_keys($extra), self::EXPENSE_EXTRA_KEYS);
        if ($unknown !== []) {
            throw new InvalidArgumentException('Kolom tambahan tidak dikenal: '.implode(', ', $unknown));
        }

        $clean = [];
        if (isset($extra['bill_id'])) {
            if (! $user->bills()->whereKey($extra['bill_id'])->exists()) {
                throw new LedgerException(LedgerException::BILL_NOT_FOUND, 'Tagihan tidak ditemukan.');
            }
            $clean['bill_id'] = (int) $extra['bill_id'];
        }
        if (isset($extra['recurring_rule_id'])) {
            if (! $user->recurringRules()->whereKey($extra['recurring_rule_id'])->exists()) {
                throw new LedgerException(LedgerException::RECURRING_NOT_FOUND, 'Aturan berulang tidak ditemukan.');
            }
            $clean['recurring_rule_id'] = (int) $extra['recurring_rule_id'];
        }
        if (array_key_exists('recipient_institution', $extra)) {
            $clean['recipient_institution'] = self::clean($extra['recipient_institution'], self::RECIPIENT_MAX, LedgerException::FIELD_TOO_LONG, 'Lembaga penerima paling banyak '.self::RECIPIENT_MAX.' karakter.');
        }
        if (array_key_exists('receipt_number', $extra)) {
            $clean['receipt_number'] = self::clean($extra['receipt_number'], self::RECEIPT_MAX, LedgerException::FIELD_TOO_LONG, 'Nomor bukti setor paling banyak '.self::RECEIPT_MAX.' karakter.');
        }

        return $clean;
    }

    /** Teks dirapikan; kosong menjadi null; terlalu panjang ditolak. */
    private static function clean(?string $raw, int $max, string $reason, string $message): ?string
    {
        $text = $raw === null ? null : trim($raw);
        if ($text === null || $text === '') {
            return null;
        }
        if (mb_strlen($text) > $max) {
            throw new LedgerException($reason, $message);
        }

        return $text;
    }
}
