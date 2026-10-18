<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Database\Eloquent\Relations\HasMany;
use Illuminate\Support\Carbon;

/**
 * Tagihan dan cicilan (S34): lihat Bill di :domain untuk acuan lengkap. Beda dari RecurringRule:
 * pembayarannya tidak pernah otomatis, selalu lewat tombol Bayar (App\Livewire\Tagihan::pay).
 */
class Bill extends Model
{
    use HasFactory;

    public const NAME_MAX = 40;

    protected $fillable = [
        'user_id', 'name', 'amount_minor', 'account_id', 'room_id', 'category_id', 'note',
        'frequency', 'start_date', 'next_due', 'total_installments', 'paid_count', 'active',
    ];

    protected $casts = [
        'start_date' => 'date',
        'next_due' => 'date',
        'active' => 'boolean',
    ];

    public function user(): BelongsTo
    {
        return $this->belongsTo(User::class);
    }

    public function account(): BelongsTo
    {
        return $this->belongsTo(Account::class);
    }

    public function room(): BelongsTo
    {
        return $this->belongsTo(Room::class);
    }

    public function category(): BelongsTo
    {
        return $this->belongsTo(Category::class);
    }

    public function transactions(): HasMany
    {
        return $this->hasMany(Transaction::class);
    }

    /** Sekali bayar (frequency null) lunas setelah dibayar sekali; berulang lunas bila cicilannya sudah mencapai totalnya. */
    public function isFinished(): bool
    {
        if ($this->frequency === null) {
            return $this->paid_count >= 1;
        }

        return $this->total_installments !== null && $this->paid_count >= $this->total_installments;
    }

    /** Cicilan yang belum dibayar; null bila tanpa batas. */
    public function remainingInstallments(): ?int
    {
        return $this->total_installments !== null ? max($this->total_installments - $this->paid_count, 0) : null;
    }

    public function statusOn(Carbon $today): string
    {
        if ($this->isFinished()) {
            return 'lunas';
        }

        if (! $this->active) {
            return 'dijeda';
        }

        if ($this->next_due->lessThan($today)) {
            return 'terlambat';
        }

        if ($this->next_due->equalTo($today)) {
            return 'hari_ini';
        }

        if ($this->next_due->lessThanOrEqualTo($today->copy()->addDays(3))) {
            return 'segera';
        }

        return 'mendatang';
    }

    /** Akun, ruang, dan kategorinya masih aktif, jadi bisa dibayar. */
    public function isUsable(): bool
    {
        return ! $this->account->archived && ! $this->room->archived && ! $this->category->archived;
    }
}
