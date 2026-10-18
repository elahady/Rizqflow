<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * `account` sejak scaffold awal hanya teks bebas (lihat migrasi transactions). Sekarang akun
     * punya tabel sendiri (lihat create_accounts_table) jadi transaksi menunjuk ke sana, bukan
     * menyimpan nama sebagai string. Aman diganti langsung karena data yang ada masih contoh/seed.
     */
    public function up(): void
    {
        Schema::table('transactions', function (Blueprint $table) {
            $table->dropColumn('account');
            $table->foreignId('account_id')->nullable()->after('category_id')->constrained()->nullOnDelete();
        });
    }

    public function down(): void
    {
        Schema::table('transactions', function (Blueprint $table) {
            $table->dropConstrainedForeignId('account_id');
            $table->string('account')->nullable();
        });
    }
};
