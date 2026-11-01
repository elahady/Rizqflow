<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Models\PushToken;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Http\Response;

/**
 * Daftar dan cabut token FCM perangkat (Tahap 9/13, server-sync.md "Notifikasi push"). Semua di dalam
 * `auth:sanctum` dan hanya menyentuh baris milik pemilik token API; `device_id` dari klien hanya
 * memilih perangkat DI DALAM akun itu.
 *
 * `device_id` seharusnya sama dengan yang dikirim saat masuk (`POST /api/auth/google` atau
 * `/auth/login`), supaya `POST /api/auth/logout` ikut mencabut token push perangkat ini.
 *
 * Tidak butuh langganan: daftar token tidak membuka data apa pun, dan kabar hanya dikirim untuk ruang
 * bersama yang memang diikuti akun ini.
 */
class PushTokenController extends Controller
{
    private const DEVICE_ID_RULE = ['required', 'string', 'regex:/^[A-Za-z0-9._-]{1,100}$/'];

    public function store(Request $request): JsonResponse
    {
        $data = $request->validate([
            'device_id' => self::DEVICE_ID_RULE,
            // Token FCM: ratusan karakter tanpa spasi; batas longgar.
            'token' => ['required', 'string', 'max:4096', 'regex:/^\S+$/'],
        ]);

        $baris = PushToken::daftarkan($request->user()->id, $data['device_id'], $data['token']);

        return response()->json([
            'device_id' => $baris->device_id,
            'updated_at' => $baris->updated_at?->utc()->format('Y-m-d\TH:i:s\Z'),
        ]);
    }

    /** Idempoten: 204 juga bila perangkat ini belum pernah mendaftarkan token. */
    public function destroy(Request $request, string $deviceId): Response
    {
        validator(['device_id' => $deviceId], ['device_id' => self::DEVICE_ID_RULE])->validate();

        PushToken::query()
            ->where('user_id', $request->user()->id)
            ->where('device_id', $deviceId)
            ->delete();

        return response()->noContent();
    }
}
