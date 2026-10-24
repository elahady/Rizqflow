<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * "Yang bisa dihitung tidak disimpan" (docs/model-data.md): jatah dan terpakai ruang per bulan,
     * rezeki bulan ini, dan yang belum dialirkan kini diturunkan dari transaksi dan potret alokasi
     * (App\Support\MonthLedger). Kolom statis `rooms.budget_minor`/`used_minor` dan tabel
     * `monthly_summaries` dari scaffold awal hanya berisi angka seed, jadi aman dihapus.
     */
    public function up(): void
    {
        Schema::table('rooms', function (Blueprint $table) {
            $table->dropColumn(['budget_minor', 'used_minor']);
        });

        Schema::dropIfExists('monthly_summaries');
    }

    /** Mengembalikan bentuk lama (kosong); angkanya tidak bisa dipulihkan karena memang turunan. */
    public function down(): void
    {
        Schema::table('rooms', function (Blueprint $table) {
            $table->unsignedBigInteger('budget_minor')->default(0);
            $table->unsignedBigInteger('used_minor')->default(0);
        });

        Schema::create('monthly_summaries', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();
            $table->date('month');
            $table->unsignedBigInteger('income_minor')->default(0);
            $table->unsignedBigInteger('unallocated_minor')->default(0);
            $table->timestamps();

            $table->unique(['user_id', 'month']);
        });
    }
};
