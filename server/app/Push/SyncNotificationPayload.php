<?php

namespace App\Push;

use InvalidArgumentException;

/**
 * Padanan server untuk kontrak `SyncNotificationPayload` di :domain Android
 * (domain/src/main/kotlin/.../domain/sync/SyncNotificationPayload.kt): data push HANYA dua kunci,
 * `roomId` dan `kind`, dengan `kind` salah satu dari SyncNotificationKind. Tidak ada nominal, nama,
 * catatan, akun, atau id pencatat. Aplikasi mengambil dan mendekripsi isi sendiri lewat pull.
 *
 * Satu-satunya jalan membangun data push; jangan menambah kunci lain di sini tanpa mengubah kontrak
 * di :domain lebih dulu (dan tetap tanpa data keuangan).
 */
final class SyncNotificationPayload
{
    public const KIND_NEW_RECORD = 'NEW_RECORD';

    public const KIND_MEMBER_REMOVED = 'MEMBER_REMOVED';

    public const KIND_ROOM_KEY_ROTATED = 'ROOM_KEY_ROTATED';

    private const KINDS = [self::KIND_NEW_RECORD, self::KIND_MEMBER_REMOVED, self::KIND_ROOM_KEY_ROTATED];

    private function __construct(public readonly string $roomId, public readonly string $kind) {}

    public static function of(string $roomId, string $kind): self
    {
        if (trim($roomId) === '' || ! in_array($kind, self::KINDS, true)) {
            throw new InvalidArgumentException('Payload notifikasi sync tidak valid');
        }

        return new self($roomId, $kind);
    }

    public static function newRecord(string $roomId): self
    {
        return self::of($roomId, self::KIND_NEW_RECORD);
    }

    /** @return array{roomId: string, kind: string} */
    public function toData(): array
    {
        return ['roomId' => $this->roomId, 'kind' => $this->kind];
    }
}
