<?php

namespace App\Support;

/**
 * Satu-satunya tempat yang menjawab "boleh atau tidak" (lihat CLAUDE.md: "semua penguncian fitur
 * lewat satu lapisan entitlement"). Halaman dan logika tidak pernah memeriksa paket sendiri;
 * mereka bertanya ke sini lewat User::entitlements(), sehingga model harga bisa berubah tanpa
 * membongkar kode. Porting dari PlanEntitlements di :domain; belum ada sumber pembayaran
 * sungguhan di web (menyusul Tahap 15/12), jadi $plans untuk sekarang diisi lewat seed atau tinker.
 */
class Entitlements
{
    /** Batas ruang gratis (disetujui pemilik 2026-09-21, sama seperti Android). Akun tidak dibatasi. */
    public const FREE_ROOM_LIMIT = 5;

    /** @param list<Plan> $plans */
    public function __construct(private readonly array $plans = []) {}

    public function isEnabled(Feature $feature): bool
    {
        return in_array($feature->requiredPlan(), $this->plans, true);
    }

    /** Jumlah ruang maksimum, atau null bila tak terbatas. */
    public function roomLimit(): ?int
    {
        return $this->isEnabled(Feature::UnlimitedRooms) ? null : self::FREE_ROOM_LIMIT;
    }

    public function canAddRoom(int $currentRoomCount): bool
    {
        $limit = $this->roomLimit();

        return $limit === null || $currentRoomCount < $limit;
    }

    /**
     * Akses ruang keluarga bersama (join, daftar anggota, push/pull baris ruang itu): dibuka oleh Sync milik
     * PEMILIK ruang (peran-anggota.md I-8/E.1 butir 4, monetisasi.md "satu langganan Sync mencakup keluarga
     * sampai 5 anggota, bukan per orang"), BUKAN Sync milik peminta sendiri. [$ownerEntitlements] null berarti
     * ruang itu belum punya anggota sama sekali (ruang baru): [$this] (Entitlements peminta) dipakai sebagai
     * gantinya, karena peminta akan jadi pemilik begitu `join`/push pertamanya tercatat (RoomMember::tambahAnggota).
     */
    public function allowsSharedRoom(?self $ownerEntitlements): bool
    {
        return ($ownerEntitlements ?? $this)->isEnabled(Feature::SharedFamilyRoom);
    }
}
