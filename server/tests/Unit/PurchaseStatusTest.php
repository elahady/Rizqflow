<?php

namespace Tests\Unit;

use App\Play\PurchaseStatus;
use Carbon\CarbonImmutable;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

class PurchaseStatusTest extends TestCase
{
    /** @return array<string, array{PurchaseStatus, bool, bool}> status, expiry di masa depan?, berhak? */
    public static function entitlementMatrix(): array
    {
        return [
            'dibeli (sekali beli) berhak' => [PurchaseStatus::Purchased, false, true],
            'aktif sebelum expiry berhak' => [PurchaseStatus::Active, true, true],
            'aktif sesudah expiry tidak berhak' => [PurchaseStatus::Active, false, false],
            'masa tenggang sebelum expiry berhak' => [PurchaseStatus::GracePeriod, true, true],
            'masa tenggang sesudah expiry tidak berhak' => [PurchaseStatus::GracePeriod, false, false],
            'dibatalkan tapi belum lewat expiry berhak' => [PurchaseStatus::Canceled, true, true],
            'dibatalkan dan sudah lewat expiry tidak berhak' => [PurchaseStatus::Canceled, false, false],
            'ditahan tidak berhak walau expiry masa depan' => [PurchaseStatus::OnHold, true, false],
            'dijeda tidak berhak walau expiry masa depan' => [PurchaseStatus::Paused, true, false],
            'kedaluwarsa tidak berhak' => [PurchaseStatus::Expired, true, false],
            'tertunda tidak berhak' => [PurchaseStatus::Pending, true, false],
            'dikembalikan dananya tidak berhak' => [PurchaseStatus::Refunded, true, false],
        ];
    }

    #[DataProvider('entitlementMatrix')]
    public function test_entitlement_matrix(PurchaseStatus $status, bool $expiryInFuture, bool $expected): void
    {
        $now = CarbonImmutable::parse('2026-10-24 12:00:00');
        $expiresAt = $now->addDays($expiryInFuture ? 5 : -5);

        $this->assertSame($expected, $status->entitles($expiresAt, $now));
    }

    public function test_subscription_states_without_expiry_never_entitle(): void
    {
        $now = CarbonImmutable::parse('2026-10-24 12:00:00');

        foreach ([PurchaseStatus::Active, PurchaseStatus::GracePeriod, PurchaseStatus::Canceled] as $status) {
            $this->assertFalse($status->entitles(null, $now), $status->value);
        }
    }

    public function test_expiry_exactly_now_is_no_longer_entitled(): void
    {
        $now = CarbonImmutable::parse('2026-10-24 12:00:00');

        $this->assertFalse(PurchaseStatus::Active->entitles($now, $now));
    }

    public function test_only_expired_and_refunded_are_final(): void
    {
        $final = array_filter(PurchaseStatus::cases(), fn (PurchaseStatus $status) => $status->isFinal());

        $this->assertEqualsCanonicalizing([PurchaseStatus::Expired, PurchaseStatus::Refunded], array_values($final));
    }
}
