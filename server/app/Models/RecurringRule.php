<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Database\Eloquent\Relations\HasMany;

/**
 * Aturan transaksi berulang (S33): lihat RecurringRule di :domain untuk acuan lengkap. Web hanya
 * menangani pemasukan dan pengeluaran (tanpa transfer, sama seperti TransaksiDetail).
 */
class RecurringRule extends Model
{
    use HasFactory;

    protected $fillable = [
        'user_id', 'type', 'amount_minor', 'account_id', 'room_id', 'category_id',
        'source', 'note', 'frequency', 'start_date', 'next_due', 'end_date', 'active',
    ];

    protected $casts = [
        'start_date' => 'date',
        'next_due' => 'date',
        'end_date' => 'date',
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

    public function isFinished(): bool
    {
        return $this->end_date !== null && $this->next_due->greaterThan($this->end_date);
    }

    /** Akun, ruang, dan kategorinya masih aktif, jadi kemunculan berikutnya bisa dicatat. */
    public function isUsable(): bool
    {
        if ($this->account->archived) {
            return false;
        }

        if ($this->type === 'pengeluaran') {
            return $this->room && ! $this->room->archived && $this->category && ! $this->category->archived;
        }

        return true;
    }
}
