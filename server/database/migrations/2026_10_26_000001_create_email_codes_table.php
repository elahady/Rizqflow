<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Kode 6 angka lewat email untuk verifikasi daftar dan atur ulang kata sandi web. Yang disimpan
     * hanya HMAC-nya (lihat App\Auth\EmailCodeService), tidak pernah kode aslinya.
     *
     * Sekalian mengisi email_verified_at akun lama: sebelum masuk email+sandi ada, satu-satunya akun
     * selain Google adalah akun demo seed, jadi semuanya sudah dianggap terverifikasi. Tanpa ini
     * akun Google yang menambah kata sandi di Profil akan tertolak sebagai "belum terverifikasi".
     */
    public function up(): void
    {
        Schema::create('email_codes', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();
            $table->string('purpose', 16);
            $table->string('code_hash');
            $table->timestamp('expires_at');
            $table->unsignedTinyInteger('attempts')->default(0);
            $table->timestamp('consumed_at')->nullable();
            $table->timestamps();

            $table->index(['user_id', 'purpose']);
        });

        DB::table('users')->whereNull('email_verified_at')->update(['email_verified_at' => DB::raw('created_at')]);
        DB::table('users')->whereNull('email_verified_at')->update(['email_verified_at' => now()]);
    }

    public function down(): void
    {
        Schema::dropIfExists('email_codes');
    }
};
