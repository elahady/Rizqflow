<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Rantai token langganan pengganti (Tahap 9, "Ikuti token langganan pengganti ke pemilik baris
     * lama"): saat Play melaporkan `linkedPurchaseToken` atau
     * `outOfAppPurchaseContext.expiredPurchaseToken` ketika memverifikasi token BARU, baris token
     * LAMA (baris ini) dicatat sudah diganti oleh token baru itu lewat kolom aditif ini.
     *
     * Murni jejak audit (siapa digantikan oleh siapa); App\Play\PlayPurchaseChainResolver TIDAK
     * PERNAH membaca kolom ini untuk memutuskan pemilik token baru — keputusan itu selalu dari
     * `token_hash` baris LAMA yang ditemukan lewat token pendahulu yang dilaporkan Play sendiri
     * (`PlayVerification::$predecessorToken`), bukan sebaliknya. Nullable dan tanpa unique: satu
     * baris lama hanya pernah digantikan oleh satu token (ditulis sekali lewat
     * `PlayPurchase::markSupersededBy`), tetapi tidak ada baris lain yang bergantung padanya.
     */
    public function up(): void
    {
        Schema::table('play_purchases', function (Blueprint $table) {
            $table->char('superseded_by_token_hash', 64)->nullable()->after('token_hash');
        });
    }

    public function down(): void
    {
        Schema::table('play_purchases', function (Blueprint $table) {
            $table->dropColumn('superseded_by_token_hash');
        });
    }
};
