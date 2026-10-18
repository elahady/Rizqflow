<?php

namespace Tests\Unit;

use App\Support\Entitlements;
use App\Support\Feature;
use App\Support\Plan;
use PHPUnit\Framework\TestCase;

/** Porting dari PlanEntitlements di :domain — logikanya murni, lihat Entitlements.kt. */
class EntitlementsTest extends TestCase
{
    public function test_without_any_plan_only_free_features_are_enabled(): void
    {
        $entitlements = new Entitlements([]);

        $this->assertFalse($entitlements->isEnabled(Feature::AdvancedAllocationRules));
        $this->assertFalse($entitlements->isEnabled(Feature::MultiZakatProfile));
        $this->assertSame(Entitlements::FREE_ROOM_LIMIT, $entitlements->roomLimit());
    }

    public function test_pro_unlocks_pro_features_but_not_sync_features(): void
    {
        $entitlements = new Entitlements([Plan::Pro]);

        $this->assertTrue($entitlements->isEnabled(Feature::AdvancedAllocationRules));
        $this->assertTrue($entitlements->isEnabled(Feature::MultiZakatProfile));
        $this->assertTrue($entitlements->isEnabled(Feature::RoleSystems));
        $this->assertFalse($entitlements->isEnabled(Feature::EncryptedSync));
        $this->assertFalse($entitlements->isEnabled(Feature::SharedFamilyRoom));
    }

    public function test_sync_unlocks_sync_features_but_not_pro_features(): void
    {
        $entitlements = new Entitlements([Plan::Sync]);

        $this->assertTrue($entitlements->isEnabled(Feature::EncryptedSync));
        $this->assertTrue($entitlements->isEnabled(Feature::SharedFamilyRoom));
        $this->assertFalse($entitlements->isEnabled(Feature::AdvancedAllocationRules));
    }

    public function test_unlimited_rooms_feature_removes_the_room_limit(): void
    {
        $entitlements = new Entitlements([Plan::Pro]);

        $this->assertNull($entitlements->roomLimit());
        $this->assertTrue($entitlements->canAddRoom(1000));
    }

    public function test_free_plan_can_add_a_room_only_below_the_limit(): void
    {
        $entitlements = new Entitlements([]);

        $this->assertTrue($entitlements->canAddRoom(4));
        $this->assertFalse($entitlements->canAddRoom(5));
        $this->assertFalse($entitlements->canAddRoom(6));
    }
}
