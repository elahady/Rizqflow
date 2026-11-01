<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Support\Facades\DB;

/**
 * Kursor pull per (akun, perangkat, cakupan) untuk pull dengan `scope` (sync-desain.md bagian G).
 * `room_id` string kosong = cakupan pribadi (`a`), selain itu id ruang bersama (`r:<roomId>`).
 *
 * Berdampingan dengan `sync_devices` (kursor global untuk pull TANPA `scope`, klien lama), tidak
 * menggantikannya: pull tanpa `scope` tidak menyentuh tabel ini dan sebaliknya. Dua-duanya dibaca
 * `SyncRetention` untuk memutuskan apakah sebuah tombstone sudah dilewati semua perangkat aktif.
 * Kursor ditimpa apa adanya (bisa turun bila klien sinkron ulang dari 0): itu keadaan perangkat
 * sebenarnya, dan kursor yang turun justru harus kembali menahan kompaksi.
 */
class SyncDeviceCursor extends Model
{
    protected $fillable = [
        'user_id',
        'device_id',
        'room_id',
        'after_id',
        'last_seen_at',
    ];

    protected function casts(): array
    {
        return [
            'after_id' => 'integer',
            'last_seen_at' => 'datetime',
        ];
    }

    public function user(): BelongsTo
    {
        return $this->belongsTo(User::class);
    }

    /** Catat (atau timpa) kursor perangkat untuk satu cakupan. Atomik: satu `INSERT ... ON CONFLICT/DUPLICATE KEY`. */
    public static function catat(int $userId, string $deviceId, ?string $roomId, int $afterId): void
    {
        $sekarang = now();

        DB::table('sync_device_cursors')->upsert(
            [[
                'user_id' => $userId,
                'device_id' => $deviceId,
                'room_id' => $roomId ?? '',
                'after_id' => $afterId,
                'last_seen_at' => $sekarang,
                'created_at' => $sekarang,
                'updated_at' => $sekarang,
            ]],
            ['user_id', 'device_id', 'room_id'],
            ['after_id', 'last_seen_at', 'updated_at'],
        );
    }
}
