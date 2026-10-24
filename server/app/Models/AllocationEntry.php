<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

/**
 * Potret alokasi satu pemasukan ke satu ruang (tabel `allocation_entry` di docs/model-data.md):
 * persentase saat dicatat (`share_bp`, basis point) dan nominal hasil AllocationEngine
 * (`amount_minor`). Hanya ditulis lewat App\Support\Ledger; ikut terhapus bersama pemasukannya.
 */
class AllocationEntry extends Model
{
    protected $fillable = ['transaction_id', 'room_id', 'share_bp', 'amount_minor'];

    protected $casts = [
        'share_bp' => 'integer',
        'amount_minor' => 'integer',
    ];

    public function transaction(): BelongsTo
    {
        return $this->belongsTo(Transaction::class);
    }

    public function room(): BelongsTo
    {
        return $this->belongsTo(Room::class);
    }
}
