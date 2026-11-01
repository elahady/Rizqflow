<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Support\Facades\DB;

/**
 * Watermark kompaksi per cakupan: id tombstone terbesar yang sudah dihapus fisik DI cakupan itu
 * (pribadi milik satu akun, atau satu ruang bersama). Pull per cakupan dengan kursor di bawah nilai
 * ini dijawab 410 `cursor_expired`; cakupan lain tidak terpengaruh.
 *
 * Berdampingan dengan watermark global `SyncCompactionState` (dipakai pull TANPA `scope`, klien lama,
 * dan tetap benar karena global >= watermark tiap cakupan). Yang global terlalu kasar untuk pull per
 * cakupan: cakupan sepi yang kursornya sudah lama akan dipaksa tarik ulang penuh setiap ada tombstone
 * di cakupan LAIN yang dikompaksi.
 */
class SyncScopeWatermark extends Model
{
    protected $fillable = ['scope_key', 'user_id', 'watermark_id'];

    protected function casts(): array
    {
        return ['watermark_id' => 'integer'];
    }

    /** Kunci cakupan: `a|<userId>` untuk pribadi, `r|<roomId>` untuk ruang bersama (semua anggota berbagi satu). */
    public static function kunci(int $userId, ?string $roomId): string
    {
        return $roomId === null ? "a|{$userId}" : "r|{$roomId}";
    }

    /** 0 bila belum ada tombstone yang dikompaksi di cakupan itu. */
    public static function untuk(int $userId, ?string $roomId): int
    {
        return (int) (self::query()->where('scope_key', self::kunci($userId, $roomId))->value('watermark_id') ?? 0);
    }

    /**
     * Naikkan watermark (tidak pernah turun). Dipanggil di dalam transaksi kompaksi, sebelum tombstone
     * dihapus fisik.
     */
    public static function naikkan(int $userId, ?string $roomId, int $id): void
    {
        $sekarang = now();

        // Dua pernyataan, bukan upsert: kolom yang ditimpa harus MAX(lama, baru) dan itu tidak portabel
        // antara MySQL (GREATEST) dan SQLite (MAX berargumen ganda). Aman karena hanya sync:compact yang
        // menulis, dengan `withoutOverlapping`, dan kondisi `<` di UPDATE menjaga agar tidak pernah turun.
        $dipakai = DB::table('sync_scope_watermarks')
            ->where('scope_key', self::kunci($userId, $roomId))
            ->where('watermark_id', '<', $id)
            ->update(['watermark_id' => $id, 'updated_at' => $sekarang]);

        if ($dipakai === 0) {
            DB::table('sync_scope_watermarks')->insertOrIgnore([
                'scope_key' => self::kunci($userId, $roomId),
                'user_id' => $roomId === null ? $userId : null,
                'watermark_id' => $id,
                'created_at' => $sekarang,
                'updated_at' => $sekarang,
            ]);
        }
    }
}
