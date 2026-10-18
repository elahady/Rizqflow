<?php

use App\Http\Controllers\Auth\AuthenticatedSessionController;
use App\Http\Controllers\Auth\RegisteredUserController;
use App\Livewire\Denah;
use App\Livewire\Ruang;
use App\Livewire\Transaksi;
use App\Livewire\Zakat;
use Illuminate\Support\Facades\Route;

Route::middleware('guest')->group(function () {
    Route::get('/login', [AuthenticatedSessionController::class, 'create'])->name('login');
    Route::post('/login', [AuthenticatedSessionController::class, 'store']);
    Route::get('/register', [RegisteredUserController::class, 'create'])->name('register');
    Route::post('/register', [RegisteredUserController::class, 'store']);
});

Route::middleware('auth')->group(function () {
    Route::post('/logout', [AuthenticatedSessionController::class, 'destroy'])->name('logout');

    Route::get('/', Denah::class);
    Route::get('/denah', Denah::class)->name('denah');
    Route::get('/transaksi', Transaksi::class)->name('transaksi');
    Route::get('/ruang/{room?}', Ruang::class)->name('ruang');
    Route::get('/zakat', Zakat::class)->name('zakat');
});
