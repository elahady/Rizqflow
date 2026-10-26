<?php

namespace App\Http\Controllers\Api;

use App\Auth\AccountService;
use App\Auth\ApiTokenService;
use App\Auth\GoogleAccountResolver;
use App\Auth\GoogleIdTokenVerifier;
use App\Http\Controllers\Controller;
use App\Models\User;
use Illuminate\Auth\AuthenticationException;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Http\Response;
use Illuminate\Support\Carbon;
use Illuminate\Validation\ValidationException;
use Laravel\Sanctum\PersonalAccessToken;

/**
 * Akun server untuk aplikasi Android (Tahap 9, server-sync.md): tukar ID token Google (atau email+sandi,
 * Tahap 14, [loginWithPassword]) jadi token API Sanctum untuk endpoint sync berikutnya, putar/cabut token
 * (Tahap 12), dan hapus akun.
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
            'device_name' => ['sometimes', 'nullable', 'string', 'max:100'],
        ]);

        $claims = $verifier->verify($request->string('credential'));
        if ($claims === null || ($claims['email_verified'] ?? false) !== true || empty($claims['email'])) {
            throw ValidationException::withMessages(['credential' => ['Masuk dengan Google gagal.']]);
        }

        $user = $resolver->resolve($claims);

        return self::accountResponse($request, $user, $tokens);
    }

    /**
     * Masuk dengan email+sandi (Tahap 14, server-sync.md): akun server yang SAMA dengan masuk web. Aturan dan batas
     * gagalnya dari [AccountService::checkLogin] (5 gagal per menit per IP, 10 per 15 menit per email). Galat selalu
     * 422 dengan pesan Bahasa Indonesia yang siap ditampilkan; email tak dikenal, akun tanpa sandi (hanya Google),
     * dan sandi salah dijawab sama persis supaya tidak membocorkan email yang terdaftar. Email yang belum
     * diverifikasi tidak dapat token: kodenya dikirim seperti di web, lalu klien diminta verifikasi lewat web.
     * Jawaban sukses sama bentuknya dengan [login] (token, expires_at, user).
     */
    public function loginWithPassword(Request $request, AccountService $accounts, ApiTokenService $tokens): JsonResponse
    {
        $request->validate([
            'email' => ['required', 'string', 'email'],
            'password' => ['required', 'string'],
            'device_id' => ['sometimes', 'nullable', 'string', 'max:64', 'regex:/^[A-Za-z0-9._-]+$/'],
            'device_name' => ['sometimes', 'nullable', 'string', 'max:100'],
        ]);

        [$status, $detail] = $accounts->checkLogin((string) $request->input('email'), (string) $request->input('password'), (string) $request->ip());

        if ($status === AccountService::LOGIN_THROTTLED) {
            throw ValidationException::withMessages([
                'email' => ['Terlalu banyak percobaan. Coba lagi dalam '.$detail.' detik.'],
            ]);
        }

        if ($status === AccountService::LOGIN_INVALID) {
            throw ValidationException::withMessages(['email' => ['Email atau kata sandi salah.']]);
        }

        // Kata sandi benar tapi email belum diverifikasi: sama seperti web, kodenya dikirim (bila pengiriman aktif)
        // dan tidak ada token. Klien tidak punya layar verifikasi, jadi pesannya menunjuk ke web.
        if ($status === AccountService::LOGIN_UNVERIFIED) {
            if (! config('rizqflow.email_codes_enabled')) {
                throw ValidationException::withMessages([
                    'email' => ['Emailmu belum diverifikasi dan pengiriman kode belum tersedia. Masuk dengan Google untuk sementara.'],
                ]);
            }

            $accounts->sendVerification($detail);

            throw ValidationException::withMessages([
                'email' => ['Emailmu belum diverifikasi. Kode verifikasi sudah kami kirim ke email itu; verifikasi lewat web Rizqflow, lalu masuk lagi di sini.'],
            ]);
        }

        return self::accountResponse($request, $detail, $tokens);
    }

    /**
     * Terbitkan token untuk perangkat ini dan bentuk jawaban masuk (dipakai masuk Google dan email+sandi).
     * Satu token aktif per perangkat: token lama dengan nama yang sama dicabut dulu, supaya masuk ulang di perangkat
     * yang sama tidak menumpuk token. Jumlah token per akun juga dibatasi (ApiTokenService::MAX_TOKENS_PER_ACCOUNT,
     * yang terlama dibuang), jadi device_id yang terus berganti pun tidak menumpuk token tanpa batas.
     */
    private static function accountResponse(Request $request, User $user, ApiTokenService $tokens): JsonResponse
    {
        $token = $tokens->issueForDevice($user, self::tokenName($request), self::deviceName($request));

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
        $request->validate([
            'device_name' => ['sometimes', 'nullable', 'string', 'max:100'],
        ]);

        $user = $request->user();
        $current = $user->currentAccessToken();

        // Hanya token API sungguhan yang bisa diputar; bukan otentikasi sesi (TransientToken).
        $new = $current instanceof PersonalAccessToken ? $tokens->rotate($user, $current, self::deviceName($request)) : null;
        if ($new === null) {
            throw new AuthenticationException;
        }

        return response()->json(ApiTokenService::payload($new));
    }

    /**
     * Daftar token API aktif akun ini (Tahap 14, "Daftar perangkat"): satu item per perangkat yang masuk. Isi token
     * TIDAK pernah dijawab (hanya metadata); `current` menandai token yang dipakai permintaan ini. Token yang sudah
     * kedaluwarsa tetap ikut terdaftar dengan `expires_at` di masa lalu, sama seperti daftar di Profil web.
     */
    public function devices(Request $request): JsonResponse
    {
        $user = $request->user();
        $current = $user->currentAccessToken();
        $currentId = $current instanceof PersonalAccessToken ? $current->getKey() : null;

        $devices = $user->tokens()
            ->orderByRaw('COALESCE(last_used_at, created_at) desc')
            ->orderByDesc('id')
            ->get(['id', 'name', 'device_name', 'created_at', 'last_used_at', 'expires_at'])
            ->map(fn (PersonalAccessToken $token) => [
                'id' => $token->id,
                'label' => str_starts_with($token->name, 'android:') ? 'Android' : 'Perangkat lain',
                'device_name' => $token->device_name,
                'created_at' => self::iso($token->created_at),
                'last_used_at' => self::iso($token->last_used_at),
                'expires_at' => self::iso($token->expires_at),
                'current' => $token->getKey() === $currentId,
            ])
            ->values()
            ->all();

        return response()->json(['devices' => $devices]);
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

    /** Nama perangkat dari klien (opsional, dipangkas); kosong atau tidak dikirim berarti null. */
    private static function deviceName(Request $request): ?string
    {
        $name = trim((string) $request->input('device_name', ''));

        return $name === '' ? null : $name;
    }

    /** Waktu ke ISO-8601 UTC detik penuh, bentuk yang sama dengan `expires_at` di jawaban token. */
    private static function iso(?\DateTimeInterface $at): ?string
    {
        return $at === null ? null : Carbon::instance($at)->utc()->format('Y-m-d\TH:i:s\Z');
    }

    private static function tokenName(Request $request): string
    {
        // Sudah divalidasi di login(); kosong/tidak dikirim jatuh ke nama bawaan.
        $deviceId = $request->input('device_id');

        return 'android:'.(is_string($deviceId) && $deviceId !== '' ? $deviceId : 'default');
    }
}
