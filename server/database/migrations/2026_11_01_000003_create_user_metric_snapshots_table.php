<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Metrik pengguna anonim (Tahap 9): satu baris angka agregat per hari, ditulis `metrik:pengguna
     * --simpan`. Sengaja TANPA kolom user_id, email, nama, atau data keuangan apa pun: hanya hitungan.
     */
    public function up(): void
    {
        Schema::create('user_metric_snapshots', function (Blueprint $table) {
            $table->id();
            $table->date('tanggal')->unique();
            $table->unsignedInteger('pengguna_total');
            $table->unsignedInteger('pengguna_pro');
            $table->unsignedInteger('pengguna_sync');
            $table->timestamps();
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('user_metric_snapshots');
    }
};
