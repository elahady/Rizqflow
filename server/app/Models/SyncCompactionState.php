<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;

/**
 * Status kompaksi sinkron untuk seluruh server (Tahap 7, sync-desain.md bagian J #6), satu baris dengan id 1.
 *
 * - `tombstone_watermark_id`: id tombstone terbesar yang sudah dihapus fisik. Pull dengan kursor
 *   `after` di bawah nilai ini dijawab 410 cursor_expired (klien pull ulang dari 0).
 * - `last_run_at` dan jumlah yang dihapus pada jalan terakhir: bahan "Riwayat sinkron".
 */
class SyncCompactionState extends Model
{
    public const ID = 1;

    public $timestamps = false;

    public $incrementing = false;

    protected $table = 'sync_compaction_state';

    protected $fillable = [
        'id',
        'tombstone_watermark_id',
        'last_removed_versions',
        'last_removed_tombstones',
        'last_run_at',
    ];

    protected function casts(): array
    {
        return [
            'tombstone_watermark_id' => 'integer',
            'last_removed_versions' => 'integer',
            'last_removed_tombstones' => 'integer',
            'last_run_at' => 'datetime',
        ];
    }

    /** Tanpa menulis: 0 bila kompaksi belum pernah jalan. */
    public static function tombstoneWatermark(): int
    {
        return (int) (self::query()->whereKey(self::ID)->value('tombstone_watermark_id') ?? 0);
    }

    /** Baris status, atau null bila kompaksi belum pernah jalan. */
    public static function terakhir(): ?self
    {
        return self::query()->whereKey(self::ID)->first();
    }

    /** Catat hasil satu jalan kompaksi. Watermark hanya boleh naik. */
    public static function catatJalan(int $hapusVersi, int $hapusTombstone, ?int $watermarkBaru): void
    {
        $state = self::query()->firstOrCreate(['id' => self::ID], ['tombstone_watermark_id' => 0]);
        $state->last_removed_versions = $hapusVersi;
        $state->last_removed_tombstones = $hapusTombstone;
        $state->last_run_at = now();
        if ($watermarkBaru !== null && $watermarkBaru > $state->tombstone_watermark_id) {
            $state->tombstone_watermark_id = $watermarkBaru;
        }
        $state->save();
    }
}
