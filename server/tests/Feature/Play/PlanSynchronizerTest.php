<?php

namespace Tests\Feature\Play;

use App\Models\User;
use App\Play\PlanSynchronizer;
use App\Play\PlayProduct;
use App\Play\PurchaseStatus;
use App\Support\Plan;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Tests\Support\CreatesPlayPurchases;
use Tests\TestCase;

/** Satu-satunya penulis users.plans untuk jalur pembelian: diturunkan dari baris play_purchases. */
class PlanSynchronizerTest extends TestCase
{
    use CreatesPlayPurchases, RefreshDatabase;

    /** @param list<Plan>|null $plans paket yang dinilai ulang; bawaan semua paket */
    private function sync(User $user, ?array $plans = null): User
    {
        app(PlanSynchronizer::class)->sync($user, $plans ?? Plan::cases());

        return $user->fresh();
    }

    public function test_entitled_row_grants_the_plan_in_fixed_order(): void
    {
        $user = User::factory()->create();
        $this->makePurchase($user, 'tok-sync');
        $this->makePurchase($user, 'tok-pro', PlayProduct::Pro);

        $this->assertSame(['pro', 'sync'], $this->sync($user)->plans);
    }

    public function test_a_plan_without_any_purchase_row_is_left_alone(): void
    {
        $seeded = User::factory()->create(['plans' => ['sync', 'pro']]);
        $none = User::factory()->create(['plans' => null]);

        $this->assertSame(['sync', 'pro'], $this->sync($seeded)->plans, 'urutan dan isi seed tidak disentuh');
        $this->assertNull($this->sync($none)->plans);
    }

    public function test_expired_row_without_notification_is_revoked_by_its_stored_expiry(): void
    {
        $user = User::factory()->create(['plans' => ['sync']]);
        $this->makePurchase($user, 'tok', status: PurchaseStatus::Active, expiresAt: now()->subMinute());

        $this->assertSame([], $this->sync($user)->plans);
    }

    public function test_a_plan_not_asked_for_is_never_touched_even_if_its_stored_row_looks_stale(): void
    {
        // Baris Sync tampak kedaluwarsa menurut data tersimpan, tetapi Sync tidak diverifikasi pada panggilan
        // ini (hanya Pro). Tanpa jawaban Play untuk Sync, paket Sync tidak boleh dicabut.
        $user = User::factory()->create(['plans' => ['pro', 'sync']]);
        $this->makePurchase($user, 'tok-sync', status: PurchaseStatus::Active, expiresAt: now()->subHour());
        $this->makePurchase($user, 'tok-pro', PlayProduct::Pro, PurchaseStatus::Refunded);

        $this->assertSame(['sync'], $this->sync($user, [Plan::Pro])->plans, 'hanya Pro yang dinilai ulang');
        $this->assertSame([], $this->sync($user, [Plan::Sync])->plans, 'dinilai ulang bila diminta, Sync memang sudah lewat');
    }

    public function test_an_empty_plan_list_changes_nothing(): void
    {
        $user = User::factory()->create(['plans' => ['sync']]);
        $this->makePurchase($user, 'tok', status: PurchaseStatus::Expired);

        $this->assertSame(['sync'], $this->sync($user, [])->plans);
    }

    public function test_any_entitled_row_keeps_the_plan_even_if_another_row_is_not(): void
    {
        $user = User::factory()->create(['plans' => ['sync']]);
        $this->makePurchase($user, 'tok-lama', status: PurchaseStatus::Expired);
        $this->makePurchase($user, 'tok-baru', status: PurchaseStatus::Canceled, expiresAt: now()->addDays(2));
        $this->makePurchase($user, 'tok-hold', status: PurchaseStatus::OnHold);

        $this->assertSame(['sync'], $this->sync($user)->plans);
    }

    public function test_only_the_plan_with_rows_is_recomputed(): void
    {
        $user = User::factory()->create(['plans' => ['pro', 'sync']]);
        $this->makePurchase($user, 'tok-pro', PlayProduct::Pro, PurchaseStatus::Refunded);

        $this->assertSame(['sync'], $this->sync($user)->plans);
    }

    public function test_other_users_rows_do_not_affect_this_user(): void
    {
        $user = User::factory()->create(['plans' => ['pro']]);
        $other = User::factory()->create();
        $this->makePurchase($other, 'tok-other', PlayProduct::Pro, PurchaseStatus::Refunded);

        $this->assertSame(['pro'], $this->sync($user)->plans);
    }

    public function test_the_passed_instance_is_refreshed(): void
    {
        $user = User::factory()->create();
        $this->makePurchase($user, 'tok');

        app(PlanSynchronizer::class)->sync($user, [Plan::Sync]);

        $this->assertSame(['sync'], $user->plans);
        $this->assertFalse($user->isDirty());
    }

    public function test_deleted_user_is_a_no_op(): void
    {
        $user = User::factory()->create();
        $user->delete();

        app(PlanSynchronizer::class)->sync($user, Plan::cases());

        $this->addToAssertionCount(1); // tidak melempar
    }

    public function test_plans_of_normalises_order_and_drops_unknown_values(): void
    {
        $user = new User(['plans' => ['sync', 'pro', 'sync', 'tak-dikenal']]);

        $this->assertSame(['pro', 'sync'], PlanSynchronizer::plansOf($user));
        $this->assertSame([], PlanSynchronizer::plansOf(new User));
    }
}
