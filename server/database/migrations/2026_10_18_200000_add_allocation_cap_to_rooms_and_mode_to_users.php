<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Aturan alokasi lanjutan (Pro): mode berlaku untuk seluruh ruleset sekaligus ('persentase'
     * dasar atau 'lanjutan', porting AllocationMode di :domain), jadi disimpan di users bukan di
     * tiap ruang. Prioritas ruang memakai `rooms.sort_order` yang sudah ada (sama seperti urutan
     * S10 dipakai mode persentase) — tidak perlu kolom prioritas baru. `allocation_cap_minor`
     * null berarti tak terbatas (menampung seluruh sisa), cocok untuk ruang prioritas terakhir.
     */
    public function up(): void
    {
        Schema::table('users', function (Blueprint $table) {
            $table->string('allocation_mode')->default('persentase')->after('plans');
        });

        Schema::table('rooms', function (Blueprint $table) {
            $table->unsignedBigInteger('allocation_cap_minor')->nullable()->after('allocation_percent');
        });
    }

    /**
     * Reverse the migrations.
     */
    public function down(): void
    {
        Schema::table('users', function (Blueprint $table) {
            $table->dropColumn('allocation_mode');
        });

        Schema::table('rooms', function (Blueprint $table) {
            $table->dropColumn('allocation_cap_minor');
        });
    }
};
