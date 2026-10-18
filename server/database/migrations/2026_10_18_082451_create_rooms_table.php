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
        Schema::create('rooms', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();
            $table->string('name');
            $table->string('icon')->default('ruang');
            $table->string('type'); // menunaikan | menumbuhkan | mencukupi
            $table->unsignedTinyInteger('slot')->default(1); // identitas warna, lihat docs/design/README.md
            $table->unsignedTinyInteger('allocation_percent')->nullable();
            $table->unsignedBigInteger('budget_minor')->default(0); // jatah bulan ini, rupiah penuh (bukan floating point)
            $table->unsignedBigInteger('used_minor')->default(0);
            $table->unsignedTinyInteger('sort_order')->default(0);
            $table->timestamps();
        });
    }

    /**
     * Reverse the migrations.
     */
    public function down(): void
    {
        Schema::dropIfExists('rooms');
    }
};
