<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Database\Eloquent\Relations\HasMany;
use Illuminate\Support\Carbon;

/**
 * Utang-piutang (S35): lihat Debt di :domain untuk acuan lengkap. Perpindahan uangnya digerakkan
 * langsung ke Account.balance_minor oleh App\Livewire\UtangPiutang, bukan lewat tabel transactions
 * (lihat catatan di migrasinya).
 */
class Debt extends Model
{
    use HasFactory;

    public const PARTY_MAX = 40;

    protected $fillable = [
        'user_id', 'direction', 'party', 'principal_minor', 'account_id',
        'start_date', 'due_date', 'note', 'collectible',
    ];

    protected $casts = [
        'start_date' => 'date',
        'due_date' => 'date',
        'collectible' => 'boolean',
    ];

    public function user(): BelongsTo
    {
        return $this->belongsTo(User::class);
    }

    public function account(): BelongsTo
    {
        return $this->belongsTo(Account::class);
    }

    public function payments(): HasMany
    {
        return $this->hasMany(DebtPayment::class)->orderByDesc('paid_on')->orderByDesc('id');
    }

    public function repaidMinor(): int
    {
        return (int) $this->payments()->sum('amount_minor');
    }

    public function outstandingMinor(): int
    {
        return max($this->principal_minor - $this->repaidMinor(), 0);
    }

    public function isSettled(): bool
    {
        return $this->outstandingMinor() <= 0;
    }

    public function statusOn(Carbon $today): string
    {
        if ($this->isSettled()) {
            return 'lunas';
        }

        if ($this->due_date === null) {
            return 'berjalan';
        }

        if ($this->due_date->lessThan($today)) {
            return 'terlambat';
        }

        if ($this->due_date->equalTo($today)) {
            return 'hari_ini';
        }

        if ($this->due_date->lessThanOrEqualTo($today->copy()->addDays(3))) {
            return 'segera';
        }

        return 'berjalan';
    }
}
