<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Undangan terdaftar untuk ruang yang dibagi (sync-desain.md bagian F "Rencana distribusi keyset
     * otomatis", langkah 1; keputusan pemilik 2026-11-02 dan 2026-11-03). Server hanya menyimpan
     * `invite_hash` = SHA-256 dari HKDF(member_secret, "rizqflow-invite") dalam heksadesimal; rahasia
     * anggotanya tidak pernah sampai ke server, jadi hash ini tidak membuka apa pun. Fungsinya dua:
     * (1) `join` oleh non-anggota wajib membawa hash undangan yang sah (sekali pakai, kedaluwarsa 7
     * hari), sehingga bekas anggota tidak bisa mendaftar ulang sendiri; (2) pemilik tahu undangan mana
     * dipakai anggota mana (`used_by_user_id`) untuk mengikat rahasia ke anggota dan mengirim keyset baru
     * (key_grant) saat kunci ruang dirotasi.
     */
    public function up(): void
    {
        Schema::create('room_invites', function (Blueprint $table) {
            $table->id();
            $table->string('room_id', 100);
            $table->string('invite_hash', 64);
            $table->foreignId('issued_by')->constrained('users')->cascadeOnDelete();
            // cascade, bukan nullOnDelete: akun pemakai dihapus tidak boleh menghidupkan lagi undangan sekali pakai.
            $table->foreignId('used_by_user_id')->nullable()->constrained('users')->cascadeOnDelete();
            $table->timestamp('expires_at');
            $table->timestamp('created_at')->useCurrent();

            $table->unique(['room_id', 'invite_hash']);
            $table->index('room_id');
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('room_invites');
    }
};
