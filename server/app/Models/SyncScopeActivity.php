<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Support\Facades\DB;

/**
 * Kapan sebuah perangkat terakhir push dan pull per cakupan (pribadi `a`, ruang bersama `r:<roomId>`),
 * bahan "Riwayat sinkron" (`GET /api/sync/history`, bidang `scopes`). `room_id` string kosong = pribadi.
 *
 * HANYA metadata yang sudah terlihat server: akun, id perangkat, id ruang, dan dua waktu. Tidak ada
 * isi, jumlah baris, atau id rekaman. Pull TANPA `scope` (klien lama) mencatat semua cakupan yang
 * dicakupnya (pribadi plus ruang yang diikuti), jadi riwayat per cakupan terisi tanpa perubahan klien.
 */
class SyncScopeActivity extends Model
{
    protected $table = 'sync_scope_activity';

    protected $fillable = [
        'user_id',
        'device_id',
        'room_id',
        'last_push_at',
        'last_pull_at',
    ];

    protected function casts(): array
    {
        return [
            'last_push_at' => 'datetime',
            'last_pull_at' => 'datetime',
        ];
    }

    public function user(): BelongsTo
    {
        return $this->belongsTo(User::class);
    }

    /**
     * @param  array<int, ?string>  $roomIds  satu entri per cakupan (null = pribadi); duplikat diabaikan
     */
    public static function catatPush(int $userId, string $deviceId, array $roomIds): void
    {
        self::catat($userId, $deviceId, $roomIds, 'last_push_at');
    }

    /**
     * @param  array<int, ?string>  $roomIds  satu entri per cakupan (null = pribadi); duplikat diabaikan
     */
    public static function catatPull(int $userId, string $deviceId, array $roomIds): void
    {
        self::catat($userId, $deviceId, $roomIds, 'last_pull_at');
    }

    private static function catat(int $userId, string $deviceId, array $roomIds, string $kolom): void
    {
        $sekarang = now();
        $baris = [];
        foreach ($roomIds as $roomId) {
            $kolomRuang = $roomId ?? '';
            // Satu baris per cakupan: upsert dengan kunci kembar dalam satu pernyataan ditolak SQLite.
            $baris[$kolomRuang] = [
                'user_id' => $userId,
                'device_id' => $deviceId,
                'room_id' => $kolomRuang,
                'last_push_at' => $kolom === 'last_push_at' ? $sekarang : null,
                'last_pull_at' => $kolom === 'last_pull_at' ? $sekarang : null,
                'created_at' => $sekarang,
                'updated_at' => $sekarang,
            ];
        }
        if ($baris === []) {
            return;
        }

        // Hanya kolom yang bersangkutan yang ditimpa saat baris sudah ada (push tidak menghapus waktu pull).
        DB::table('sync_scope_activity')->upsert(
            array_values($baris),
            ['user_id', 'device_id', 'room_id'],
            [$kolom, 'updated_at'],
        );
    }
}
