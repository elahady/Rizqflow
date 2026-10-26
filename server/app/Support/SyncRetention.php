<?php

namespace App\Support;

use App\Models\SyncCompactionState;
use App\Models\SyncDevice;
use App\Models\SyncQuota;
use Carbon\CarbonInterface;
use Illuminate\Support\Facades\DB;

/**
 * Kompaksi fisik `sync_log_entries` dan pembersihan `sync_devices` (Tahap 7, sync-desain.md bagian G
 * "Kompaksi" dan bagian J #6). Dipanggil oleh `sync:compact` dan `sync:prune-stale-devices`.
 *
 * Aturan (tidak pernah membaca isi payload, hanya kolom luar):
 * - Versi KALAH: ada baris lain untuk rekaman yang sama (user, ruang, record_id) dengan urutan total
 *   lebih besar: (changed_at_ms, tombstone menang, device_id leksikografis terbesar). Dihapus bila
 *   umur barisnya (created_at, jam server) >= version_days. Pemenangnya tidak pernah disentuh di sini.
 * - TOMBSTONE: baris hapus dengan umur >= tombstone_days, TIDAK ada baris lain untuk rekaman itu
 *   (supaya tidak ada versi lama yang bangkit kembali), dan setiap perangkat aktif sudah melewatinya
 *   (after_id >= id). Untuk ruang bersama, perangkat semua anggota ruang itu ikut dihitung.
 *   Setelah dihapus, watermark naik ke id tombstone terbesar yang dihapus (lihat SyncCompactionState).
 *
 * Urutan: versi kalah dulu, baru tombstone (sehingga tombstone yang tinggal sendirian bisa lolos).
 * Setiap jalan idempoten: baris yang sudah dihapus tidak muncul lagi di kandidat. Kompaksi tidak
 * pernah menghapus data karena alasan lain (kuota, prune perangkat, dsb.).
 */
class SyncRetention
{
    /** Ukuran satu potongan transaksi, supaya kunci tidak ditahan lama. */
    private const CHUNK = 500;

    /**
     * @return array{versi: int, tombstone: int}
     */
    public function compact(): array
    {
        $now = now()->utc();
        $parameter = [
            'versiCutoff' => $this->format($now->copy()->subDays((int) config('sync.retention.version_days'))),
            'tombstoneCutoff' => $this->format($now->copy()->subDays((int) config('sync.retention.tombstone_days'))),
            'activeSince' => $this->format($this->perangkatAktifSejak($now)),
        ];

        [$versi] = $this->hapusBertahap('versi', $parameter);
        [$tombstone, $watermark] = $this->hapusBertahap('tombstone', $parameter);

        SyncCompactionState::catatJalan($versi, $tombstone, $watermark);

        return ['versi' => $versi, 'tombstone' => $tombstone];
    }

    /**
     * Hapus perangkat (cursor) yang tidak pernah pull dalam active_device_days terakhir. Tidak menyentuh
     * log. Aman karena perangkat seperti ini tetap dilayani: pull-nya dijawab 410 bila kursornya sudah
     * terlewati kompaksi, lalu ia pull ulang dari 0 (sync-desain.md bagian G).
     */
    public function pruneStaleDevices(): int
    {
        $cutoff = $this->format($this->perangkatAktifSejak(now()->utc()));

        return SyncDevice::where('last_seen_at', '<', $cutoff)->delete();
    }

    /** Perangkat aktif = pernah pull sejak batas ini (sync_devices.last_seen_at). */
    private function perangkatAktifSejak(CarbonInterface $now): CarbonInterface
    {
        return $now->copy()->subDays((int) config('sync.retention.active_device_days'));
    }

    /**
     * @param  array{versiCutoff: string, tombstoneCutoff: string, activeSince: string}  $parameter
     * @return array{0: int, 1: ?int} jumlah baris terhapus dan id tombstone terbesar yang terhapus
     */
    private function hapusBertahap(string $jenis, array $parameter): array
    {
        $terhapus = 0;
        $maksId = null;
        $setelah = 0;

        while (true) {
            $ids = $this->kandidat($jenis, $parameter, $setelah, null);
            if ($ids === []) {
                break;
            }
            $setelah = max($ids);

            // Diperiksa ulang di dalam transaksi yang sama dengan penghapusan, karena push bisa
            // menambah baris untuk rekaman yang sama sejak kandidat dipilih.
            $dihapus = DB::transaction(function () use ($jenis, $parameter, $ids) {
                $valid = $this->kandidat($jenis, $parameter, 0, $ids);
                if ($valid !== []) {
                    $this->hapusFisik($valid);
                }

                return $valid;
            });

            $terhapus += count($dihapus);
            if ($jenis === 'tombstone' && $dihapus !== []) {
                $maksId = max($maksId ?? 0, max($dihapus));
            }
        }

        return [$terhapus, $maksId];
    }

