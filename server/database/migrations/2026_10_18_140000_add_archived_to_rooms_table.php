<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Ruang yang diarsipkan (S10) tidak tampil di Denah dan tidak menerima alokasi baru,
     * tapi riwayat transaksinya tetap ada — sama seperti arsip akun/kategori Android.
     */
    public function up(): void
    {
        Schema::table('rooms', function (Blueprint $table) {
            $table->boolean('archived')->default(false)->after('sort_order');
        });
    }

    public function down(): void
    {
        Schema::table('rooms', function (Blueprint $table) {
            $table->dropColumn('archived');
        });
    }
};
