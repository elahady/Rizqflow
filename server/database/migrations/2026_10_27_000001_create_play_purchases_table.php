<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Pembelian Google Play yang sudah diverifikasi server (Tahap 9, docs/server-sync.md "Pembelian
     * Pro dan Sync"). Satu baris = satu token pembelian yang pernah lolos verifikasi ke Play dan
     * dikaitkan ke satu akun; `users.plans` diturunkan dari baris-baris ini oleh
     * `App\Play\PlanSynchronizer`, tidak pernah dari data kiriman klien.
     *
     * `token_hash` (sha256 token) UNIQUE adalah penjaga terakhir aturan "satu token hanya bisa
     * diklaim satu akun" terhadap permintaan yang berbalapan. Token aslinya disimpan terenkripsi
     * (cast `encrypted`) karena Play Developer API dan perintah `play:reconcile` butuh token utuh
     * untuk verifikasi ulang; token tidak pernah masuk log atau respons.
     */
    public function up(): void
    {
        Schema::create('play_purchases', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();
            $table->string('plan', 20);        // 'pro' | 'sync' (App\Support\Plan)
            $table->string('product_id', 100); // 'rizqflow_pro' | 'rizqflow_sync'
            $table->char('token_hash', 64)->unique();
            $table->text('purchase_token');    // terenkripsi (APP_KEY)
            $table->string('status', 20);      // App\Play\PurchaseStatus, bukan teks bebas dari klien
            $table->timestamp('expires_at')->nullable();
            $table->boolean('auto_renewing')->nullable();
            $table->boolean('acknowledged')->nullable();
            $table->timestamp('last_verified_at');
            // Percobaan verifikasi ulang terakhir oleh play:reconcile (berhasil, dilewati, atau gagal):
            // hanya untuk mengatur giliran, supaya baris yang terus dilewati tidak menutup antrean.
            $table->timestamp('last_checked_at')->nullable();
            $table->timestamps();

            $table->index(['user_id', 'plan']);
            $table->index(['status', 'expires_at']); // pemilihan baris untuk play:reconcile
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('play_purchases');
    }
};
