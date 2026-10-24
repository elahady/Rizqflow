<?php

use App\Auth\ApiTokenService;
use Illuminate\Cookie\Middleware\EncryptCookies;
use Illuminate\Foundation\Http\Middleware\ValidateCsrfToken;
use Laravel\Sanctum\Http\Middleware\AuthenticateSession;
use Laravel\Sanctum\Sanctum;

// Diterbitkan dari vendor/laravel/sanctum/config/sanctum.php (Tahap 12). Satu-satunya nilai yang
// diubah dari bawaan Sanctum adalah `expiration`; sisanya sama dengan bawaan.
return [

    /*
    |--------------------------------------------------------------------------
    | Stateful Domains
    |--------------------------------------------------------------------------
    |
    | Permintaan dari host berikut mendapat cookie autentikasi stateful (untuk SPA first-party).
    | Rizqflow memakai token Bearer untuk aplikasi Android dan sesi Livewire untuk web, jadi
    | nilai bawaan dibiarkan.
    |
    */

    'stateful' => explode(',', env('SANCTUM_STATEFUL_DOMAINS', sprintf(
        '%s%s',
        'localhost,localhost:3000,127.0.0.1,127.0.0.1:8000,::1',
        Sanctum::currentApplicationUrlWithPort(),
        // Sanctum::currentRequestHost(),
    ))),

    /*
    |--------------------------------------------------------------------------
    | Sanctum Guards
    |--------------------------------------------------------------------------
    |
    | Guard yang dicek lebih dulu sebelum jatuh ke token Bearer.
    |
    */

    'guard' => ['web'],

    /*
    |--------------------------------------------------------------------------
    | Expiration Minutes
    |--------------------------------------------------------------------------
    |
    | Jaring pengaman: Guard menolak token yang `created_at`-nya lebih tua dari ini, juga token tanpa
    | `expires_at` (terbitan sebelum ada masa berlaku). Nilainya sengaja SAMA dengan masa berlaku token
    | baru (`API_TOKEN_TTL_DAYS`, bawaan 90 hari) lewat fungsi penguraian yang sama dengan
    | `config/rizqflow.php`; token baru juga membawa `expires_at` eksplisit (ApiTokenService).
    |
    */

    'expiration' => ApiTokenService::parseTtlDays(env('API_TOKEN_TTL_DAYS')) * 24 * 60,

    /*
    |--------------------------------------------------------------------------
    | Token Prefix
    |--------------------------------------------------------------------------
    |
    | Awalan token supaya pemindai rahasia (mis. GitHub) mengenalinya bila tertulis di repositori.
    |
    */

    'token_prefix' => env('SANCTUM_TOKEN_PREFIX', ''),

    /*
    |--------------------------------------------------------------------------
    | Sanctum Middleware
    |--------------------------------------------------------------------------
    |
    | Middleware yang dipakai Sanctum untuk autentikasi SPA first-party.
    |
    */

    'middleware' => [
        'authenticate_session' => AuthenticateSession::class,
        'encrypt_cookies' => EncryptCookies::class,
        'validate_csrf_token' => ValidateCsrfToken::class,
    ],

];
