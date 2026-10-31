<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;

/**
 * Lantai per rekaman (Tahap 9): `changed_at_ms` terbesar dari tombstone rekaman ini yang sudah dihapus
 * fisik oleh sync:compact. Push dengan `changed_at_ms` <= lantai ditolak di SyncController::push.
 *
 * Cakupan: data pribadi (scope '') per akun; ruang bersama (scope = room_id) berlaku untuk SEMUA anggota
 * ruang itu, karena setelah tombstone hilang penjaga "penulis pertama" tidak lagi menemukan baris.
 */
class SyncRecordFloor extends Model
{
    public const PRIBADI = '';

    protected $fillable = ['user_id', 'scope', 'record_id', 'floor_ms'];

    protected function casts(): array
    {
        return ['floor_ms' => 'integer'];
    }

    public static function scopeFor(?string $roomId): string
    {
        return $roomId ?? self::PRIBADI;
    }

    /** Lantai yang berlaku untuk push ini, atau null bila rekaman belum pernah dikompaksi. */
    public static function lantai(int $userId, ?string $roomId, string $recordId): ?int
    {
        $query = self::query()->where('scope', self::scopeFor($roomId))->where('record_id', $recordId);
        if ($roomId === null) {
            $query->where('user_id', $userId);
        }

        $max = $query->max('floor_ms');

        return $max === null ? null : (int) $max;
    }

    /** Naikkan lantai (tidak pernah turun). Dipanggil di dalam transaksi kompaksi. */
    public static function naikkan(int $userId, ?string $roomId, string $recordId, int $ms): void
    {
        $floor = self::query()->firstOrNew([
            'user_id' => $userId,
            'scope' => self::scopeFor($roomId),
            'record_id' => $recordId,
        ]);
        if (! $floor->exists || $ms > $floor->floor_ms) {
            $floor->floor_ms = $ms;
            $floor->save();
        }
    }
}
