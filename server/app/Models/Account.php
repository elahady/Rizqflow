<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Database\Eloquent\Relations\HasMany;

/**
 * Dompet/rekening (tunai, bank, e-wallet), versi web yang disederhanakan dari tabel `account`
 * Android (docs/model-data.md). Web belum punya saldo pembuka terpisah: `balance_minor` adalah saldo
 * sekarang, digerakkan setiap transaksi dicatat, diubah, atau dihapus lewat App\Support\Ledger
 * (beberapa halaman lama, mis. Tagihan, Zakat, Utang-piutang, masih menggerakkannya langsung).
 * Boleh negatif: aplikasi mencatat, tidak memblokir pengeluaran yang melebihi saldo.
 */
class Account extends Model
{
    use HasFactory;

    protected $fillable = [
        'user_id', 'name', 'type', 'balance_minor', 'archived', 'sort_order',
    ];

    protected $casts = [
        'archived' => 'boolean',
        'balance_minor' => 'integer',
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
