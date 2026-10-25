<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Satu salinan kunci akun yang dibungkus kode pemulihan 24 kata (Tahap 13, docs/server-sync.md
     * "Model kunci"): supaya perangkat baru bisa memulihkan kunci dengan kode itu. `wrapped_key`
     * adalah blob BURAM yang dibuat di klien (AES-256-GCM, kunci PBKDF2 dari frasa 24 kata, versi 1);
     * server tidak punya kuncinya, tidak pernah melihat kode pemulihan atau kunci terbaca, dan tidak
     * bisa membukanya.
     *
     * Penyimpanan: byte hasil dekode base64 di kolom `binary` (BLOB di SQLite dan MySQL), bukan
     * string base64 di kolom text. Alasannya: 25% lebih kecil, tidak ada urusan charset/collation
     * (MySQL membandingkan dan memotong spasi ujung pada text, tidak pada BLOB), dan kolom tidak
     * pernah dikira teks. BLOB MySQL muat 65.535 byte; batas aplikasi 8192 byte jauh di bawahnya.
     * Kolom ini tidak diindeks (hanya `user_id` yang unik), jadi batas panjang indeks tidak relevan.
     *
     * TIDAK ada kolom lain yang bisa membocorkan: tanpa frasa, petunjuk, atau hash kode. Satu akun
     * satu baris (`user_id` unik); membuat ulang kode pemulihan = mengganti baris itu. Hapus akun ikut
     * menghapus baris (cascade).
     */
    public function up(): void
    {
        Schema::create('recovery_keys', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->unique()->constrained()->cascadeOnDelete();
            // 1 = BackupCrypto AES-256-GCM + PBKDF2 (lihat App\Models\RecoveryKey::KNOWN_VERSIONS).
            $table->unsignedSmallInteger('version');
            $table->binary('wrapped_key');
            $table->timestamps();
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('recovery_keys');
    }
};
