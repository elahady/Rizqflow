<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Kekayaan bersih sepanjang waktu (Pro, web-only — tidak ada padanan mobile). Web tidak punya
     * riwayat saldo akun atau harta zakat tersimpan (keduanya angka "sekarang" yang ditimpa setiap
     * berubah), jadi tidak bisa direkonstruksi mundur ke masa lalu. App\Support\NetWorthSnapshotter
     * mencatat satu baris per hari per user (unik lewat [user_id, snapshot_date]) setiap Denah
     * dibuka dan belum ada baris untuk hari itu — sama seperti RecurringRunner, tanpa penjadwal
     * latar belakang. Riwayat baru mulai terbentuk sejak fitur ini dipasang, bukan sejak akun dibuat.
     */
    public function up(): void
    {
        Schema::create('net_worth_snapshots', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();
            $table->date('snapshot_date');
            $table->unsignedBigInteger('accounts_minor');
            $table->unsignedBigInteger('zakat_wealth_minor');
            $table->unsignedBigInteger('total_minor');
            $table->timestamps();

            $table->unique(['user_id', 'snapshot_date']);
        });
    }

    /**
     * Reverse the migrations.
     */
    public function down(): void
    {
        Schema::dropIfExists('net_worth_snapshots');
    }
};
