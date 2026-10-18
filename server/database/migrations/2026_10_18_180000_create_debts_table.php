<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Utang-piutang (S35): berbeda dari Debt di :domain, perpindahan uangnya **tidak** dicatat
     * sebagai baris di `transactions` (tidak ada jenis transaksi pinjaman di model data web, sama
     * seperti transfer) — saldo akun digerakkan langsung oleh App\Livewire\UtangPiutang, supaya
     * tidak ikut terhitung di `Room::categoryBreakdown` atau filter jenis transaksi mana pun.
     * `account_id` di sini menyimpan akun yang saldonya digerakkan saat dicatat (nullable: pinjaman
     * lama yang uangnya sudah bergerak sebelum dicatat tidak menyentuh saldo).
     */
    public function up(): void
    {
        Schema::create('debts', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();
            $table->string('direction'); // piutang | utang
            $table->string('party');
            $table->unsignedBigInteger('principal_minor');
            $table->foreignId('account_id')->nullable()->constrained()->nullOnDelete();
            $table->date('start_date');
            $table->date('due_date')->nullable();
            $table->string('note')->nullable();
            $table->boolean('collectible')->default(true); // hanya bermakna untuk piutang
            $table->timestamps();
        });
    }

    /**
     * Reverse the migrations.
     */
    public function down(): void
    {
        Schema::dropIfExists('debts');
    }
};
