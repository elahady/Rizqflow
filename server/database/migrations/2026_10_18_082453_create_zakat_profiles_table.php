<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Run the migrations.
     */
    public function up(): void
    {
        Schema::create('zakat_profiles', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();
            $table->string('name')->default('Utama');
            $table->unsignedBigInteger('net_wealth_minor')->default(0);
            $table->unsignedBigInteger('nisab_minor')->default(0);
            // Label tampilan saja, bukan tanggal Hijriyah terkonversi: metode kalender Hijriyah
            // untuk haul belum diputuskan (lihat docs/konsep.md), jangan asumsikan di sini.
            $table->string('haul_started_label')->nullable();
            $table->string('haul_due_label')->nullable();
            $table->unsignedSmallInteger('haul_day')->nullable();
            $table->unsignedSmallInteger('haul_total_days')->nullable();
            $table->string('status')->default('belum_nisab'); // belum_nisab | haul_berjalan | haul_genap
            $table->timestamps();
        });
    }

    /**
     * Reverse the migrations.
     */
    public function down(): void
    {
        Schema::dropIfExists('zakat_profiles');
    }
};
