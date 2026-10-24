<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Keanggotaan ruang yang dibagi (Tahap 9, roadmap.md "Ruang keluarga bersama"): siapa boleh
     * melihat baris `sync_log_entries` milik sebuah `room_id`. `room_id` di sini string bebas dari
     * Android (sama seperti `sync_log_entries.room_id`), tidak terhubung ke tabel `rooms` milik web
     * (itu entitas per-user terpisah untuk dashboard Tahap 14). Baris dibuat otomatis saat anggota
     * pertama kali push ke sebuah room_id, atau eksplisit lewat endpoint join saat scan undangan QR.
     */
    public function up(): void
    {
        Schema::create('room_members', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();
            $table->string('room_id');
            $table->timestamp('created_at')->useCurrent();

            $table->unique(['user_id', 'room_id']);
            $table->index('room_id'); // filter pull: semua anggota sebuah ruang
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('room_members');
    }
};
