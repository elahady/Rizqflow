<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Tagihan dan cicilan (S34): berbeda dari transaksi berulang (recurring_rules) karena
     * pembayarannya tidak pernah otomatis — pengguna mengetuk Bayar, lalu BillPayService mencatat
     * pengeluaran dan memajukan `next_due`/`paid_count`. `start_date` adalah jangkar jadwal yang
     * sama seperti recurring_rules, dihitung ulang lewat App\Support\RecurrenceSchedule.
     */
    public function up(): void
    {
        Schema::create('bills', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();
            $table->string('name');
            $table->unsignedBigInteger('amount_minor');
            $table->foreignId('account_id')->constrained()->cascadeOnDelete();
            $table->foreignId('room_id')->constrained()->cascadeOnDelete();
            $table->foreignId('category_id')->constrained()->cascadeOnDelete();
            $table->string('note')->nullable();
            $table->string('frequency')->nullable(); // null = sekali bayar; harian | mingguan | bulanan
            $table->date('start_date');
            $table->date('next_due');
            $table->unsignedInteger('total_installments')->nullable();
            $table->unsignedInteger('paid_count')->default(0);
            $table->boolean('active')->default(true);
            $table->timestamps();
        });

        Schema::table('transactions', function (Blueprint $table) {
            $table->foreignId('bill_id')->nullable()->after('recurring_rule_id')->constrained()->nullOnDelete();
        });
    }

    /**
     * Reverse the migrations.
     */
    public function down(): void
    {
        Schema::table('transactions', function (Blueprint $table) {
            $table->dropConstrainedForeignId('bill_id');
        });

        Schema::dropIfExists('bills');
    }
};
