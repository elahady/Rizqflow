<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * "Lantai per rekaman" (Tahap 9, pengerasan kompaksi): saat sync:compact menghapus fisik sebuah
     * tombstone, `changed_at_ms`-nya dicatat di sini. Push berikutnya untuk rekaman yang sama dengan
     * `changed_at_ms` <= lantai ditolak, supaya versi lama yang terlambat tidak menghidupkan kembali
     * rekaman yang sudah dihapus (tombstone-nya sudah tidak ada untuk mengalahkannya).
     *
     * `scope` = room_id untuk ruang bersama, string kosong untuk data pribadi (bukan NULL, supaya
     * indeks unik juga berlaku di MySQL). Hanya kolom luar yang memang sudah terlihat server; tidak
     * ada isi rekaman.
     */
    public function up(): void
    {
        Schema::create('sync_record_floors', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();
            $table->string('scope', 100);
            $table->string('record_id', 100);
            $table->unsignedBigInteger('floor_ms');
            $table->timestamps();

            $table->unique(['user_id', 'scope', 'record_id'], 'sync_floor_key_unique');
            $table->index(['scope', 'record_id'], 'sync_floor_scope_idx');
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('sync_record_floors');
    }
};
