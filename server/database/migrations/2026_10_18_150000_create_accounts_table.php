<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Dompet/rekening tempat uang berada (tunai, bank, e-wallet), versi web yang disederhanakan
     * dari tabel `account` Android (docs/model-data.md). Tidak dibatasi jumlahnya (keputusan
     * 2026-09-24, lihat monetisasi.md).
     */
    public function up(): void
    {
        Schema::create('accounts', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();
            $table->string('name');
            $table->string('type'); // tunai | bank | e_wallet
            $table->unsignedBigInteger('balance_minor')->default(0); // rupiah penuh, bukan floating point
            $table->boolean('archived')->default(false);
            $table->unsignedSmallInteger('sort_order')->default(0);
            $table->timestamps();
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('accounts');
    }
};
