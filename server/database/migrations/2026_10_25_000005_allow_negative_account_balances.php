<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Saldo akun boleh negatif: aplikasi mencatat dan tidak memblokir, jadi pengeluaran boleh melebihi
     * saldo (LedgerServiceTest Android, "pengeluaran boleh melebihi saldo"). Kolomnya dulu unsigned,
     * yang di MySQL produksi membuat decrement di bawah nol gagal ("BIGINT UNSIGNED value is out of
     * range") begitu web mencatat lewat App\Support\Ledger. Total di net_worth_snapshots ikut, karena
     * menjumlah saldo akun. Di SQLite (dev dan tes) tipe unsigned tidak ada, jadi tidak ada yang diubah
     * dan tabel tidak perlu dibangun ulang.
     */
    public function up(): void
    {
        if (Schema::getConnection()->getDriverName() === 'sqlite') {
            return;
        }

        Schema::table('accounts', function (Blueprint $table) {
            $table->bigInteger('balance_minor')->default(0)->change();
        });

        Schema::table('net_worth_snapshots', function (Blueprint $table) {
            $table->bigInteger('accounts_minor')->change();
            $table->bigInteger('total_minor')->change();
        });
    }

    /** Gagal di MySQL bila sudah ada saldo negatif; samakan dulu saldonya sebelum membalik. */
    public function down(): void
    {
        if (Schema::getConnection()->getDriverName() === 'sqlite') {
            return;
        }

        Schema::table('accounts', function (Blueprint $table) {
            $table->unsignedBigInteger('balance_minor')->default(0)->change();
        });

        Schema::table('net_worth_snapshots', function (Blueprint $table) {
            $table->unsignedBigInteger('accounts_minor')->change();
            $table->unsignedBigInteger('total_minor')->change();
        });
    }
};
