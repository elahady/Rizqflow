<?php

use App\Http\Controllers\Auth\AuthenticatedSessionController;
use App\Http\Controllers\Auth\EmailVerificationController;
use App\Http\Controllers\Auth\GoogleLoginController;
use App\Http\Controllers\Auth\PasswordResetController;
use App\Http\Controllers\Auth\RegisteredUserController;
use App\Http\Controllers\ReportPdfController;
use App\Http\Controllers\TransactionExportController;
use App\Http\Controllers\ZakatRekapController;
use App\Livewire\AlirkanRezeki;
use App\Livewire\Aliran;
use App\Livewire\AturanAlokasi;
use App\Livewire\BandingkanPeriode;
use App\Livewire\Denah;
use App\Livewire\GrafikTren;
use App\Livewire\HeatmapPencatatan;
use App\Livewire\ImporCsv;
use App\Livewire\ImporEkspor;
use App\Livewire\Jadwal;
use App\Livewire\KekayaanBersih;
use App\Livewire\Kelola;
use App\Livewire\LaporanTahunan;
use App\Livewire\Profil;
use App\Livewire\ProyeksiCashflow;
use App\Livewire\Ruang;
use App\Livewire\Ringkasan;
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
    Route::post('/login', [AuthenticatedSessionController::class, 'store'])->name('login.attempt');
    Route::post('/auth/google', [GoogleLoginController::class, 'store'])->name('login.google');

    // Daftar, verifikasi email, lupa/atur ulang kata sandi: butuh pengirim email. Selama saklar
    // rizqflow.email_codes_enabled mati, middleware email-codes mengalihkan ke Masuk dengan pesan ramah.
    Route::middleware('email-codes')->group(function () {
        Route::get('/register', [RegisteredUserController::class, 'create'])->name('register');
        Route::post('/register', [RegisteredUserController::class, 'store'])->name('register.store');

        Route::get('/verifikasi-email', [EmailVerificationController::class, 'show'])->name('verification.show');
        Route::post('/verifikasi-email', [EmailVerificationController::class, 'verify'])->name('verification.verify');
        Route::post('/verifikasi-email/kirim-ulang', [EmailVerificationController::class, 'resend'])->name('verification.resend');

        Route::get('/lupa-kata-sandi', [PasswordResetController::class, 'forgot'])->name('password.forgot');
        Route::post('/lupa-kata-sandi', [PasswordResetController::class, 'sendCode'])->name('password.send');
        Route::get('/reset-kata-sandi', [PasswordResetController::class, 'reset'])->name('password.reset');
        Route::post('/reset-kata-sandi', [PasswordResetController::class, 'update'])->name('password.update');
    });
});

Route::middleware('auth')->group(function () {
    Route::post('/logout', [AuthenticatedSessionController::class, 'destroy'])->name('logout');

    Route::get('/', Denah::class);
    Route::get('/denah', Denah::class)->name('denah');
    Route::get('/ringkasan', Ringkasan::class)->name('ringkasan');
    Route::get('/transaksi', Transaksi::class)->name('transaksi');
    Route::get('/transaksi/ekspor', [TransactionExportController::class, 'csv'])->name('transaksi.ekspor');
    Route::get('/transaksi/berulang', TransaksiBerulang::class)->name('transaksi.berulang');
    // Harus sebelum /transaksi/{transaction}, kalau tidak "rezeki" dibaca sebagai id transaksi.
    Route::get('/transaksi/rezeki', AlirkanRezeki::class)->name('transaksi.rezeki');
    Route::get('/jadwal', Jadwal::class)->name('jadwal');
    Route::get('/tagihan', Tagihan::class)->name('tagihan');
    Route::get('/utang-piutang', UtangPiutang::class)->name('utang-piutang');
    Route::get('/transaksi/{transaction}', TransaksiDetail::class)->name('transaksi.detail');
    Route::get('/ruang', RuangDaftar::class)->name('ruang');
    Route::get('/ruang/aturan', AturanAlokasi::class)->name('aturan-alokasi');
    Route::get('/ruang/{room}', Ruang::class)->name('ruang.detail');
    Route::get('/zakat', Zakat::class)->name('zakat');
    Route::get('/zakat/rekap/{year}/pdf', [ZakatRekapController::class, 'pdf'])->where('year', '[0-9]{4}')->name('zakat.rekap.pdf');
    Route::get('/zakat/rekap/{year}/csv', [ZakatRekapController::class, 'csv'])->where('year', '[0-9]{4}')->name('zakat.rekap.csv');
    Route::get('/akun', Kelola::class)->name('kelola');
    // Analisa › Aliran menggantikan hub kartu Laporan; nama route tetap 'laporan' supaya tautan lama berfungsi.
    Route::get('/laporan', Aliran::class)->name('laporan');
    Route::get('/laporan/tren', GrafikTren::class)->name('laporan.tren');
    Route::get('/laporan/kekayaan-bersih', KekayaanBersih::class)->name('laporan.kekayaan-bersih');
    Route::get('/laporan/bandingkan', BandingkanPeriode::class)->name('laporan.bandingkan');
    Route::get('/laporan/bandingkan/pdf', [ReportPdfController::class, 'bandingkanPeriode'])->name('laporan.bandingkan.pdf');
    Route::get('/laporan/bulanan/pdf', [ReportPdfController::class, 'bulanan'])->name('laporan.bulanan.pdf');
    Route::get('/laporan/tahunan', LaporanTahunan::class)->name('laporan.tahunan');
    Route::get('/laporan/tahunan/pdf', [ReportPdfController::class, 'tahunan'])->name('laporan.tahunan.pdf');
    Route::get('/laporan/cashflow', ProyeksiCashflow::class)->name('laporan.cashflow');
    Route::get('/laporan/heatmap', HeatmapPencatatan::class)->name('laporan.heatmap');
    Route::get('/laporan/impor-csv', ImporCsv::class)->name('laporan.impor-csv');
    Route::get('/impor-ekspor', ImporEkspor::class)->name('impor-ekspor');
    Route::get('/profil', Profil::class)->name('profil');
});
