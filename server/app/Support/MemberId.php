<?php

namespace App\Support;

/**
 * Id anggota buram untuk satu ruang (peran-anggota.md bagian E.1). Rumus yang sama persis dipakai
 * `author` di balasan pull sync (Api\SyncController) supaya klien bisa mengaitkan baris `SHARED`
 * dengan anggota di `GET members`, tanpa server pernah membuka id pengguna internal ke klien.
 */
final class MemberId
{
    public static function dari(?string $roomId, int $userId): string
    {
        return substr(hash('sha256', 'rizqflow-sync-author|'.($roomId ?? 'a').'|'.$userId), 0, 16);
    }
}
