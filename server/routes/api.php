<?php

use App\Http\Controllers\Api\AccountController;
use Illuminate\Support\Facades\Route;

// Akun server untuk aplikasi Android (Tahap 9, server-sync.md). Rute sync/backup berikutnya
// menyusul di grup auth:sanctum yang sama.
Route::post('/auth/google', [AccountController::class, 'login']);

Route::middleware('auth:sanctum')->group(function () {
    Route::delete('/account', [AccountController::class, 'destroy']);
});
