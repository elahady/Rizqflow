<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Kursor pull per perangkat (Tahap 2 sync-desain.md bagian G/D), dasar retensi/kedaluwarsa kursor
     * (Tahap 7: perangkat tak terlihat lama -> 410 cursor_expired, belum dikerjakan di tahap ini).
     * Hanya dicatat bila klien mengirim `device_id` di pull; klien lama (tanpa device_id) tidak
     * tercatat di sini, tidak memengaruhi pull-nya sendiri.
     */
    public function up(): void
    {
        Schema::create('sync_devices', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();
            $table->string('device_id', 100);
            $table->unsignedBigInteger('after_id')->default(0); // kursor pull terakhir yang diketahui server
            $table->timestamp('last_seen_at')->nullable();
            $table->timestamps();

            $table->unique(['user_id', 'device_id']);
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('sync_devices');
    }
};
