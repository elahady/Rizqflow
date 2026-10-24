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
