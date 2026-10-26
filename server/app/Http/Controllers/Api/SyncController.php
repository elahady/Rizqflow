<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Api\Concerns\DeniesWithoutSync;
use App\Http\Controllers\Controller;
use App\Models\RoomMember;
use App\Models\SyncDevice;
use App\Models\SyncLogEntry;
use Carbon\Carbon;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Log;

/**
 * Sinkronisasi antar-perangkat terenkripsi (Tahap 9, server-sync.md "Model konflik sinkronisasi"):
 * log append-only, server tidak pernah membaca `payload` (ciphertext buram dari aplikasi). [push]
 * menerima perubahan dari satu perangkat; [pull] mengirim ulang perubahan dari perangkat lain
 * sejak kursor terakhir. Resolusi "siapa menang" (last-write-wins dari `changed_at`) dikerjakan di
 * aplikasi setelah mendekripsi, bukan di sini — server hanya menyimpan dan meneruskan apa adanya.
 * Pull dari kursor 0 di perangkat baru sekaligus berfungsi sebagai restore dari backup.
 *
 * [pull] juga mengirim baris ruang yang dibagi (lihat RoomMember/RoomMembershipController), tidak
 * hanya baris milik akun sendiri — baris tanpa room_id (mis. Account) tetap selalu privat per akun.
 */
class SyncController extends Controller
{
    use DeniesWithoutSync;

    private const MAX_BATCH = 500;

    private const MAX_PAYLOAD_LENGTH = 262144; // ~256 KB per baris (base64), cukup longgar untuk satu rekaman

    private const DEFAULT_PULL_LIMIT = 200;

    private const MAX_PULL_LIMIT = 500;

    public function push(Request $request): JsonResponse
    {
        if ($denied = $this->denyWithoutSync($request)) {
            return $denied;
        }

        $data = $request->validate([
            'entries' => ['required', 'array', 'min:1', 'max:'.self::MAX_BATCH],
            'entries.*.room_id' => ['nullable', 'string', 'max:100'],
            'entries.*.record_type' => ['required', 'string', 'max:60'],
            'entries.*.record_id' => ['required', 'string', 'max:100'],
            'entries.*.device_id' => ['required', 'string', 'max:100'],
            'entries.*.payload' => ['required', 'string', 'max:'.self::MAX_PAYLOAD_LENGTH],
            'entries.*.deleted' => ['boolean'],
            'entries.*.changed_at' => ['required', 'date'],
            // Tahap 2 sync-desain.md bagian G: presisi milidetik tambahan; nullable untuk klien lama
            // (dihitung dari changed_at bila tidak dikirim).
            'entries.*.changed_at_ms' => ['nullable', 'integer', 'min:0'],
        ]);

        $userId = $request->user()->id;

        // Satu transaksi DB per batch (Tahap 2 bagian G): gagal di tengah tidak meninggalkan separuh baris.
        $ids = DB::transaction(function () use ($data, $userId) {
            $ids = [];

            // Siapa pun yang push ke sebuah room_id otomatis jadi anggotanya (idempoten) — bootstrap
            // keanggotaan tanpa butuh endpoint "buat ruang" terpisah, lihat RoomMembershipController.
            $roomIds = array_unique(array_filter(array_column($data['entries'], 'room_id')));
            foreach ($roomIds as $roomId) {
                RoomMember::firstOrCreate(['user_id' => $userId, 'room_id' => $roomId]);
            }

            foreach ($data['entries'] as $entry) {
                $roomId = $entry['room_id'] ?? null;
                $changedAtMs = $entry['changed_at_ms'] ?? Carbon::parse($entry['changed_at'])->getTimestampMs();

                // Penulis tunggal (Tahap 2 bagian B R3): hanya penulis PERTAMA baris ini (dalam ruang
                // yang sama) boleh mengubah atau menghapusnya — anggota lain memegang kunci ruang yang
                // sama jadi `device_id`/isi payload bisa dipalsukan; id baris tidak bisa.
                if ($roomId !== null) {
                    $firstAuthor = SyncLogEntry::where('room_id', $roomId)
                        ->where('record_id', $entry['record_id'])
                        ->orderBy('id')
                        ->value('user_id');
                    if ($firstAuthor !== null && $firstAuthor !== $userId) {
                        Log::info('sync push: baris ditolak, bukan penulis pertama', [
                            'room_id' => $roomId,
                            'record_id' => $entry['record_id'],
                            'first_author' => $firstAuthor,
                            'rejected_user' => $userId,
                        ]);

                        continue;
                    }
                }

                // Idempoten: push yang diulang (ack hilang di klien) menemukan baris yang sama dan
                // mengembalikan id-nya, tidak menggandakan baris.
                $existing = SyncLogEntry::where('user_id', $userId)
                    ->where('device_id', $entry['device_id'])
                    ->where('record_id', $entry['record_id'])
                    ->where('changed_at_ms', $changedAtMs)
                    ->where('deleted', $entry['deleted'] ?? false)
                    ->first();
                if ($existing !== null) {
                    $ids[] = $existing->id;

                    continue;
                }

                $ids[] = SyncLogEntry::create([
                    'user_id' => $userId,
                    'room_id' => $roomId,
                    'record_type' => $entry['record_type'],
                    'record_id' => $entry['record_id'],
                    'device_id' => $entry['device_id'],
                    'payload' => $entry['payload'],
                    'deleted' => $entry['deleted'] ?? false,
                    'changed_at' => $entry['changed_at'],
                    'changed_at_ms' => $changedAtMs,
                ])->id;
            }

            return $ids;
        });

        // Semua baris ditolak (bukan penulis pertama): cursor tetap baris terakhir milik user ini,
        // bukan 0 — klien lama membaca ini apa adanya (lihat catatan SyncCoordinator: tidak dipakai
        // untuk apa pun selain ditampilkan).
        $cursor = $ids === [] ? (SyncLogEntry::where('user_id', $userId)->max('id') ?? 0) : max($ids);

        return response()->json([
            'ids' => $ids,
            'cursor' => $cursor,
            'server_time_ms' => now()->getTimestampMs(),
        ]);
    }

