<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Favorit untuk catat kilat (S24), versi web disederhanakan dari tabel `quick_favorite`
     * Android (docs/model-data.md): tanpa room_id dan use_count/last_used_at. Hanya untuk
     * pengeluaran. Paling banyak 6 per user, ditegakkan di Kelola::addFavorite(), bukan di skema.
     */
    public function up(): void
    {
        Schema::create('quick_entries', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();
            $table->string('name');
            $table->unsignedBigInteger('amount_minor');
            $table->foreignId('category_id')->constrained()->cascadeOnDelete();
            $table->foreignId('account_id')->constrained()->cascadeOnDelete();
            $table->timestamps();
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('quick_entries');
    }
};
