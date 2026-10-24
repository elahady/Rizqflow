<?php

namespace App\Models;

use App\Support\Ledger;
use Carbon\CarbonImmutable;
use Carbon\CarbonInterface;
use Illuminate\Database\Eloquent\Builder;
use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Database\Eloquent\Relations\HasMany;

/**
 * Satu transaksi: `pemasukan` atau `pengeluaran` (transfer belum ada di model data web). Nominal
 * selalu positif, jenis menentukan arah (docs/model-data.md). Kolom teks `category` menyimpan nama
 * pos untuk pengeluaran dan sumber (Gaji, Usaha, ...) untuk pemasukan; relasi ke tabel pos memakai
 * nama `pos()` supaya tidak bentrok dengan kolom itu. Catat, ubah nominal/pos/akun, dan hapus lewat
 * App\Support\Ledger supaya saldo akun dan potret alokasi tetap benar.
 */
class Transaction extends Model
{
    use HasFactory;

    public const TYPE_INCOME = 'pemasukan';

    public const TYPE_EXPENSE = 'pengeluaran';

    protected $fillable = [
        'user_id', 'room_id', 'category_id', 'account_id', 'recurring_rule_id', 'bill_id', 'type', 'amount_minor',
        'category', 'note', 'recipient_institution', 'receipt_number', 'occurred_on',
    ];

    protected $casts = [
        'occurred_on' => 'date',
        'amount_minor' => 'integer',
    ];

    public function user(): BelongsTo
    {
        return $this->belongsTo(User::class);
    }

    public function room(): BelongsTo
    {
        return $this->belongsTo(Room::class);
    }

    public function account(): BelongsTo
    {
        return $this->belongsTo(Account::class);
    }

    /** Pos (kategori) pengeluaran ini. Bukan `category()`: nama itu sudah dipakai kolom teks. */
    public function pos(): BelongsTo
    {
        return $this->belongsTo(Category::class, 'category_id');
    }

    public function recurringRule(): BelongsTo
    {
        return $this->belongsTo(RecurringRule::class);
    }

    public function bill(): BelongsTo
    {
        return $this->belongsTo(Bill::class);
    }

    /** Potret alokasi pemasukan ini; kosong untuk pengeluaran. Urut sesuai prioritas ruang lewat sortedAllocationEntries(). */
    public function allocationEntries(): HasMany
    {
        return $this->hasMany(AllocationEntry::class);
    }

    public function scopeIncome(Builder $query): Builder
    {
        return $query->where($query->qualifyColumn('type'), self::TYPE_INCOME);
    }

    public function scopeExpense(Builder $query): Builder
    {
        return $query->where($query->qualifyColumn('type'), self::TYPE_EXPENSE);
    }

    /** Pengeluaran di pos sistem Tak terlacak (ruang mana pun): yang belum punya pos sungguhan. */
    public function scopeUntracked(Builder $query): Builder
    {
        return $query->expense()->whereHas('pos', fn (Builder $pos) => $pos->untracked());
    }

    /** Transaksi pada bulan Masehi $month (tanggal berapa pun di bulan itu). */
    public function scopeInMonth(Builder $query, CarbonInterface $month): Builder
    {
        $start = CarbonImmutable::instance($month)->startOfMonth();

        // Batas bawah inklusif dan batas atas eksklusif sebagai string tanggal: aman untuk kolom yang
        // tersimpan "Y-m-d" maupun "Y-m-d H:i:s" (lihat catatan endOfDay di MonthLedger).
        return $query
            ->where($query->qualifyColumn('occurred_on'), '>=', $start->toDateString())
            ->where($query->qualifyColumn('occurred_on'), '<', $start->addMonthNoOverflow()->toDateString());
    }

    public function isIncome(): bool
    {
        return $this->type === self::TYPE_INCOME;
    }

    public function isExpense(): bool
    {
        return $this->type === self::TYPE_EXPENSE;
    }

    /**
     * Potret alokasi urut prioritas ruang sekarang (rooms.sort_order), sama seperti entriesOf di
     * Android. Memuat relasi room bila belum dimuat.
     *
     * @return \Illuminate\Support\Collection<int, AllocationEntry>
     */
    public function sortedAllocationEntries()
    {
        $this->loadMissing('allocationEntries.room');

        return $this->allocationEntries
            ->sort(fn (AllocationEntry $a, AllocationEntry $b) => [$a->room?->sort_order ?? PHP_INT_MAX, $a->room_id] <=> [$b->room?->sort_order ?? PHP_INT_MAX, $b->room_id])
            ->values();
    }

    /** Bagian pemasukan yang sudah dialirkan ke ruang (jumlah potretnya). */
    public function allocatedMinor(): int
    {
        return (int) $this->allocationEntries->sum('amount_minor');
    }

    /** Bagian pemasukan yang belum dialirkan ke ruang mana pun; 0 untuk pengeluaran. */
    public function unallocatedMinor(): int
    {
        return $this->isIncome() ? max($this->amount_minor - $this->allocatedMinor(), 0) : 0;
    }

    /**
     * Label pembagian pemasukan dari potretnya, mis. "10 / 30 / 60" untuk "Dialirkan 10 / 30 / 60".
     * Null bila bukan pemasukan atau belum dialirkan sama sekali.
     */
    public function allocationLabel(): ?string
    {
        if (! $this->isIncome()) {
            return null;
        }

        $entries = $this->sortedAllocationEntries()->filter(fn (AllocationEntry $e) => $e->share_bp > 0);

        return $entries->isEmpty() ? null : Ledger::shareLabel($entries->pluck('share_bp')->all());
    }
}
