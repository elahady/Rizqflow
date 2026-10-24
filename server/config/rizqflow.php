<?php

return [

    /*
    | Saklar fitur email: Daftar, verifikasi email, dan Lupa/atur ulang kata sandi lewat kode 6 angka.
    | Butuh pengirim email sungguhan. Menyala otomatis kalau MAIL_MAILER bukan "log"/"array" atau
    | lingkungannya local/testing; bisa dipaksa lewat EMAIL_CODES_ENABLED=true|false. Saat mati,
    | tautan Daftar dan "Lupa kata sandi?" disembunyikan dan route-nya mengalihkan ke Masuk dengan
    | pesan ramah; masuk email+sandi dan Google tetap berfungsi.
    */
    'email_codes_enabled' => env('EMAIL_CODES_ENABLED') !== null
        ? filter_var(env('EMAIL_CODES_ENABLED'), FILTER_VALIDATE_BOOL)
        : (in_array(env('APP_ENV', 'production'), ['local', 'testing'], true)
            || ! in_array(env('MAIL_MAILER', 'log'), ['log', 'array'], true)),

    // Batas email per hari di paket layanan pengirim (Brevo gratis: 300 per hari). Saat terkirim
    // mencapai 80% batas, pemilik diberi tahu satu kali per hari (OWNER_EMAIL).
    'mail_daily_limit' => (int) env('MAIL_DAILY_LIMIT', 300),

    // Alamat pemilik untuk pemberitahuan operasional (batas email hampir habis).
    'owner_email' => env('OWNER_EMAIL'),

    // Kode 6 angka lewat email: masa berlaku (menit) dan batas salah per kode.
    'email_code_ttl_minutes' => 10,
    'email_code_max_attempts' => 5,

];
