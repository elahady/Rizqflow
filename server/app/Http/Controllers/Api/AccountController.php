<?php

namespace App\Http\Controllers\Api;

use App\Auth\GoogleAccountResolver;
use App\Auth\GoogleIdTokenVerifier;
use App\Http\Controllers\Controller;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Validation\ValidationException;

/**
 * Akun server untuk aplikasi Android (Tahap 9, server-sync.md): tukar ID token Google jadi token
 * API Sanctum untuk endpoint sync berikutnya, dan hapus akun. Verifikasi dan pencarian/pembuatan
 * akun memakai kelas yang sama dengan masuk web ([GoogleIdTokenVerifier], [GoogleAccountResolver])
 * karena ID token Android memverifikasi audiens (client ID Web) yang sama — satu akun yang sama
 * dikenali di kedua platform lewat `sub`.
 */
class AccountController extends Controller
{
    /** Tukar ID token Google (dari Android) jadi token API. Tidak membuat sesi web (stateless). */
    public function login(Request $request, GoogleIdTokenVerifier $verifier, GoogleAccountResolver $resolver): JsonResponse
    {
        $request->validate(['credential' => ['required', 'string']]);

        $claims = $verifier->verify($request->string('credential'));
        if ($claims === null || ($claims['email_verified'] ?? false) !== true || empty($claims['email'])) {
            throw ValidationException::withMessages(['credential' => ['Masuk dengan Google gagal.']]);
        }

        $user = $resolver->resolve($claims);

        // Satu token aktif per perangkat: token lama dengan nama yang sama dicabut dulu, supaya
        // masuk ulang di perangkat yang sama tidak menumpuk token tak terbatas.
        $user->tokens()->where('name', self::tokenName($request))->delete();
        $token = $user->createToken(self::tokenName($request))->plainTextToken;

        return response()->json([
            'token' => $token,
            'user' => ['id' => $user->id, 'name' => $user->name, 'email' => $user->email],
        ]);
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
        return 'android:'.$request->string('device_id', 'default')->toString();
    }
}
