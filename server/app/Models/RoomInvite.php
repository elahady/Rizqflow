<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;

/** Satu undangan terdaftar untuk ruang yang dibagi (key_grant langkah 1). Lihat migrasi untuk konteks. */
class RoomInvite extends Model
{
    public $timestamps = false;

    /** Umur undangan sejak diterbitkan (keputusan pemilik 2026-11-03). */
    public const LIFETIME_DAYS = 7;

    /** Batas undangan yang menunggu (belum dipakai, belum kedaluwarsa) per ruang: pagar terhadap penerbitan berlebihan. */
    public const MAX_PENDING = 20;

    protected $fillable = [
        'room_id',
        'invite_hash',
        'issued_by',
        'used_by_user_id',
        'expires_at',
    ];

    protected function casts(): array
    {
        return [
            'expires_at' => 'datetime',
            'created_at' => 'datetime',
        ];
    }

    public function isUsed(): bool
    {
        return $this->used_by_user_id !== null;
    }

    public function isExpired(): bool
    {
        return $this->expires_at->isPast();
    }
}
