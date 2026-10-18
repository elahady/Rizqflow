<?php

use App\Http\Controllers\Auth\AuthenticatedSessionController;
use App\Http\Controllers\Auth\GoogleLoginController;
use App\Http\Controllers\TransactionExportController;
use App\Livewire\AturanAlokasi;
use App\Livewire\BandingkanPeriode;
use App\Livewire\Denah;
use App\Livewire\GrafikTren;
use App\Livewire\KekayaanBersih;
use App\Livewire\Kelola;
use App\Livewire\Laporan;
use App\Livewire\Ruang;
use App\Livewire\RuangDaftar;
use App\Livewire\Tagihan;
use App\Livewire\Transaksi;
use App\Livewire\TransaksiBerulang;
use App\Livewire\TransaksiDetail;
use App\Livewire\UtangPiutang;
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
    Route::get('/transaksi/ekspor', [TransactionExportController::class, 'csv'])->name('transaksi.ekspor');
    Route::get('/transaksi/berulang', TransaksiBerulang::class)->name('transaksi.berulang');
    Route::get('/tagihan', Tagihan::class)->name('tagihan');
    Route::get('/utang-piutang', UtangPiutang::class)->name('utang-piutang');
    Route::get('/transaksi/{transaction}', TransaksiDetail::class)->name('transaksi.detail');
    Route::get('/ruang', RuangDaftar::class)->name('ruang');
    Route::get('/ruang/aturan', AturanAlokasi::class)->name('aturan-alokasi');
    Route::get('/ruang/{room}', Ruang::class)->name('ruang.detail');
    Route::get('/zakat', Zakat::class)->name('zakat');
    Route::get('/akun', Kelola::class)->name('kelola');
    Route::get('/laporan', Laporan::class)->name('laporan');
    Route::get('/laporan/tren', GrafikTren::class)->name('laporan.tren');
    Route::get('/laporan/kekayaan-bersih', KekayaanBersih::class)->name('laporan.kekayaan-bersih');
    Route::get('/laporan/bandingkan', BandingkanPeriode::class)->name('laporan.bandingkan');
});
