<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Potret alokasi satu pemasukan, mengikuti tabel `allocation_entry` di docs/model-data.md:
     * "Sejarah tidak ikut berubah saat aturan berubah". Satu baris untuk setiap ruang yang
     * persentasenya lebih dari nol saat pemasukan dicatat (termasuk yang nominalnya 0 karena nominal
     * kecil), supaya ubah nominal nanti dihitung ulang dengan persentase saat itu, bukan aturan
     * sekarang. Jatah ruang per bulan = jumlah `amount_minor` dari pemasukan bulan itu (diturunkan di
     * App\Support\MonthLedger, tidak disimpan). Ditulis lewat App\Support\Ledger.
     */
    public function up(): void
    {
        Schema::create('allocation_entries', function (Blueprint $table) {
            $table->id();
            $table->foreignId('transaction_id')->constrained()->cascadeOnDelete();
            $table->foreignId('room_id')->constrained()->cascadeOnDelete();
            $table->unsignedSmallInteger('share_bp'); // basis point saat dicatat, 0 sampai 10.000
            $table->unsignedBigInteger('amount_minor'); // hasil AllocationEngine, rupiah penuh, sudah termasuk sisa pembulatan
            $table->timestamps();

            $table->unique(['transaction_id', 'room_id']);
            $table->index('room_id');
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('allocation_entries');
    }
};
