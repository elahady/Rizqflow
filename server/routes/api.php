<?php

use App\Http\Controllers\Api\AccountController;
use App\Http\Controllers\Api\PlayNotificationController;
use App\Http\Controllers\Api\PurchaseController;
use App\Http\Controllers\Api\RecoveryKeyController;
use App\Http\Controllers\Api\RoomMembershipController;
use App\Http\Controllers\Api\SyncController;
use App\Http\Controllers\Api\SyncHistoryController;
use Illuminate\Support\Facades\Route;

// Akun server dan sinkronisasi untuk aplikasi Android (Tahap 9, server-sync.md).
Route::post('/auth/google', [AccountController::class, 'login'])
    ->middleware('throttle:api-login');

// Masuk dengan email+sandi (Tahap 14): akun server yang sama dengan masuk web, aturan dan batas gagalnya
// juga sama (AccountService::checkLogin). Batas laju per IP memakai limiter `api-login` yang sama.
Route::post('/auth/login', [AccountController::class, 'loginWithPassword'])
    ->middleware('throttle:api-login');

// Real-time Developer Notifications Google Play (push Pub/Sub). TANPA Sanctum: pemanggilnya Google,
// diautentikasi lewat JWT OIDC di header Authorization (lihat PlayNotificationController).
Route::post('/play/notifications', [PlayNotificationController::class, 'receive'])
    ->middleware('throttle:play-notifications');

Route::middleware('auth:sanctum')->group(function () {
    Route::delete('/account', [AccountController::class, 'destroy']);

    // Siklus hidup token API (Tahap 12): token kedaluwarsa (API_TOKEN_TTL_DAYS) dan bisa dicabut.
    // refresh memutar token yang dipakai; logout mencabut token itu saja; tokens mencabut semua
    // token akun. Batas laju HANYA di refresh (limiter `api-token-refresh`, AppServiceProvider); logout
    // dan cabut-semua sengaja tanpa batas supaya pemegang token curian tak bisa menghalangi pemilik
    // keluar dari semua perangkat dengan menghabiskan jatah refresh.
    Route::post('/auth/refresh', [AccountController::class, 'refresh'])
        ->middleware('throttle:api-token-refresh');
    Route::post('/auth/logout', [AccountController::class, 'logout']);
    // Daftar perangkat (Tahap 14): metadata token akun ini, tanpa isi token. DELETE mencabut semuanya ("Keluar dari semua perangkat").
    Route::get('/auth/tokens', [AccountController::class, 'devices']);
    Route::delete('/auth/tokens', [AccountController::class, 'revokeAll']);

    // Klaim pembelian Pro/Sync: token dikirim aplikasi, diverifikasi server ke Google Play lalu
    // mengisi users.plans (App\Play\PurchaseClaimService). Throttle per akun.
    Route::post('/purchases/claim', [PurchaseController::class, 'claim'])
        ->middleware('throttle:purchase-claim');

    // Butuh langganan Sync (Feature::EncryptedSync); lihat SyncController::denyWithoutSync.
    Route::post('/sync/push', [SyncController::class, 'push']);
    Route::get('/sync/pull', [SyncController::class, 'pull']);
    // Riwayat sinkron (Tahap 7): ringkasan metadata saja, tanpa payload; tidak butuh langganan Sync.
    Route::get('/sync/history', [SyncHistoryController::class, 'show']);

    // Salinan kunci akun terbungkus kode pemulihan (Tahap 13), blob buram. Satu limiter `recovery-key`
    // untuk ketiga rute (per akun). Hanya PUT yang butuh Sync; GET dan DELETE tidak, supaya pengguna
    // yang langganannya berakhir tetap bisa memulihkan atau menghapus miliknya. Lihat RecoveryKeyController.
    Route::middleware('throttle:recovery-key')->group(function () {
        Route::put('/account/recovery-key', [RecoveryKeyController::class, 'update']);
        Route::get('/account/recovery-key', [RecoveryKeyController::class, 'show']);
        Route::delete('/account/recovery-key', [RecoveryKeyController::class, 'destroy']);
    });

    // Keanggotaan ruang yang dibagi (ruang keluarga) — lihat RoomMembershipController.
    Route::post('/rooms/{roomId}/join', [RoomMembershipController::class, 'join']);
    Route::get('/rooms/{roomId}/members', [RoomMembershipController::class, 'members']);
    Route::delete('/rooms/{roomId}/members/{userId}', [RoomMembershipController::class, 'remove']);
});
