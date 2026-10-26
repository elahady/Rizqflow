<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Pemilik ruang yang dibagi (keputusan pemilik 2026-10-27, roadmap.md Tahap 9 "Keluarkan anggota"):
     * pembuat ruang (anggota pertama yang tercatat untuk sebuah room_id) jadi pemilik default dengan
     * hak mengeluarkan anggota lain; anggota lain hanya pencatat. Lihat RoomMember::tambahAnggota.
     *
     * Kolom baru ini aditif dan default-nya `false` supaya aman untuk baris yang sudah ada. Backfill di
     * bawah hanya MENGISI kolom baru itu (tidak menghapus atau mengubah kolom lama): untuk setiap
     * room_id, baris dengan `id` terkecil dianggap pemilik. `id` auto-increment mengikuti urutan
     * insert, sedangkan `created_at` diisi saat insert yang sama, jadi urutan keduanya sama; dipakai
     * `MIN(id)` supaya kueri tetap portabel (MySQL dan SQLite) tanpa fungsi jendela. Ruang yang
     * sudah tidak punya baris anggota tidak terpengaruh.
     */
    public function up(): void
    {
        Schema::table('room_members', function (Blueprint $table) {
            $table->boolean('is_owner')->default(false)->after('room_id');
        });

        // Derived table wajib: MySQL menolak UPDATE yang subquery-nya membaca tabel target langsung.
        DB::statement(<<<'SQL'
            UPDATE room_members
            SET is_owner = 1
            WHERE id IN (
                SELECT first_member_id FROM (
                    SELECT MIN(id) AS first_member_id FROM room_members GROUP BY room_id
                ) AS first_members
            )
        SQL);
    }

    public function down(): void
    {
        Schema::table('room_members', function (Blueprint $table) {
            $table->dropColumn('is_owner');
        });
    }
};
