<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Tampilan tersimpan (Pro, web-only): kombinasi filter sebuah halaman yang dipanggil ulang lewat
     * tombol "Simpan tampilan" di Analisa › Aliran. Aditif, tidak mengubah tabel yang sudah ada.
     * `filters` menyimpan nilai filter yang sudah dinormalisasi (rentang, bulan, akun, ruang yang
     * disorot); isinya selalu divalidasi ulang saat dipakai, jadi tidak dipercaya begitu saja.
     */
    public function up(): void
    {
        Schema::create('saved_views', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();
            $table->string('page', 20);
            $table->string('name', 60);
            $table->json('filters');
            $table->timestamps();

            $table->index(['user_id', 'page']);
        });
    }

    /**
     * Reverse the migrations.
     */
    public function down(): void
    {
        Schema::dropIfExists('saved_views');
    }
};
