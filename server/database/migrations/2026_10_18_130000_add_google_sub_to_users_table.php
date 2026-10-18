<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Web masuk dengan akun Google yang sama dengan Android (keputusan 2026-10-18). Identitasnya
     * `sub` dari ID token Google, bukan email (email bisa berganti). Sandi jadi opsional: hanya
     * akun demo lokal yang memakainya.
     */
    public function up(): void
    {
        Schema::table('users', function (Blueprint $table) {
            $table->string('google_sub')->nullable()->unique()->after('email');
            $table->string('password')->nullable()->change();
        });
    }

    public function down(): void
    {
        Schema::table('users', function (Blueprint $table) {
            $table->dropUnique(['google_sub']);
            $table->dropColumn('google_sub');
            $table->string('password')->nullable(false)->change();
        });
    }
};
