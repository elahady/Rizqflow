<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Database\Eloquent\Relations\HasMany;

/**
 * Dompet/rekening (tunai, bank, e-wallet), versi web yang disederhanakan dari tabel `account`
 * Android (docs/model-data.md): `balance_minor` di sini statis sejak dibuat, belum otomatis
 * bergerak mengikuti transaksi seperti saldo berjalan di Android (menyusul saat Edit transaksi
 * sungguhan dan Koreksi saldo dikerjakan di web).
 */
class Account extends Model
{
    use HasFactory;

    protected $fillable = [
        'user_id', 'name', 'type', 'balance_minor', 'archived', 'sort_order',
    ];

    protected $casts = [
        'archived' => 'boolean',
    ];

    public function user(): BelongsTo
    {
        return $this->belongsTo(User::class);
    }

    public function transactions(): HasMany
    {
        return $this->hasMany(Transaction::class);
    }

    public function quickEntries(): HasMany
    {
        return $this->hasMany(QuickEntry::class);
    }

    public function scopeActive($query)
    {
        return $query->where('archived', false);
    }
}
