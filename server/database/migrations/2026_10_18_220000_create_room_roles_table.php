<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Sistem per peran (Pro, S32): modul opsional pada satu ruang (ruang selain Memberi), mirip
     * modul Memberi — bukan tipe ruang baru, jadi disimpan di tabel sendiri, bukan kolom di rooms.
     * Satu ruang paling banyak satu peran (unique room_id). Memasang/mengubah butuh Pro; melepas
     * selalu boleh. `dca_last_recorded` (bulan, tersimpan sebagai tanggal awal bulan) menandai DCA
     * bulan ini sudah dicatat — padanan sederhana "Selesai/Jatuh tempo/Akan datang" tanpa perlu
     * tabel riwayat terpisah, karena "Catat sekarang" hanya sekali per bulan per ruang.
     */
    public function up(): void
    {
        Schema::create('room_roles', function (Blueprint $table) {
            $table->id();
            $table->foreignId('room_id')->unique()->constrained()->cascadeOnDelete();
            $table->string('kind'); // trader | investor
            // Trader: batas risiko per trade = capital_minor x risk_bp / 10000.
            $table->unsignedBigInteger('capital_minor')->nullable();
            $table->unsignedInteger('risk_bp')->nullable();
            // Investor: jadwal DCA bulanan, dicatat sebagai pengeluaran biasa di kategori ini.
            $table->unsignedBigInteger('dca_amount_minor')->nullable();
            $table->unsignedTinyInteger('dca_day_of_month')->nullable();
            $table->foreignId('dca_account_id')->nullable()->constrained('accounts')->nullOnDelete();
            $table->foreignId('dca_category_id')->nullable()->constrained('categories')->nullOnDelete();
            $table->date('dca_last_recorded')->nullable();
            $table->timestamps();
        });
    }

    /**
     * Reverse the migrations.
     */
    public function down(): void
    {
        Schema::dropIfExists('room_roles');
    }
};
