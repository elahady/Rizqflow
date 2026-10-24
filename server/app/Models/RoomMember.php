<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

/** Satu baris keanggotaan ruang yang dibagi (Tahap 9). Lihat migrasi untuk konteks. */
class RoomMember extends Model
{
    public $timestamps = false;

    protected $fillable = [
        'user_id',
        'room_id',
    ];

    protected function casts(): array
    {
        return [
            'created_at' => 'datetime',
        ];
    }

    public function user(): BelongsTo
    {
        return $this->belongsTo(User::class);
    }
}
