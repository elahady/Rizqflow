<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Rekap zakat tahunan (docs/konsep.md "Desain web baru", 2026-10-24): dua kolom opsional pada
     * pengeluaran zakat (Memberi › Zakat mal), yaitu lembaga penerima dan nomor bukti setor, untuk
     * keperluan SPT (UU 23/2011 Pasal 22). Bukti setornya sendiri tidak disimpan.
     */
    public function up(): void
    {
        Schema::table('transactions', function (Blueprint $table) {
            $table->string('recipient_institution', 100)->nullable()->after('note');
            $table->string('receipt_number', 60)->nullable()->after('recipient_institution');
        });
    }

    public function down(): void
    {
        Schema::table('transactions', function (Blueprint $table) {
            $table->dropColumn(['recipient_institution', 'receipt_number']);
        });
    }
};