    public function pull(Request $request): JsonResponse
    {
        if ($denied = $this->denyWithoutSync($request)) {
            return $denied;
        }

        $validated = $request->validate([
            'after' => ['integer', 'min:0'],
            'limit' => ['integer', 'min:1', 'max:'.self::MAX_PULL_LIMIT],
            // Tahap 2 sync-desain.md bagian G: opsional, dasar kursor per perangkat (`sync_devices`,
            // Tahap 7). Klien lama tanpa device_id tetap bisa pull, hanya tidak tercatat di sana.
            'device_id' => ['nullable', 'string', 'max:100'],
        ]);
        // Laravel tidak mengubah tipe nilai yang sudah lolos validasi 'integer'; query string selalu
        // string, dan `$entries->count() === $limit` di bawah butuh int, bukan '2' !== 2.
        $after = (int) ($validated['after'] ?? 0);
        $limit = (int) ($validated['limit'] ?? self::DEFAULT_PULL_LIMIT);
        $deviceId = $validated['device_id'] ?? null;

        $userId = $request->user()->id;
        $sharedRoomIds = RoomMember::where('user_id', $userId)->pluck('room_id');

        $entries = SyncLogEntry::where(function ($query) use ($userId, $sharedRoomIds) {
            $query->where('user_id', $userId)
                ->orWhere(function ($query) use ($sharedRoomIds) {
                    $query->whereNotNull('room_id')->whereIn('room_id', $sharedRoomIds);
                });
        })
            ->where('id', '>', $after)
            ->orderBy('id')
            ->limit($limit)
            ->get(['id', 'room_id', 'record_type', 'record_id', 'device_id', 'payload', 'deleted', 'changed_at', 'changed_at_ms', 'user_id']);

        $nextAfter = $entries->last()?->id ?? $after;

        if ($deviceId !== null) {
            SyncDevice::updateOrCreate(
                ['user_id' => $userId, 'device_id' => $deviceId],
                ['after_id' => $nextAfter, 'last_seen_at' => now()],
            );
        }

        return response()->json([
            'entries' => $entries->map(fn (SyncLogEntry $entry) => [
                'id' => $entry->id,
                'room_id' => $entry->room_id,
                'record_type' => $entry->record_type,
                'record_id' => $entry->record_id,
                'device_id' => $entry->device_id,
                'payload' => $entry->payload,
                'deleted' => $entry->deleted,
                'changed_at' => $entry->changed_at,
                'changed_at_ms' => $entry->changed_at_ms,
                // Penulis buram (Tahap 2 bagian B R3): id anggota (user_id) tidak pernah dikirim mentah;
                // hash per ruang supaya tidak bisa dikorelasikan ke akun lain tanpa tahu room_id-nya.
                'author' => self::opaqueAuthor($entry->room_id, $entry->user_id),
            ]),
            'next_after' => $nextAfter,
            'has_more' => $entries->count() === $limit,
            'server_time_ms' => now()->getTimestampMs(),
        ]);
    }

    private static function opaqueAuthor(?string $roomId, int $userId): string
    {
        return substr(hash('sha256', 'rizqflow-sync-author|'.($roomId ?? 'a').'|'.$userId), 0, 16);
    }
}
