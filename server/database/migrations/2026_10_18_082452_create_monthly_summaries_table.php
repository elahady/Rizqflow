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
        Schema::create('monthly_summaries', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();
            $table->date('month'); // tanggal 1 bulan terkait
            $table->unsignedBigInteger('income_minor')->default(0); // rezeki bulan ini
            $table->unsignedBigInteger('unallocated_minor')->default(0); // belum dialirkan
            $table->timestamps();

            $table->unique(['user_id', 'month']);
        });
    }

    /**
     * Reverse the migrations.
     */
    public function down(): void
    {
        Schema::dropIfExists('monthly_summaries');
    }
};
