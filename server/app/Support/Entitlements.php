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
}
