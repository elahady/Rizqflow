<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Models\SyncCompactionState;
use App\Models\SyncDevice;
use App\Models\SyncQuota;
use Carbon\Carbon;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;

/**
 * Riwayat sinkron (Tahap 7, sync-desain.md bagian I): ringkasan read-only untuk akun yang sedang masuk.
 * Hanya METADATA: jumlah baris per perangkat, waktu push dan pull terakhir per perangkat, pemakaian
 * kuota, dan kapan kompaksi terakhir jalan. Tidak ada payload, tidak ada isi transaksi atau data
 * keuangan, sama seperti daftar perangkat di AccountController::devices.
 *
 * Tidak dijaga denyWithoutSync: pengguna yang langganannya berakhir tetap bisa melihat keadaan
 * sinkronnya sendiri (sama seperti pemulihan kunci di RecoveryKeyController).
 */
class SyncHistoryController extends Controller
{
    public function show(Request $request): JsonResponse
    {
        $userId = $request->user()->id;
        $sejakAktif = now()->utc()->subDays((int) config('sync.retention.active_device_days'));

        // Baris per perangkat dan waktu push terakhir, dari log milik akun ini sendiri.
        $pushPerPerangkat = DB::table('sync_log_entries')
            ->where('user_id', $userId)
            ->groupBy('device_id')
            ->selectRaw('device_id, COUNT(*) AS jumlah_baris, MAX(created_at) AS push_terakhir')
            ->get()
            ->keyBy('device_id');

        // Waktu pull terakhir dan kursor, dari kursor perangkat yang pernah pull dengan device_id.
        $perangkat = SyncDevice::where('user_id', $userId)->get()->keyBy('device_id');

        $idPerangkat = $pushPerPerangkat->keys()->merge($perangkat->keys())->unique()->sort()->values();

        $daftar = $idPerangkat->map(function (string $deviceId) use ($pushPerPerangkat, $perangkat, $sejakAktif) {
            $push = $pushPerPerangkat->get($deviceId);
            $pull = $perangkat->get($deviceId);

            return [
                'device_id' => $deviceId,
                'rows' => (int) ($push->jumlah_baris ?? 0),
                'last_push_at' => self::iso($push?->push_terakhir),
                'last_pull_at' => self::iso($pull?->last_seen_at),
                'cursor' => $pull?->after_id,
                'active' => $pull !== null && $pull->last_seen_at !== null && $pull->last_seen_at->gte($sejakAktif),
            ];
        })->values();

        $pemakaian = SyncQuota::pemakaianUntuk($userId);
        $kompaksi = SyncCompactionState::terakhir();

        return response()->json([
            'devices' => $daftar,
            'quota' => [
                'rows' => $pemakaian['rows'],
                'bytes' => $pemakaian['bytes'],
                'max_rows' => (int) config('sync.quota.max_rows'),
                'max_bytes' => (int) config('sync.quota.max_bytes'),
            ],
            'compaction' => [
                'last_run_at' => self::iso($kompaksi?->last_run_at),
                'last_removed_versions' => $kompaksi?->last_removed_versions ?? 0,
                'last_removed_tombstones' => $kompaksi?->last_removed_tombstones ?? 0,
            ],
            'server_time_ms' => now()->getTimestampMs(),
        ]);
    }

    /** Waktu ke ISO-8601 UTC detik penuh, sama dengan format di AccountController. */
    private static function iso(mixed $at): ?string
    {
        if ($at === null) {
            return null;
        }

        return Carbon::parse($at)->utc()->format('Y-m-d\TH:i:s\Z');
    }
}
