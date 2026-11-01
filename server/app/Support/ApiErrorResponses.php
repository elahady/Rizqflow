<?php

namespace App\Support;

use Illuminate\Auth\AuthenticationException;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Validation\ValidationException;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\HttpKernel\Exception\HttpExceptionInterface;
use Throwable;

/**
 * Menyeragamkan badan galat JSON untuk `/api/*` yang berasal dari PENGECUALIAN (rute tak ada, metode
 * salah, belum masuk, validasi, batas laju, badan terlalu besar, galat server) supaya klien mendapat
 * bentuk yang sama dengan galat yang dibuat controller: `{"message": "...", "code": "..."}`.
 *
 * Tanpa ini, galat bawaan Laravel tidak punya `code` dan pesannya berbahasa Inggris (mis. "Unauthenticated.",
 * "Too Many Attempts."), sehingga klien hanya bisa membedakan kasus lewat status HTTP. Kode di sini stabil
 * dan boleh dijadikan percabangan klien:
 *
 * | Status | code |
 * |---|---|
 * | 401 | `unauthenticated` |
 * | 403 | `forbidden` (galat 403 buatan controller membawa kode sendiri, mis. `sync_required`, `not_room_owner`) |
 * | 404 | `not_found` (rute tak ada; 404 buatan controller membawa kode sendiri) |
 * | 405 | `method_not_allowed` |
 * | 413 | `payload_too_large` |
 * | 422 validasi | `validation_failed` (isian `errors` tetap seperti bawaan Laravel) |
 * | 429 | `rate_limited`, plus `retry_after` (detik, dari header `Retry-After`) |
 * | 5xx | `server_error` |
 *
 * Hanya menambah isian (`code`, `retry_after`) dan mengganti `message` bawaan Laravel yang berbahasa Inggris;
 * isian lain, status, dan header (termasuk `Retry-After`) tidak diubah. Galat yang sudah punya `code`
 * (dibuat controller) tidak disentuh. Saat `APP_DEBUG` aktif, pesan asli tidak diganti supaya jejak galat tetap terbaca.
 */
final class ApiErrorResponses
{
    public static function normalize(Response $response, Throwable $e, Request $request): Response
    {
        if (! $request->is('api/*') || ! $response instanceof JsonResponse || $response->getStatusCode() < 400) {
            return $response;
        }

        $body = $response->getData(true);
        if (! is_array($body) || isset($body['code'])) {
            return $response;
        }

        $status = $response->getStatusCode();
        $code = self::codeFor($e, $status);
        if ($code === null) {
            return $response;
        }

        $body['code'] = $code;
        if (! config('app.debug') && ($message = self::messageFor($e, $status)) !== null) {
            $body['message'] = $message;
        }
        if ($status === 429 && ($retryAfter = $response->headers->get('Retry-After')) !== null && ctype_digit($retryAfter)) {
            $body['retry_after'] = (int) $retryAfter;
        }

        // Urutan isian: message, code, lalu sisanya (errors, retry_after), supaya badan mudah dibaca.
        $ordered = ['message' => $body['message'] ?? '', 'code' => $body['code']] + $body;
        $response->setData($ordered);

        return $response;
    }

    private static function codeFor(Throwable $e, int $status): ?string
    {
        return match (true) {
            $e instanceof AuthenticationException, $status === 401 => 'unauthenticated',
            $e instanceof ValidationException => 'validation_failed',
            $status === 403 => 'forbidden',
            $status === 404 => 'not_found',
            $status === 405 => 'method_not_allowed',
            $status === 413 => 'payload_too_large',
            $status === 429 => 'rate_limited',
            $status >= 500 => 'server_error',
            // 4xx lain (mis. 419, 409) tidak dikenal: biarkan apa adanya daripada menebak kode.
            default => null,
        };
    }

    /** Pesan Indonesia untuk galat yang pesan bawaannya berbahasa Inggris; null = pertahankan pesan yang ada. */
    private static function messageFor(Throwable $e, int $status): ?string
    {
        return match (true) {
            $e instanceof AuthenticationException, $status === 401 => 'Perlu masuk, atau sesi sudah berakhir.',
            // Pesan validasi sudah mengikuti locale aplikasi; jangan ditimpa.
            $e instanceof ValidationException => null,
            // abort(403, '...') buatan kita sendiri tetap dipakai; hanya pesan kosong yang diganti.
            $status === 403 => ($e instanceof HttpExceptionInterface && $e->getMessage() !== '') ? null : 'Tidak diizinkan.',
            $status === 404 => 'Alamat atau data yang diminta tidak ditemukan.',
            $status === 405 => 'Metode HTTP ini tidak didukung untuk alamat tersebut.',
            $status === 413 => 'Badan permintaan terlalu besar.',
            $status === 429 => 'Terlalu banyak permintaan. Coba lagi sebentar lagi.',
            $status >= 500 => 'Terjadi kesalahan di server. Coba lagi nanti.',
            default => null,
        };
    }
}
