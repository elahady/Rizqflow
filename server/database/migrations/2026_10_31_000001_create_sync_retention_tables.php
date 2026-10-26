<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Retensi, kuota, dan pengerasan sync (Tahap 7 sync-desain.md bagian I, keputusan #6 dan #12).
     *
     * Semua aditif, tidak ada kolom atau tabel lama yang diubah atau dihapus:
     * - `sync_quotas`: penghitung pemakaian per akun (baris dan byte payload), supaya kuota tidak
     *   perlu menjumlahkan seluruh payload tiap push. Diisi malas saat push pertama akun (lihat SyncQuota).
     * - `sync_compaction_state`: satu baris untuk seluruh server. `tombstone_watermark_id` adalah id
     *   tombstone terbesar yang sudah dihapus fisik oleh sync:compact; pull dengan kursor di bawahnya
     *   dijawab 410 cursor_expired. Juga mencatat kapan kompaksi terakhir jalan (Riwayat sinkron).
     * - indeks `(user_id, record_id)` pada sync_log_entries untuk mencari versi lain satu rekaman.
     */
    public function up(): void
    {
        Schema::create('sync_quotas', function (Blueprint $table) {
            $table->foreignId('user_id')->primary()->constrained()->cascadeOnDelete();
            $table->unsignedBigInteger('row_count')->default(0);
            $table->unsignedBigInteger('payload_bytes')->default(0);
            $table->timestamps();
        });

        Schema::create('sync_compaction_state', function (Blueprint $table) {
            $table->unsignedTinyInteger('id')->primary(); // selalu 1: satu baris untuk seluruh server
            $table->unsignedBigInteger('tombstone_watermark_id')->default(0);
            $table->unsignedBigInteger('last_removed_versions')->default(0);
            $table->unsignedBigInteger('last_removed_tombstones')->default(0);
            $table->timestamp('last_run_at')->nullable();
        });

        Schema::table('sync_log_entries', function (Blueprint $table) {
            $table->index(['user_id', 'record_id'], 'sync_log_key_idx');
        });
    }

    public function down(): void
    {
        Schema::table('sync_log_entries', function (Blueprint $table) {
            $table->dropIndex('sync_log_key_idx');
        });
        Schema::dropIfExists('sync_compaction_state');
        Schema::dropIfExists('sync_quotas');
    }
};
