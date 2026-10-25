<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Palet warna aksen web (porting ThemePalette di Android): 'sage' gratis, 'laut'/'pasir'/'anggur'
     * Pro. Disimpan di users supaya ikut ke perangkat lain, bukan localStorage. Palet Pro yang sudah
     * tersimpan tidak dihapus saat paket turun; App\Support\ThemePalette::effective() yang memutuskan
     * palet yang benar-benar tampil.
     */
    public function up(): void
    {
        Schema::table('users', function (Blueprint $table) {
            $table->string('theme_palette', 20)->default('sage')->after('allocation_mode');
        });
    }

    /**
     * Reverse the migrations.
     */
    public function down(): void
    {
        Schema::table('users', function (Blueprint $table) {
            $table->dropColumn('theme_palette');
        });
    }
};
