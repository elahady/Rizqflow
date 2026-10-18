<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

/**
 * Favorit untuk catat kilat (S24): nominal, kategori, dan akun tersimpan sebagai potret, satu
 * ketukan menyimpan pengeluaran. Versi web disederhanakan dari tabel `quick_favorite` Android
 * (docs/model-data.md): tanpa `room_id` (diturunkan dari kategori) dan tanpa `use_count`/
 * `last_used_at` (urutan tampil mengikuti dibuat, bukan sering dipakai).
 */
class QuickEntry extends Model
{
    use HasFactory;

    protected $fillable = [
        'user_id', 'name', 'amount_minor', 'category_id', 'account_id',
    ];

    public function user(): BelongsTo
    {
        return $this->belongsTo(User::class);
    }

    public function category(): BelongsTo
    {
        return $this->belongsTo(Category::class);
    }

    public function account(): BelongsTo
    {
        return $this->belongsTo(Account::class);
    }
}
