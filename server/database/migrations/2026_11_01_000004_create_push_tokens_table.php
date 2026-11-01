<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Token FCM perangkat untuk notifikasi push generik "ada catatan baru" (Tahap 9/13). Satu baris per
     * (akun, device_id); satu token FCM hanya milik satu baris (`token_hash` unik), jadi bila akun lain
     * masuk di ponsel yang sama, token pindah ke akun itu dan akun lama berhenti menerima kabar.
     *
     * `token` disimpan polos (server harus mengirimnya ke FCM); bukan data keuangan, tetapi tetap
     * disembunyikan dari serialisasi dan tidak pernah dicatat ke log.
     */
    public function up(): void
    {
        Schema::create('push_tokens', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();
            $table->string('device_id', 100);
            $table->text('token');
            $table->char('token_hash', 64)->unique();
            $table->timestamps();

            $table->unique(['user_id', 'device_id'], 'push_tokens_user_device_unique');
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('push_tokens');
    }
};
