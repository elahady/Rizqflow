<?php

namespace App\Support;

use App\Models\RoomMember;
use App\Models\User;

/**
 * Resolusi pemilik ruang bersama untuk Entitlements::allowsSharedRoom (peran-anggota.md I-8). Terpisah dari
 * Entitlements sendiri supaya kelas itu tetap bebas database (Entitlements dibangun dari daftar paket, bukan
 * query) — hanya dua pemanggil (RoomMembershipController, SyncController) yang butuh resolusi ini.
 */
final class SharedRoomOwner
{
    /** Entitlements pemilik ruang [roomId], atau null bila ruang itu belum punya anggota sama sekali (ruang baru). */
    public static function entitlementsOf(string $roomId): ?Entitlements
    {
        $ownerId = RoomMember::where('room_id', $roomId)->where('is_owner', true)->value('user_id');
        if ($ownerId === null) {
            return null;
        }

        return User::find($ownerId)?->entitlements();
    }
}
