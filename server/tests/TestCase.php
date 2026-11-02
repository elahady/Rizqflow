<?php

namespace Tests;

use App\Models\RoomInvite;
use App\Models\RoomMember;
use Illuminate\Foundation\Testing\TestCase as BaseTestCase;

abstract class TestCase extends BaseTestCase
{
    /**
     * Isi badan `join` yang sah untuk [roomId]: bila ruang sudah punya pemilik, daftarkan undangan baru
     * (sekali pakai, belum kedaluwarsa) atas nama pemilik itu dan kembalikan `invite_hash`-nya; bila ruang
     * kosong, tidak ada undangan yang dibutuhkan (penggabung pertama jadi pemilik). Dipakai tes yang hanya
     * butuh "anggota kedua sudah bergabung secara sah" tanpa menguji alur undangan itu sendiri.
     *
     * @return array{invite_hash?: string}
     */
    protected function undangan(string $roomId): array
    {
        $pemilik = RoomMember::where('room_id', $roomId)->where('is_owner', true)->value('user_id');
        if ($pemilik === null) {
            return [];
        }

        $hash = bin2hex(random_bytes(32));
        RoomInvite::create([
            'room_id' => $roomId,
            'invite_hash' => $hash,
            'issued_by' => $pemilik,
            'expires_at' => now()->addDays(RoomInvite::LIFETIME_DAYS),
        ]);

        return ['invite_hash' => $hash];
    }
}
