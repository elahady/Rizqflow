<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Models\RoomMember;
use App\Models\SyncCompactionState;
use App\Models\SyncDevice;
use App\Models\SyncQuota;
use App\Models\SyncScopeActivity;
use Carbon\Carbon;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Collection;
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

        // Per cakupan (pribadi `a`, ruang bersama `r:<roomId>`): waktu push/pull dari tabel aktivitas.
        // Hanya cakupan yang masih terlihat akun ini (pribadi + ruang yang
        // masih diikuti); catatan ruang yang sudah ditinggalkan tidak ditampilkan.
        $ruangDiikuti = RoomMember::where('user_id', $userId)->pluck('room_id')->all();
        $terlihat = array_flip($ruangDiikuti);
        $aktivitas = SyncScopeActivity::where('user_id', $userId)->get()
            ->filter(fn (SyncScopeActivity $a) => $a->room_id === '' || isset($terlihat[$a->room_id]));

        $idPerangkat = $pushPerPerangkat->keys()
            ->merge($perangkat->keys())
            ->merge($aktivitas->pluck('device_id'))
            ->map(fn ($id) => (string) $id)
            ->unique()->sort()->values();

        $daftar = $idPerangkat->map(function (string $deviceId) use ($pushPerPerangkat, $perangkat, $aktivitas, $sejakAktif) {
            $push = $pushPerPerangkat->get($deviceId);
            $pull = $perangkat->get($deviceId);

            return [
                'device_id' => $deviceId,
                'rows' => (int) ($push->jumlah_baris ?? 0),
                'last_push_at' => self::iso($push?->push_terakhir),
                'last_pull_at' => self::iso($pull?->last_seen_at),
                'cursor' => $pull?->after_id,
                'active' => $pull !== null && $pull->last_seen_at !== null && $pull->last_seen_at->gte($sejakAktif),
                // Per cakupan untuk perangkat ini (hanya cakupan yang punya catatan).
                'scopes' => self::daftarCakupan($aktivitas->where('device_id', $deviceId), null),
            ];
        })->values();

        // Ringkasan per cakupan untuk seluruh akun (terbaru dari semua perangkat). Waktu push juga dilengkapi
        // dari log (MAX created_at) supaya akun yang sudah punya data sebelum pencatatan ini ada tetap terisi.
        $pushLogPerRuang = DB::table('sync_log_entries')
            ->where('user_id', $userId)
            ->groupBy('room_id')
            ->selectRaw('room_id, MAX(created_at) AS push_terakhir')
            ->get()
            ->mapWithKeys(fn ($baris) => [(string) ($baris->room_id ?? '') => $baris->push_terakhir]);
        $cakupanAkun = self::daftarCakupan($aktivitas, $ruangDiikuti, $pushLogPerRuang);

        $pemakaian = SyncQuota::pemakaianUntuk($userId);
        $kompaksi = SyncCompactionState::terakhir();

        return response()->json([
            'devices' => $daftar,
            // Waktu push/pull terakhir per cakupan untuk seluruh akun: `scope` = `a` atau `r:<roomId>`,
            // `room_id` null untuk pribadi. Selalu memuat cakupan pribadi dan semua ruang yang masih diikuti
            // (waktunya null bila belum pernah). Hanya metadata, tanpa isi.
            'scopes' => $cakupanAkun,
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

    /**
     * Susun daftar cakupan. `$ruangDiikuti` null = hanya cakupan yang punya catatan (per perangkat); diisi =
     * tingkat akun: cakupan pribadi dan semua ruang diikuti selalu ikut, dan waktu push dilengkapi dari log
     * (`$pushLog`: room_id atau '' => created_at).
     *
     * @param  Collection<int, SyncScopeActivity>  $aktivitas
     * @param  string[]|null  $ruangDiikuti
     * @param  Collection<string, mixed>|null  $pushLog
     * @return array<int, array<string, mixed>>
     */
    private static function daftarCakupan(Collection $aktivitas, ?array $ruangDiikuti, ?Collection $pushLog = null): array
    {
        $kunci = $aktivitas->pluck('room_id');
        if ($ruangDiikuti !== null) {
            $kunci = $kunci->merge([''])->merge($ruangDiikuti);
        }
        $kunci = $kunci->map(fn ($k) => (string) $k)->unique()->sort()->values(); // '' (pribadi) selalu pertama

        return $kunci->map(function (string $roomId) use ($aktivitas, $pushLog) {
            $catatan = $aktivitas->where('room_id', $roomId);
            $dariLog = $pushLog?->get($roomId);
            $push = collect([$catatan->max('last_push_at'), $dariLog === null ? null : Carbon::parse($dariLog)])->filter()->max();

            $baris = [
                'scope' => $roomId === '' ? 'a' : 'r:'.$roomId,
                'room_id' => $roomId === '' ? null : $roomId,
                'last_push_at' => self::iso($push),
                'last_pull_at' => self::iso($catatan->max('last_pull_at')),
            ];

            return $baris;
        })->all();
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
