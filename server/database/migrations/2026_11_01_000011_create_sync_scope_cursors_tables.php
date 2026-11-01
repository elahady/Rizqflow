<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Pull per cakupan (sync-desain.md bagian G): `GET /api/sync/pull?scope=a|r&room_id=`. ADITIF: tidak
     * ada tabel atau kolom lama yang diubah; klien lama (pull tanpa `scope`) tetap memakai `sync_devices`
     * dan watermark global `sync_compaction_state`.
     *
     * Cakupan disimpan di kolom `room_id`: string kosong = pribadi (`a`), selain itu id ruang bersama
     * (`r:<roomId>`); string kosong, bukan NULL, supaya indeks unik juga berlaku di MySQL.
     *
     * - `sync_device_cursors`: kursor pull per (akun, perangkat, cakupan). Dibaca `sync:compact`
     *   (tombstone baru boleh dihapus bila kursor cakupannya sudah melewatinya) dan dibuang
     *   `sync:prune-stale-devices`.
     * - `sync_scope_watermarks`: id tombstone terbesar yang sudah dihapus fisik DI cakupan itu, supaya
     *   410 `cursor_expired` hanya mengenai cakupan yang tombstone-nya benar-benar dikompaksi
     *   (watermark global mengena semua cakupan). `scope_key` = `a|<userId>` atau `r|<roomId>`;
     *   `user_id` hanya diisi untuk cakupan pribadi (supaya ikut terhapus bersama akun).
     * - dua indeks log `(room_id, id)` dan `(user_id, room_id, id)` untuk pull per cakupan.
     */
    public function up(): void
    {
        Schema::create('sync_device_cursors', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();
            $table->string('device_id', 100);
            $table->string('room_id', 100)->default(''); // '' = pribadi
            $table->unsignedBigInteger('after_id')->default(0);
            $table->timestamp('last_seen_at')->nullable();
            $table->timestamps();

            $table->unique(['user_id', 'device_id', 'room_id'], 'sync_cursor_key_unique');
            $table->index(['room_id', 'after_id'], 'sync_cursor_scope_idx');
        });

        Schema::create('sync_scope_watermarks', function (Blueprint $table) {
            $table->id();
            $table->string('scope_key', 120)->unique();
            $table->foreignId('user_id')->nullable()->constrained()->cascadeOnDelete();
            $table->unsignedBigInteger('watermark_id')->default(0);
            $table->timestamps();
        });

        Schema::table('sync_log_entries', function (Blueprint $table) {
            $table->index(['room_id', 'id'], 'sync_log_room_idx');
            $table->index(['user_id', 'room_id', 'id'], 'sync_log_user_room_idx');
        });
    }

    public function down(): void
    {
        Schema::table('sync_log_entries', function (Blueprint $table) {
            $table->dropIndex('sync_log_user_room_idx');
            $table->dropIndex('sync_log_room_idx');
        });
        Schema::dropIfExists('sync_scope_watermarks');
        Schema::dropIfExists('sync_device_cursors');
    }
};
