<?php

use Illuminate\Foundation\Inspiring;
use Illuminate\Support\Facades\Artisan;
use Illuminate\Support\Facades\Schedule;

Artisan::command('inspire', function () {
    $this->comment(Inspiring::quote());
})->purpose('Display an inspiring quote');

// Verifikasi ulang langganan Play yang kedaluwarsa tanpa notifikasi (Tahap 9, DEPLOY.md
// "Pembelian Google Play"). Baru berjalan bila ada cron `php artisan schedule:run` tiap menit di
// server; sampai itu dipasang (Tahap 12, kerja pemilik) perintah ini harus dijalankan manual.
Schedule::command('play:reconcile')->dailyAt('03:30')->withoutOverlapping();

// Buang token API yang sudah kedaluwarsa lebih dari 24 jam (Tahap 12, token kedaluwarsa dan bisa
// dicabut). Token kedaluwarsa sudah ditolak 401 tanpa perintah ini; ini hanya merapikan tabel
// personal_access_tokens. Sama seperti play:reconcile, baru berjalan bila ada cron `schedule:run`
// di VPS (Tahap 12, kerja pemilik; lihat DEPLOY.md "Token API").
Schedule::command('sanctum:prune-expired --hours=24')->dailyAt('03:45')->withoutOverlapping();
