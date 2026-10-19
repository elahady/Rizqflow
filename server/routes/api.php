<?php

use App\Http\Controllers\Api\AccountController;
use App\Http\Controllers\Api\SyncController;
use Illuminate\Support\Facades\Route;

// Akun server dan sinkronisasi untuk aplikasi Android (Tahap 9, server-sync.md).
Route::post('/auth/google', [AccountController::class, 'login']);

Route::middleware('auth:sanctum')->group(function () {
    Route::delete('/account', [AccountController::class, 'destroy']);

    // Butuh langganan Sync (Feature::EncryptedSync); lihat SyncController::denyWithoutSync.
    Route::post('/sync/push', [SyncController::class, 'push']);
    Route::get('/sync/pull', [SyncController::class, 'pull']);
});
