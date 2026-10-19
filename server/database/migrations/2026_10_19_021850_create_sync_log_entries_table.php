<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Log sinkronisasi append-only (Tahap 9, server-sync.md "Model konflik sinkronisasi"): tiap
     * perubahan di perangkat Android jadi satu baris baru di sini, tidak pernah UPDATE/DELETE —
     * server cuma menyimpan dan mengirim ulang payload terenkripsi, tanpa membacanya.
     */
    public function up(): void
    {
        Schema::create('sync_log_entries', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();
            $table->string('room_id')->nullable(); // RoomId domain Android; null untuk rekaman lintas ruang (mis. Account). Bukan FK ke tabel rooms web.
            $table->string('record_type', 60); // label bebas dari Android (mis. "transaction"), tidak divalidasi di sini
            $table->string('record_id', 100); // pengenal rekaman di domain Android, tetap sama antar baris untuk rekaman yang sama
            $table->string('device_id', 100);
            $table->text('payload'); // ciphertext terenkode base64 dari aplikasi; buram bagi server
            $table->boolean('deleted')->default(false); // baris tanda hapus, bukan menghapus baris lama
            $table->timestamp('changed_at'); // jam perangkat saat berubah; dasar last-write-wins, bukan waktu server
            $table->timestamp('created_at')->useCurrent(); // waktu server menerima; tanpa updated_at karena baris tidak pernah diubah

            $table->index(['user_id', 'id']); // kursor pull: baris milik user ini setelah id tertentu
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('sync_log_entries');
    }
};
