<?php

return [

    /*
    |--------------------------------------------------------------------------
    | Third Party Services
    |--------------------------------------------------------------------------
    |
    | This file is for storing the credentials for third party services such
    | as Mailgun, Postmark, AWS and more. This file provides the de facto
    | location for this type of information, allowing packages to have
    | a conventional file to locate the various service credentials.
    |
    */

    'postmark' => [
        'key' => env('POSTMARK_API_KEY'),
    ],

    'resend' => [
        'key' => env('RESEND_API_KEY'),
    ],

    'ses' => [
        'key' => env('AWS_ACCESS_KEY_ID'),
        'secret' => env('AWS_SECRET_ACCESS_KEY'),
        'region' => env('AWS_DEFAULT_REGION', 'us-east-1'),
    ],

    // Client ID Web yang sama dengan Android (app/src/main/res/values/google_auth.xml). ID publik,
    // bukan rahasia. Lihat docs/auth-google.md bagian Web.
    'google' => [
        'client_id' => env('GOOGLE_CLIENT_ID', ''),
    ],

    // Verifikasi pembelian Google Play dan Real-time Developer Notifications (Tahap 9, DEPLOY.md
    // "Pembelian Google Play"). Semua nilai kosong berarti fitur mati (klaim menjawab 503, endpoint
    // notifikasi menjawab 503); tidak ada nilai rahasia di berkas ini.
    'play' => [
        // ID paket aplikasi Android; harus sama dengan yang dipakai Play Console.
        'package_name' => env('PLAY_PACKAGE_NAME', 'com.roziqrizal.rizqflow'),
        // Kunci akun layanan Google Cloud yang diberi izin di Play Console: jalur berkas JSON, isi
        // JSON mentah, atau isi JSON dalam base64 (satu baris, cocok untuk env_file Docker).
        'service_account_json' => env('PLAY_SERVICE_ACCOUNT_JSON'),
        // Audience JWT OIDC pada langganan push Pub/Sub (biasanya URL endpoint notifikasi). Kosong
        // = endpoint notifikasi menolak semua permintaan dengan 503.
        'rtdn_audience' => env('PLAY_RTDN_AUDIENCE'),
        // Opsional tapi dianjurkan: email akun layanan penanda tangan push Pub/Sub; bila diisi,
        // klaim email token harus sama dan email_verified.
        'rtdn_service_account_email' => env('PLAY_RTDN_SERVICE_ACCOUNT_EMAIL'),
    ],

    'slack' => [
        'notifications' => [
            'bot_user_oauth_token' => env('SLACK_BOT_USER_OAUTH_TOKEN'),
            'channel' => env('SLACK_BOT_USER_DEFAULT_CHANNEL'),
        ],
    ],

];
