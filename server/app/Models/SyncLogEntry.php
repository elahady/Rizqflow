<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

/**
 * Satu baris log sinkronisasi append-only (Tahap 9, server-sync.md "Model konflik sinkronisasi").
 * Baris di sini tidak pernah diubah atau dihapus — lihat `Api\SyncController`.
 */
class SyncLogEntry extends Model
{
    /** Tidak ada updated_at: baris tidak pernah diubah. created_at diisi DB (useCurrent), bukan Eloquent. */
    public $timestamps = false;

    protected $fillable = [
        'user_id',
        'room_id',
        'record_type',
        'record_id',
        'device_id',
        'payload',
        'deleted',
        'changed_at',
    ];

    protected function casts(): array
    {
        return [
            'deleted' => 'boolean',
            'changed_at' => 'datetime',
            'created_at' => 'datetime',
        ];
    }

    public function user(): BelongsTo
    {
        return $this->belongsTo(User::class);
    }
}
