<?php

namespace App\Models;

use App\Support\SyncQuotaExceeded;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Support\Facades\DB;

/**
 * Penghitung pemakaian kuota sinkron per akun (Tahap 7, sync-desain.md bagian J #12): jumlah baris
 * `sync_log_entries` yang ditulis akun itu dan total byte payload-nya. Batas ada di config/sync.php.
 *
 * Diisi malas: akun yang belum punya baris di sini dihitung sekali dari `sync_log_entries` saat push
 * pertama, lalu dijaga oleh push (tambah) dan `sync:compact` (kurang). Semua perubahan dilakukan di
 * dalam transaksi yang sama dengan baris log, dan baris penghitung dikunci `FOR UPDATE` supaya push
 * serentak satu akun tidak melewati batas bersama-sama.
 */
class SyncQuota extends Model
{
    protected $table = 'sync_quotas';

    protected $primaryKey = 'user_id';

    public $incrementing = false;

    protected $fillable = [
        'user_id',
        'row_count',
        'payload_bytes',
    ];

    protected function casts(): array
    {
        return [
            'row_count' => 'integer',
            'payload_bytes' => 'integer',
        ];
    }

    /**
     * Pemakaian akun saat ini tanpa menulis apa pun (untuk jawaban Riwayat sinkron). Bila baris
     * penghitung belum ada, dihitung langsung dari log.
     *
     * @return array{rows: int, bytes: int}
     */
    public static function pemakaianUntuk(int $userId): array
    {
        $row = self::find($userId);
        if ($row !== null) {
            return ['rows' => $row->row_count, 'bytes' => $row->payload_bytes];
        }

        return self::hitungDariLog($userId);
    }

    /**
     * Dipanggil di awal transaksi push: pastikan baris penghitung ada, lalu kunci untuk transaksi ini.
     */
    public static function kunciUntukTulis(int $userId): self
    {
        if (! self::whereKey($userId)->exists()) {
            $hitung = self::hitungDariLog($userId);
            self::query()->insertOrIgnore([
                'user_id' => $userId,
                'row_count' => $hitung['rows'],
                'payload_bytes' => $hitung['bytes'],
                'created_at' => now(),
                'updated_at' => now(),
            ]);
        }

        return self::whereKey($userId)->lockForUpdate()->firstOrFail();
    }

    /**
     * Tambah pemakaian baru akibat push. Melempar SyncQuotaExceeded bila melewati batas, sehingga
     * transaksi push di-rollback dan tidak ada baris yang tersimpan. Push yang tidak menambah baris
     * baru (semua duplikat) selalu lolos, walau kuota sudah penuh.
     */
    public function tambahPemakaian(int $rows, int $bytes): void
    {
        if ($rows === 0 && $bytes === 0) {
            return;
        }

        $maxRows = (int) config('sync.quota.max_rows');
        $maxBytes = (int) config('sync.quota.max_bytes');
        if ($this->row_count + $rows > $maxRows || $this->payload_bytes + $bytes > $maxBytes) {
            throw new SyncQuotaExceeded;
        }

        $this->row_count += $rows;
        $this->payload_bytes += $bytes;
        $this->save();
    }

    /**
     * Kurangi pemakaian akibat kompaksi fisik. Dipanggil di dalam transaksi kompaksi. Bila baris
     * penghitung belum ada, tidak perlu dikurangi: nilai awalnya nanti dihitung ulang dari log.
     */
    public static function kurangi(int $userId, int $rows, int $bytes): void
    {
        $quota = self::whereKey($userId)->lockForUpdate()->first();
        if ($quota === null) {
            return;
        }

        $quota->row_count = max(0, $quota->row_count - $rows);
        $quota->payload_bytes = max(0, $quota->payload_bytes - $bytes);
        $quota->save();
    }

    /** @return array{rows: int, bytes: int} */
    private static function hitungDariLog(int $userId): array
    {
        $hitung = DB::table('sync_log_entries')
            ->where('user_id', $userId)
            ->selectRaw('COUNT(*) AS jumlah, COALESCE(SUM(LENGTH(payload)), 0) AS total_bytes')
            ->first();

        return ['rows' => (int) ($hitung->jumlah ?? 0), 'bytes' => (int) ($hitung->total_bytes ?? 0)];
    }
}