    /**
     * @param  array{versiCutoff: string, tombstoneCutoff: string, activeSince: string}  $parameter
     * @param  int[]|null  $ids  bila diisi, hanya memeriksa id ini (pemeriksaan ulang)
     * @return int[]
     */
    private function kandidat(string $jenis, array $parameter, int $setelah, ?array $ids): array
    {
        if ($ids !== null) {
            $filter = 'l.id IN ('.implode(',', array_fill(0, count($ids), '?')).')';
            $bindingFilter = $ids;
        } else {
            $filter = 'l.id > ?';
            $bindingFilter = [$setelah];
        }

        if ($jenis === 'versi') {
            $syarat = <<<'SQL'
                l.created_at <= ? AND l.changed_at_ms IS NOT NULL AND EXISTS (
                    SELECT 1 FROM sync_log_entries w
                    WHERE w.user_id = l.user_id
                      AND w.record_id = l.record_id
                      AND (w.room_id = l.room_id OR (w.room_id IS NULL AND l.room_id IS NULL))
                      AND w.changed_at_ms IS NOT NULL
                      AND (w.changed_at_ms > l.changed_at_ms
                           OR (w.changed_at_ms = l.changed_at_ms AND w.deleted > l.deleted)
                           OR (w.changed_at_ms = l.changed_at_ms AND w.deleted = l.deleted AND w.device_id > l.device_id))
                )
                SQL;
            $bindingSyarat = [$parameter['versiCutoff']];
        } else {
            $syarat = <<<'SQL'
                l.deleted = 1 AND l.created_at <= ? AND l.changed_at_ms IS NOT NULL
                AND NOT EXISTS (
                    SELECT 1 FROM sync_log_entries o
                    WHERE o.user_id = l.user_id
                      AND o.record_id = l.record_id
                      AND o.id <> l.id
                      AND (o.room_id = l.room_id OR (o.room_id IS NULL AND l.room_id IS NULL))
                )
                AND NOT EXISTS (
                    SELECT 1 FROM sync_devices d
                    WHERE d.last_seen_at >= ?
                      AND d.after_id < l.id
                      AND (d.user_id = l.user_id
                           OR (l.room_id IS NOT NULL AND d.user_id IN (
                                SELECT rm.user_id FROM room_members rm WHERE rm.room_id = l.room_id)))
                )
                SQL;
            $bindingSyarat = [$parameter['tombstoneCutoff'], $parameter['activeSince']];
        }

        $sql = 'SELECT l.id FROM sync_log_entries l WHERE '.$filter.' AND ('.$syarat.') ORDER BY l.id LIMIT '.self::CHUNK;
        $hasil = DB::select($sql, array_merge($bindingFilter, $bindingSyarat));

        return array_map(fn ($baris) => (int) $baris->id, $hasil);
    }

    /** Dipanggil di dalam transaksi. Mengurangi kuota pemiliknya dulu, baru menghapus barisnya. */
    private function hapusFisik(array $ids): void
    {
        $perPengguna = DB::table('sync_log_entries')
            ->whereIn('id', $ids)
            ->groupBy('user_id')
            ->selectRaw('user_id, COUNT(*) AS jumlah, COALESCE(SUM(LENGTH(payload)), 0) AS total_bytes')
            ->get();

        foreach ($perPengguna as $grup) {
            SyncQuota::kurangi((int) $grup->user_id, (int) $grup->jumlah, (int) $grup->total_bytes);
        }

        DB::table('sync_log_entries')->whereIn('id', $ids)->delete();
    }

    private function format(CarbonInterface $waktu): string
    {
        return $waktu->copy()->utc()->format('Y-m-d H:i:s');
    }
}
