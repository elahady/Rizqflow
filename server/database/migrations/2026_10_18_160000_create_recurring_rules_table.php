<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Aturan transaksi berulang (S33), hanya pemasukan dan pengeluaran — transfer belum ada di
     * model data transaksi web sama sekali (lihat migrasi transactions). `next_due` maju setiap
     * kemunculan berhasil dicatat lewat App\Support\RecurringRunner, dihitung dari `start_date`
     * (bukan dari kemunculan sebelumnya) mengikuti RecurrenceSchedule di :domain.
     */
    public function up(): void
    {
        Schema::create('recurring_rules', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();
            $table->string('type'); // pemasukan | pengeluaran
            $table->unsignedBigInteger('amount_minor');
            $table->foreignId('account_id')->constrained()->cascadeOnDelete();
            $table->foreignId('room_id')->nullable()->constrained()->nullOnDelete();
            $table->foreignId('category_id')->nullable()->constrained()->nullOnDelete();
            $table->string('source')->nullable(); // sumber pemasukan, bukan dipakai pengeluaran
            $table->string('note')->nullable();
            $table->string('frequency'); // harian | mingguan | bulanan
            $table->date('start_date');
            $table->date('next_due');
            $table->date('end_date')->nullable();
            $table->boolean('active')->default(true);
            $table->timestamps();
        });

        Schema::table('transactions', function (Blueprint $table) {
            $table->foreignId('recurring_rule_id')->nullable()->after('account_id')->constrained()->nullOnDelete();
            $table->unique(['recurring_rule_id', 'occurred_on']);
        });
    }

    /**
     * Reverse the migrations.
     */
    public function down(): void
    {
        Schema::table('transactions', function (Blueprint $table) {
            $table->dropUnique(['recurring_rule_id', 'occurred_on']);
            $table->dropConstrainedForeignId('recurring_rule_id');
        });

        Schema::dropIfExists('recurring_rules');
    }
};
