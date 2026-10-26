<?php

namespace App\Play;

use Carbon\CarbonImmutable;
use Carbon\CarbonInterface;

/**
 * Hasil verifikasi satu token ke Play, sudah dipetakan ke tipe internal. `productId` adalah
 * produk yang DIKEMBALIKAN Play (untuk langganan: baris item yang cocok dengan produk yang
 * ditanyakan, atau item pertama bila tak ada yang cocok); pemanggil wajib membandingkannya dengan
 * produk yang diklaim.
 */
final class PlayVerification
{
    public function __construct(
        public readonly string $productId,
        public readonly PurchaseStatus $status,
        public readonly ?CarbonImmutable $expiresAt = null,
        public readonly ?bool $autoRenewing = null,
        public readonly ?bool $acknowledged = null,
        /**
         * Token pendahulu untuk token ini, bila Play sendiri melaporkannya (Tahap 9, "Ikuti token
         * langganan pengganti ke pemilik baris lama"): `linkedPurchaseToken` (ganti base plan, atau
         * re-signup langganan yang dibatalkan tapi belum lapsed) atau
         * `outOfAppPurchaseContext.expiredPurchaseToken` (berlangganan lagi dari Play Store setelah
         * langganan lama sepenuhnya kedaluwarsa). Keduanya hanya ada di jawaban `subscriptionsv2.get`
         * (langganan); Play tidak punya field serupa untuk produk sekali beli, jadi selalu null dari
         * verifyOneTime(). Dipakai PlayPurchaseChainResolver untuk mengikuti token baru yang belum
         * pernah diklaim ke pemilik baris pendahulu — TIDAK PERNAH dipercaya dari klien, hanya dari
         * jawaban Play API lewat kelas ini.
         */
        public readonly ?string $predecessorToken = null,
    ) {}

    public function entitles(CarbonInterface $now): bool
    {
        return $this->status->entitles($this->expiresAt, $now);
    }
}
