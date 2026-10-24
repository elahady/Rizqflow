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
    ) {}

    public function entitles(CarbonInterface $now): bool
    {
        return $this->status->entitles($this->expiresAt, $now);
    }
}
