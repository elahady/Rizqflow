<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Waktu push dan pull per cakupan untuk "Riwayat sinkron" (`GET /api/sync/history`, bidang
     * `scopes`). ADITIF: tidak ada tabel atau kolom lama yang diubah. Sebelumnya server hanya mencatat
     * waktu per perangkat (`sync_devices.last_seen_at`, `MAX(created_at)` log).
     *
     * Cakupan disimpan di kolom `room_id`: string kosong = cakupan pribadi (`a`), selain itu id ruang
     * bersama (`r:<roomId>`). String kosong, bukan NULL, supaya indeks unik juga berlaku di MySQL
     * (pola yang sama dengan `sync_record_floors.scope`). Hanya metadata yang memang sudah terlihat
     * server (akun, perangkat, ruang, kapan); tidak ada isi rekaman, jumlah baris, atau id rekaman.
     */
    public function up(): void
    {
        Schema::create('sync_scope_activity', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();
            $table->string('device_id', 100);
            $table->string('room_id', 100)->default(''); // '' = pribadi
            $table->timestamp('last_push_at')->nullable();
            $table->timestamp('last_pull_at')->nullable();
            $table->timestamps();

            $table->unique(['user_id', 'device_id', 'room_id'], 'sync_activity_key_unique');
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('sync_scope_activity');
    }
};
