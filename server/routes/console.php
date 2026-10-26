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

// Retensi sinkron (Tahap 7, sync-desain.md bagian J #6): buang kursor perangkat yang tidak aktif 180 hari,
// lalu kompaksi versi kalah (>= 90 hari) dan tombstone (>= 400 hari, sudah dilewati semua perangkat aktif).
// Urutan tidak mengubah hasil: keduanya memakai batas "aktif" yang sama. Sama seperti jadwal lain di atas,
// baru berjalan bila ada cron `schedule:run` di server (Tahap 12).
Schedule::command('sync:prune-stale-devices')->dailyAt('03:55')->withoutOverlapping();
Schedule::command('sync:compact')->dailyAt('04:05')->withoutOverlapping();
