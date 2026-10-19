<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Models\SyncLogEntry;
use App\Support\Feature;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;

/**
 * Sinkronisasi antar-perangkat terenkripsi (Tahap 9, server-sync.md "Model konflik sinkronisasi"):
 * log append-only, server tidak pernah membaca `payload` (ciphertext buram dari aplikasi). [push]
 * menerima perubahan dari satu perangkat; [pull] mengirim ulang perubahan dari perangkat lain
 * sejak kursor terakhir. Resolusi "siapa menang" (last-write-wins dari `changed_at`) dikerjakan di
 * aplikasi setelah mendekripsi, bukan di sini — server hanya menyimpan dan meneruskan apa adanya.
 * Pull dari kursor 0 di perangkat baru sekaligus berfungsi sebagai restore dari backup.
 */
class SyncController extends Controller
{
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
        ]);

        $userId = $request->user()->id;
        $ids = [];

        foreach ($data['entries'] as $entry) {
            $ids[] = SyncLogEntry::create([
                'user_id' => $userId,
                'room_id' => $entry['room_id'] ?? null,
                'record_type' => $entry['record_type'],
                'record_id' => $entry['record_id'],
                'device_id' => $entry['device_id'],
                'payload' => $entry['payload'],
                'deleted' => $entry['deleted'] ?? false,
                'changed_at' => $entry['changed_at'],
            ])->id;
        }

        return response()->json(['ids' => $ids, 'cursor' => max($ids)]);
    }

    public function pull(Request $request): JsonResponse
    {
        if ($denied = $this->denyWithoutSync($request)) {
            return $denied;
        }

        $validated = $request->validate([
            'after' => ['integer', 'min:0'],
            'limit' => ['integer', 'min:1', 'max:'.self::MAX_PULL_LIMIT],
        ]);
        // Laravel tidak mengubah tipe nilai yang sudah lolos validasi 'integer'; query string selalu
        // string, dan `$entries->count() === $limit` di bawah butuh int, bukan '2' !== 2.
        $after = (int) ($validated['after'] ?? 0);
        $limit = (int) ($validated['limit'] ?? self::DEFAULT_PULL_LIMIT);

        $entries = SyncLogEntry::where('user_id', $request->user()->id)
            ->where('id', '>', $after)
            ->orderBy('id')
            ->limit($limit)
            ->get(['id', 'room_id', 'record_type', 'record_id', 'device_id', 'payload', 'deleted', 'changed_at']);

        return response()->json([
            'entries' => $entries,
            'next_after' => $entries->last()?->id ?? $after,
            'has_more' => $entries->count() === $limit,
        ]);
    }

    /** Satu-satunya tempat yang menjawab "boleh atau tidak" untuk sync — lihat Support\Entitlements. */
    private function denyWithoutSync(Request $request): ?JsonResponse
    {
        if ($request->user()->entitlements()->isEnabled(Feature::EncryptedSync)) {
            return null;
        }

        return response()->json(['message' => 'Butuh langganan Sync.'], 403);
    }
}
