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
        'is_owner',
    ];

    protected function casts(): array
    {
        return [
            'is_owner' => 'boolean',
            'created_at' => 'datetime',
        ];
    }

    public function user(): BelongsTo
    {
        return $this->belongsTo(User::class);
    }

    /**
     * Satu-satunya jalur menambah anggota (join QR dan push sync). Anggota pertama yang tercatat untuk
     * sebuah room_id jadi pemilik (keputusan 2026-10-27); anggota berikutnya hanya pencatat. Idempoten:
     * memanggil lagi untuk pasangan user/ruang yang sama tidak mengubah baris yang ada.
     */
    public static function tambahAnggota(int $userId, string $roomId): void
    {
        self::firstOrCreate(
            ['user_id' => $userId, 'room_id' => $roomId],
            ['is_owner' => ! self::where('room_id', $roomId)->exists()],
        );
    }
}
