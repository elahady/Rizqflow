<?php

namespace App\Play;

use Carbon\CarbonInterface;

/**
 * Status internal sebuah pembelian, dipetakan dari jawaban Play (bukan dari klien atau isi
 * notifikasi). Satu-satunya tempat yang memutuskan "berhak atau tidak" untuk jalur pembelian.
 */
enum PurchaseStatus: string
{
    /** Produk sekali beli: dibeli dan belum dikembalikan dananya. */
    case Purchased = 'purchased';
    /** Produk sekali beli: dibatalkan atau dikembalikan dananya (final). */
    case Refunded = 'refunded';
    /** Pembayaran tertunda (produk maupun langganan): belum membuka apa pun. */
    case Pending = 'pending';
    case Active = 'active';
    case GracePeriod = 'grace_period';
    /** Dibatalkan pengguna tetapi masa aksesnya belum habis. */
    case Canceled = 'canceled';
    case OnHold = 'on_hold';
    case Paused = 'paused';
    /** Kedaluwarsa, atau pembelian tertunda yang dibatalkan (final). */
    case Expired = 'expired';

    /**
     * Berhak atau tidak pada waktu $now. Langganan ACTIVE, IN_GRACE_PERIOD, dan CANCELED hanya
     * berhak selama $now masih sebelum expiryTime; ON_HOLD, PAUSED, EXPIRED, PENDING tidak.
     */
    public function entitles(?CarbonInterface $expiresAt, CarbonInterface $now): bool
    {
        return match ($this) {
            self::Purchased => true,
            self::Active, self::GracePeriod, self::Canceled => $expiresAt !== null && $now->lt($expiresAt),
            self::Refunded, self::Pending, self::OnHold, self::Paused, self::Expired => false,
        };
    }

    /**
     * Tidak bisa berubah lagi tanpa token baru: tidak perlu diverifikasi ulang, dan token yang
     * sudah final saat diklaim tidak dicatat.
     */
    public function isFinal(): bool
    {
        return $this === self::Expired || $this === self::Refunded;
    }
}
