<?php

namespace App\Http\Controllers\Api;

use App\Auth\ApiTokenService;
use App\Auth\GoogleAccountResolver;
use App\Auth\GoogleIdTokenVerifier;
use App\Http\Controllers\Controller;
use Illuminate\Auth\AuthenticationException;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Http\Response;
use Illuminate\Validation\ValidationException;
use Laravel\Sanctum\PersonalAccessToken;

/**
 * Akun server untuk aplikasi Android (Tahap 9, server-sync.md): tukar ID token Google jadi token
 * API Sanctum untuk endpoint sync berikutnya, putar/cabut token (Tahap 12), dan hapus akun.
 * Verifikasi dan pencarian/pembuatan akun memakai kelas yang sama dengan masuk web
 * ([GoogleIdTokenVerifier], [GoogleAccountResolver]) karena ID token Android memverifikasi audiens
 * (client ID Web) yang sama — satu akun yang sama dikenali di kedua platform lewat `sub`.
 *
 * Token API kedaluwarsa (`API_TOKEN_TTL_DAYS`, bawaan 90 hari) dan bisa dicabut; masa berlaku dan
 * penerbitannya dihitung di satu tempat, [ApiTokenService].
 */
class AccountController extends Controller
{
    /**
     * Tukar ID token Google (dari Android) jadi token API. Tidak membuat sesi web (stateless).
     * Jawaban membawa `expires_at` (ISO-8601 UTC) supaya klien tahu kapan harus memutar token.
     */
    public function login(Request $request, GoogleIdTokenVerifier $verifier, GoogleAccountResolver $resolver, ApiTokenService $tokens): JsonResponse
    {
        // device_id opsional (klien lama tanpa itu memakai nama bawaan). Tetapi bila dikirim, harus berupa
        // pengenal pendek (UUID, dst.) karena menjadi nama token; panjang dan karakter dibatasi.
        $request->validate([
            'credential' => ['required', 'string'],
            'device_id' => ['sometimes', 'nullable', 'string', 'max:64', 'regex:/^[A-Za-z0-9._-]+$/'],
        ]);

        $claims = $verifier->verify($request->string('credential'));
        if ($claims === null || ($claims['email_verified'] ?? false) !== true || empty($claims['email'])) {
            throw ValidationException::withMessages(['credential' => ['Masuk dengan Google gagal.']]);
        }

        $user = $resolver->resolve($claims);

        // Satu token aktif per perangkat: token lama dengan nama yang sama dicabut dulu, supaya
        // masuk ulang di perangkat yang sama tidak menumpuk token. Jumlah token per akun juga
        // dibatasi (ApiTokenService::MAX_TOKENS_PER_ACCOUNT, yang terlama dibuang), jadi device_id
        // yang terus berganti pun tidak menumpuk token tanpa batas.
        $token = $tokens->issueForDevice($user, self::tokenName($request));

        return response()->json([
            ...ApiTokenService::payload($token),
            'user' => ['id' => $user->id, 'name' => $user->name, 'email' => $user->email],
        ]);
    }

    /**
     * Putar token: token yang dipakai permintaan ini dicabut dan token baru (nama per perangkat dan
     * abilities sama) terbit dengan masa berlaku penuh. Token kedaluwarsa atau tak sah sudah ditolak 401
     * oleh `auth:sanctum` (klien harus masuk ulang dengan Google). Dua refresh bersamaan dengan token
     * yang sama: paling banyak satu berhasil, yang kalah dijawab 401 (lihat [ApiTokenService::rotate]).
     */
    public function refresh(Request $request, ApiTokenService $tokens): JsonResponse
    {
        $user = $request->user();
        $current = $user->currentAccessToken();

        // Hanya token API sungguhan yang bisa diputar; bukan otentikasi sesi (TransientToken).
        $new = $current instanceof PersonalAccessToken ? $tokens->rotate($user, $current) : null;
        if ($new === null) {
            throw new AuthenticationException;
        }

        return response()->json(ApiTokenService::payload($new));
    }

    /** Keluar di perangkat ini: HANYA token yang dipakai permintaan ini dicabut; token perangkat lain tetap. */
    public function logout(Request $request): Response
    {
        $current = $request->user()->currentAccessToken();
        if ($current instanceof PersonalAccessToken) {
            $current->delete();
        }

        return response()->noContent();
    }

    /** Keluar dari semua perangkat: SEMUA token API akun ini dicabut. */
    public function revokeAll(Request $request): Response
    {
        $request->user()->tokens()->delete();

        return response()->noContent();
    }

    /**
     * Hapus akun dari aplikasi (syarat Google Play bila ada pembuatan akun). Mencabut semua token
     * dan menghapus baris pengguna; data lain yang tertaut (lihat migrasi) ikut terhapus lewat
     * `cascadeOnDelete`. Ini menghapus akun SERVER saja — data di ponsel pengguna tidak disentuh.
     */
    public function destroy(Request $request): JsonResponse
    {
        $user = $request->user();
        $user->tokens()->delete();
        $user->delete();

        return response()->json(status: 204);
    }

    private static function tokenName(Request $request): string
    {
        // Sudah divalidasi di login(); kosong/tidak dikirim jatuh ke nama bawaan.
        $deviceId = $request->input('device_id');

        return 'android:'.(is_string($deviceId) && $deviceId !== '' ? $deviceId : 'default');
    }
}
