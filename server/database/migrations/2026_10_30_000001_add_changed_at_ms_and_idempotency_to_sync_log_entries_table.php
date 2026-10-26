<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Tahap 2 sync-desain.md bagian G: `changed_at` (`timestamp`) berhenti di presisi detik dan di
     * tahun 2038 (MySQL); `changed_at_ms` BIGINT menambah presisi milidetik yang dibutuhkan LWW
     * (floor +1 ms, SyncClock). Kolom lama dibiarkan (dibaca klien lama, dan beberapa kueri lama
     * masih memakainya) — aditif, tidak ada data yang hilang.
     *
     * Indeks `(user_id, device_id, record_id, changed_at_ms, deleted)` mendukung idempotensi push
     * (SyncController::push mencari baris yang sudah ada sebelum menulis baris baru); bukan UNIQUE
     * supaya baris lama (changed_at_ms null, sebelum kolom ini ada) tidak pernah bertabrakan.
     */
    public function up(): void
    {
        Schema::table('sync_log_entries', function (Blueprint $table) {
            $table->unsignedBigInteger('changed_at_ms')->nullable()->after('changed_at');
            $table->index(['user_id', 'device_id', 'record_id', 'changed_at_ms', 'deleted'], 'sync_log_idempotency_idx');
        });
    }

    public function down(): void
    {
        Schema::table('sync_log_entries', function (Blueprint $table) {
            $table->dropIndex('sync_log_idempotency_idx');
            $table->dropColumn('changed_at_ms');
        });
    }
};
