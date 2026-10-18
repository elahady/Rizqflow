<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Lapisan entitlement (lihat App\Support\Entitlements, porting dari Entitlements di :domain):
     * paket yang dimiliki pengguna, dicatat di sini sebagai array ('pro', 'sync'). Belum ada
     * pembayaran sungguhan di web (Google Play Billing untuk Pro masih Tahap 8; checkout web
     * belum diputuskan) — kolom ini baru diberi isi lewat seed atau tinker untuk sekarang.
     */
    public function up(): void
    {
        Schema::table('users', function (Blueprint $table) {
            $table->json('plans')->nullable()->after('google_sub');
        });
    }

    /**
     * Reverse the migrations.
     */
    public function down(): void
    {
        Schema::table('users', function (Blueprint $table) {
            $table->dropColumn('plans');
        });
    }
};
