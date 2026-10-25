<?php

use App\Http\Controllers\Api\AccountController;
use App\Http\Controllers\Api\PlayNotificationController;
use App\Http\Controllers\Api\PurchaseController;
use App\Http\Controllers\Api\RecoveryKeyController;
use App\Http\Controllers\Api\RoomMembershipController;
use App\Http\Controllers\Api\SyncController;
use Illuminate\Support\Facades\Route;

// Akun server dan sinkronisasi untuk aplikasi Android (Tahap 9, server-sync.md).
Route::post('/auth/google', [AccountController::class, 'login'])
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
    Route::delete('/auth/tokens', [AccountController::class, 'revokeAll']);

    // Klaim pembelian Pro/Sync: token dikirim aplikasi, diverifikasi server ke Google Play lalu
    // mengisi users.plans (App\Play\PurchaseClaimService). Throttle per akun.
    Route::post('/purchases/claim', [PurchaseController::class, 'claim'])
        ->middleware('throttle:purchase-claim');

    // Butuh langganan Sync (Feature::EncryptedSync); lihat SyncController::denyWithoutSync.
    Route::post('/sync/push', [SyncController::class, 'push']);
    Route::get('/sync/pull', [SyncController::class, 'pull']);

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
});
