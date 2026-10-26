<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

/**
 * Kursor pull per perangkat (Tahap 2 sync-desain.md bagian G), ditulis `Api\SyncController::pull`
 * saat klien mengirim `device_id`. Dasar retensi/kedaluwarsa kursor Tahap 7 — belum dibaca di sini.
 */
class SyncDevice extends Model
{
    protected $fillable = [
        'user_id',
        'device_id',
        'after_id',
        'last_seen_at',
    ];

    protected function casts(): array
    {
        return [
            'last_seen_at' => 'datetime',
        ];
    }

    public function user(): BelongsTo
    {
        return $this->belongsTo(User::class);
    }
}
