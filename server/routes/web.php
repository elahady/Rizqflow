<?php

use App\Http\Controllers\Auth\AuthenticatedSessionController;
use App\Http\Controllers\Auth\GoogleLoginController;
use App\Livewire\AturanAlokasi;
use App\Livewire\Denah;
use App\Livewire\Ruang;
use App\Livewire\RuangDaftar;
use App\Livewire\Transaksi;
use App\Livewire\Zakat;
use Illuminate\Support\Facades\Route;

Route::middleware('guest')->group(function () {
    Route::get('/login', [AuthenticatedSessionController::class, 'create'])->name('login');
    Route::post('/auth/google', [GoogleLoginController::class, 'store'])->name('login.google');

    // Masuk demo (email+sandi akun seed) hanya untuk pengembangan lokal; produksi hanya Google.
    if (app()->environment('local', 'testing')) {
        Route::post('/login', [AuthenticatedSessionController::class, 'store'])->name('login.demo');
    }
});

Route::middleware('auth')->group(function () {
    Route::post('/logout', [AuthenticatedSessionController::class, 'destroy'])->name('logout');

    Route::get('/', Denah::class);
    Route::get('/denah', Denah::class)->name('denah');
    Route::get('/transaksi', Transaksi::class)->name('transaksi');
    Route::get('/ruang', RuangDaftar::class)->name('ruang');
    Route::get('/ruang/aturan', AturanAlokasi::class)->name('aturan-alokasi');
    Route::get('/ruang/{room}', Ruang::class)->name('ruang.detail');
    Route::get('/zakat', Zakat::class)->name('zakat');
});
