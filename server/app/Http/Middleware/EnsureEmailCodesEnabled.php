<?php

namespace App\Http\Middleware;

use Closure;
use Illuminate\Http\Request;
use Symfony\Component\HttpFoundation\Response;

/**
 * Menjaga route yang butuh pengiriman email (Daftar, verifikasi, lupa/atur ulang kata sandi). Selama
 * saklar config('rizqflow.email_codes_enabled') mati (pengirim email produksi belum dipasang), route
 * ini mengalihkan ke Masuk dengan pesan ramah, bukan 404 atau galat pengiriman.
 */
class EnsureEmailCodesEnabled
{
    public function handle(Request $request, Closure $next): Response
    {
        if (! config('rizqflow.email_codes_enabled')) {
            return redirect()->route('login')->with('status', 'Daftar dan atur ulang kata sandi lewat email belum tersedia. Masuk dengan Google, atau dengan email dan kata sandi kalau kamu sudah punya.');
        }

        return $next($request);
    }
}
