<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Nama perangkat yang bisa dikenali pengguna (mis. "Samsung SM-S911B"), dikirim klien Android saat
     * masuk dan memutar token (Tahap 14, daftar perangkat). Kolom `name` tetap `android:<device_id>`
     * (pengenal acak untuk mencabut token lama di perangkat yang sama); kolom ini hanya untuk tampilan.
     * Nullable: token lama dan klien lama tanpa nama tetap sah, tampilannya jatuh ke label bawaan.
     */
    public function up(): void
    {
        Schema::table('personal_access_tokens', function (Blueprint $table) {
            $table->string('device_name', 100)->nullable()->after('name');
        });
    }

    public function down(): void
    {
        Schema::table('personal_access_tokens', function (Blueprint $table) {
            $table->dropColumn('device_name');
        });
    }
};
