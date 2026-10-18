<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Multi-profil harta zakat (Pro, S14): profil pertama selalu gratis, profil tambahan
     * butuh Feature::MultiZakatProfile. Profil yang diarsipkan tidak terkunci saat turun paket
     * (datanya tetap ada), hanya disembunyikan dari daftar chip aktif.
     */
    public function up(): void
    {
        Schema::table('zakat_profiles', function (Blueprint $table) {
            $table->boolean('archived')->default(false)->after('status');
        });
    }

    /**
     * Reverse the migrations.
     */
    public function down(): void
    {
        Schema::table('zakat_profiles', function (Blueprint $table) {
            $table->dropColumn('archived');
        });
    }
};
