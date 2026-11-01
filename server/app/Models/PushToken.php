<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Support\Facades\DB;

/**
 * Token FCM satu perangkat milik satu akun (Tahap 9/13). Dikelola lewat PushTokenController; dipakai
 * RoomActivityNotifier. `token` tidak pernah ikut serialisasi atau log.
 */
class PushToken extends Model
{
    /** Batas baris per akun (sama dengan plafon token API per perangkat). */
    public const MAX_PER_USER = 20;

    protected $fillable = ['user_id', 'device_id', 'token', 'token_hash'];

    protected $hidden = ['token', 'token_hash'];

    public function user(): BelongsTo
    {
        return $this->belongsTo(User::class);
    }

    public static function hashOf(string $token): string
    {
        return hash('sha256', $token);
    }

    /**
     * Daftarkan atau perbarui token untuk (akun, perangkat). Token yang sama di baris lain (akun atau
     * perangkat lain) dibuang lebih dulu: token FCM milik satu pemasangan aplikasi, yang kini dipakai
     * akun ini. Kelebihan di atas MAX_PER_USER membuang yang paling lama tidak diperbarui.
     */
    public static function daftarkan(int $userId, string $deviceId, string $token): self
    {
        return DB::transaction(function () use ($userId, $deviceId, $token) {
            $hash = self::hashOf($token);

            self::query()
                ->where('token_hash', $hash)
                ->where(fn ($q) => $q->where('user_id', '!=', $userId)->orWhere('device_id', '!=', $deviceId))
                ->delete();

            $baris = self::query()->updateOrCreate(
                ['user_id' => $userId, 'device_id' => $deviceId],
                ['token' => $token, 'token_hash' => $hash],
            );
            // updated_at selalu disegarkan walau token sama: tanda perangkat masih memakai token ini.
            $baris->touch();

            $kelebihan = self::query()->where('user_id', $userId)
                ->orderByDesc('updated_at')->orderByDesc('id')
                ->skip(self::MAX_PER_USER)->take(PHP_INT_MAX)
                ->pluck('id');
            if ($kelebihan->isNotEmpty()) {
                self::query()->whereIn('id', $kelebihan)->delete();
            }

            return $baris;
        });
    }
}
