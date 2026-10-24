<?php

namespace Tests\Feature\Play;

use App\Models\PlayPurchase;
use App\Models\User;
use App\Play\PlayProduct;
use App\Play\PlayPurchaseVerifier;
use App\Play\PlayUnavailable;
use App\Play\PurchaseStatus;
use Carbon\CarbonImmutable;
use Illuminate\Console\Scheduling\Schedule;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\DB;
use Tests\Support\CreatesPlayPurchases;
use Tests\Support\FakePlayPurchaseVerifier;
use Tests\TestCase;

/** php artisan play:reconcile: verifikasi ulang langganan yang kedaluwarsa tanpa notifikasi (verifier palsu, tanpa jaringan). */
class PlayReconcileTest extends TestCase
{
    use CreatesPlayPurchases, RefreshDatabase;

    private FakePlayPurchaseVerifier $play;

    protected function setUp(): void
    {
        parent::setUp();

        $this->play = new FakePlayPurchaseVerifier;
        $this->app->instance(PlayPurchaseVerifier::class, $this->play);
    }

    public function test_subscription_past_expiry_without_notification_is_revoked(): void
    {
        $user = User::factory()->create(['plans' => ['sync']]);
        $this->makePurchase($user, 'tok', expiresAt: now()->subDay(), lastVerifiedAt: now()->subDays(31));
        $this->play->respond('tok', FakePlayPurchaseVerifier::sync(PurchaseStatus::Expired, CarbonImmutable::now()->subDay()));

        $this->artisan('play:reconcile')->assertExitCode(0);

        $this->assertSame(PurchaseStatus::Expired, PlayPurchase::sole()->status);
        $this->assertSame([], $user->fresh()->plans);
    }

    public function test_renewed_subscription_is_extended_and_keeps_its_plan(): void
    {
        $user = User::factory()->create(['plans' => ['sync']]);
        $this->makePurchase($user, 'tok', expiresAt: now()->subHour());
        $renewed = CarbonImmutable::now()->addDays(30)->startOfSecond();
        $this->play->respond('tok', FakePlayPurchaseVerifier::sync(PurchaseStatus::Active, $renewed));

        $this->artisan('play:reconcile')->assertExitCode(0);

        $this->assertTrue(PlayPurchase::sole()->expires_at->equalTo($renewed));
        $this->assertSame(['sync'], $user->fresh()->plans);
    }

    public function test_only_due_rows_are_verified(): void
    {
        $user = User::factory()->create();
        $due = fn (string $token) => $this->play->respond($token, FakePlayPurchaseVerifier::sync());
        $this->play->respond('tok-pro', FakePlayPurchaseVerifier::pro());

        // Tidak jatuh tempo: langganan masih jauh dan baru diverifikasi; produk sekali beli baru diverifikasi.
        $this->makePurchase($user, 'tok-segar', expiresAt: now()->addDays(20));
        $this->makePurchase($user, 'tok-pro', PlayProduct::Pro);
        // Jatuh tempo: hampir kedaluwarsa (12 jam), basi (4 hari), belum punya expires_at (pending).
        $this->makePurchase($user, 'tok-hampir', expiresAt: now()->addHours(12));
        $this->makePurchase($user, 'tok-basi', expiresAt: now()->addDays(20), lastVerifiedAt: now()->subDays(4));
        $this->makePurchase($user, 'tok-pending', status: PurchaseStatus::Pending, lastVerifiedAt: now()->subHour());
        foreach (['tok-hampir', 'tok-basi', 'tok-pending'] as $token) {
            $due($token);
        }

        $this->artisan('play:reconcile')->assertExitCode(0);

        $verified = array_map(fn (array $call) => $call[1], $this->play->calls);
        $this->assertEqualsCanonicalizing(['tok-hampir', 'tok-basi', 'tok-pending'], $verified);
    }

    public function test_final_rows_are_never_reverified(): void
    {
        $user = User::factory()->create();
        $this->makePurchase($user, 'tok-expired', status: PurchaseStatus::Expired, expiresAt: now()->subYear(), lastVerifiedAt: now()->subYear());
        $this->makePurchase($user, 'tok-refunded', PlayProduct::Pro, PurchaseStatus::Refunded, lastVerifiedAt: now()->subYear());

        $this->artisan('play:reconcile')->assertExitCode(0);

        $this->assertCount(0, $this->play->calls);
    }

    public function test_stale_pending_and_one_time_purchases_are_checked_on_their_own_schedules(): void
    {
        $user = User::factory()->create();
        $this->makePurchase($user, 'tok-pro-lama', PlayProduct::Pro, lastVerifiedAt: now()->subDays(31));
        $this->makePurchase($user, 'tok-pro-baru', PlayProduct::Pro, lastVerifiedAt: now()->subDays(29));
        $this->makePurchase($user, 'tok-pro-pending', PlayProduct::Pro, PurchaseStatus::Pending, lastVerifiedAt: now()->subDays(2));
        $this->play->respond('tok-pro-lama', FakePlayPurchaseVerifier::pro(PurchaseStatus::Refunded));
        $this->play->respond('tok-pro-pending', FakePlayPurchaseVerifier::pro());
        $user->update(['plans' => ['pro']]);

        $this->artisan('play:reconcile')->assertExitCode(0);

        $verified = array_map(fn (array $call) => $call[1], $this->play->calls);
        $this->assertEqualsCanonicalizing(['tok-pro-lama', 'tok-pro-pending'], $verified);
        $this->assertSame(PurchaseStatus::Refunded, PlayPurchase::where('token_hash', PlayPurchase::hashToken('tok-pro-lama'))->sole()->status);
        // Masih ada baris Pro lain yang berhak (tok-pro-baru dan tok-pro-pending yang kini dibeli): Pro tetap.
        $this->assertSame(['pro'], $user->fresh()->plans);
    }

    public function test_play_failure_changes_nothing_marks_the_run_failed_and_continues_with_other_rows(): void
    {
        $gagal = User::factory()->create(['plans' => ['sync']]);
        $ok = User::factory()->create(['plans' => ['sync']]);
        $this->makePurchase($gagal, 'tok-gagal', expiresAt: now()->subDay(), lastVerifiedAt: now()->subDays(10));
        $this->makePurchase($ok, 'tok-ok', expiresAt: now()->subDay(), lastVerifiedAt: now()->subDays(5));
        $this->play->respond('tok-gagal', new PlayUnavailable);
        $this->play->respond('tok-ok', FakePlayPurchaseVerifier::sync(PurchaseStatus::Expired));

        $this->artisan('play:reconcile')
            ->expectsOutputToContain('gagal 1')
            ->assertExitCode(1);

        $this->assertSame(['sync'], $gagal->fresh()->plans, 'galat Play tidak pernah mencabut');
        $this->assertSame(PurchaseStatus::Active, PlayPurchase::where('token_hash', PlayPurchase::hashToken('tok-gagal'))->sole()->status);
        $this->assertSame([], $ok->fresh()->plans);
    }

    public function test_refreshing_one_row_does_not_revoke_a_sibling_plan_whose_play_call_failed(): void
    {
        $user = User::factory()->create(['plans' => ['pro', 'sync']]);
        // Pro (verifikasi paling tua) diproses lebih dulu dan Play menjawab dikembalikan dananya.
        $this->makePurchase($user, 'tok-pro', PlayProduct::Pro, lastVerifiedAt: now()->subDays(40));
        // Sync: expires_at sudah lewat menurut data tersimpan, tetapi Play gagal untuk baris ini.
        $this->makePurchase($user, 'tok-sync', expiresAt: now()->subHour(), lastVerifiedAt: now()->subDays(5));
        $this->play->respond('tok-pro', FakePlayPurchaseVerifier::pro(PurchaseStatus::Refunded));
        $this->play->respond('tok-sync', new PlayUnavailable);

        $this->artisan('play:reconcile')->expectsOutputToContain('gagal 1')->assertExitCode(1);

        $this->assertSame(['sync'], $user->fresh()->plans, 'Pro dicabut oleh jawaban Play; Sync tak terjawab, jadi tak disentuh');
    }

    public function test_rows_that_keep_being_skipped_do_not_starve_the_rest_of_the_queue(): void
    {
        $user = User::factory()->create();
        // Tiga baris macet: Play selalu menjawab "tak dikenal" (dilewati), dan verifikasinya paling tua.
        foreach (['macet-1', 'macet-2', 'macet-3'] as $i => $token) {
            $this->makePurchase($user, $token, expiresAt: now()->subDays(100), lastVerifiedAt: now()->subDays(50 + $i));
        }
        // Baris sehat yang juga jatuh tempo tetapi verifikasinya lebih baru.
        $healthy = $this->makePurchase($user, 'sehat', expiresAt: now()->subHour(), lastVerifiedAt: now()->subDays(4));
        $renewed = CarbonImmutable::now()->addDays(30)->startOfSecond();
        $this->play->respond('sehat', FakePlayPurchaseVerifier::sync(PurchaseStatus::Active, $renewed));

        $this->artisan('play:reconcile', ['--limit' => 3])->expectsOutputToContain('dilewati 3')->assertExitCode(0);
        $this->assertTrue($healthy->fresh()->expires_at->lt(now()), 'jalan pertama habis untuk baris macet');

        $this->artisan('play:reconcile', ['--limit' => 3])->assertExitCode(0);

        $this->assertTrue($healthy->fresh()->expires_at->equalTo($renewed), 'jalan kedua sampai ke baris sehat');
    }

    public function test_rows_that_fail_every_time_also_rotate_to_the_back_of_the_queue(): void
    {
        $user = User::factory()->create();
        foreach (['gagal-1', 'gagal-2'] as $i => $token) {
            $this->makePurchase($user, $token, expiresAt: now()->subDay(), lastVerifiedAt: now()->subDays(20 + $i));
            $this->play->respond($token, new PlayUnavailable);
        }
        $healthy = $this->makePurchase($user, 'sehat', expiresAt: now()->subHour(), lastVerifiedAt: now()->subDays(4));
        $this->play->respond('sehat', FakePlayPurchaseVerifier::sync());

        $this->artisan('play:reconcile', ['--limit' => 2])->assertExitCode(1);
        $this->assertTrue($healthy->fresh()->expires_at->lt(now()), 'jalan pertama habis untuk baris yang gagal');

        // Jalan kedua: baris sehat (belum pernah dicoba) didahulukan, satu baris gagal ikut kebagian (kode keluar 1).
        $this->artisan('play:reconcile', ['--limit' => 2])->assertExitCode(1);

        $this->assertTrue($healthy->fresh()->expires_at->gt(now()), 'jalan kedua sampai ke baris sehat');
    }

    public function test_due_window_boundaries_are_one_day_for_expiry_and_pending_three_for_stale_subscriptions_thirty_for_one_time(): void
    {
        $this->travelTo(now()->startOfSecond());
        $user = User::factory()->create();
        $far = now()->addDays(20);
        // Tepat di dalam batas: harus jatuh tempo.
        $this->makePurchase($user, 'exp-dalam', expiresAt: now()->addDay()->subMinute());
        $this->makePurchase($user, 'pending-lama', PlayProduct::Pro, PurchaseStatus::Pending, lastVerifiedAt: now()->subDay()->subMinute());
        $this->makePurchase($user, 'basi-lama', expiresAt: $far, lastVerifiedAt: now()->subDays(3)->subMinute());
        $this->makePurchase($user, 'pro-lama', PlayProduct::Pro, lastVerifiedAt: now()->subDays(30)->subMinute());
        // Tepat di luar batas: belum waktunya.
        $this->makePurchase($user, 'exp-luar', expiresAt: now()->addDay()->addMinute());
        $this->makePurchase($user, 'pending-baru', PlayProduct::Pro, PurchaseStatus::Pending, lastVerifiedAt: now()->subDay()->addMinute());
        $this->makePurchase($user, 'basi-baru', expiresAt: $far, lastVerifiedAt: now()->subDays(3)->addMinute());
        $this->makePurchase($user, 'pro-baru', PlayProduct::Pro, lastVerifiedAt: now()->subDays(30)->addMinute());
        foreach (['exp-dalam', 'basi-lama'] as $token) {
            $this->play->respond($token, FakePlayPurchaseVerifier::sync());
        }
        foreach (['pending-lama', 'pro-lama'] as $token) {
            $this->play->respond($token, FakePlayPurchaseVerifier::pro());
        }

        $this->artisan('play:reconcile')->assertExitCode(0);

        $verified = array_map(fn (array $call) => $call[1], $this->play->calls);
        $this->assertEqualsCanonicalizing(['exp-dalam', 'pending-lama', 'basi-lama', 'pro-lama'], $verified);
    }

    public function test_reverified_rows_get_the_current_time_as_last_verified_at(): void
    {
        $this->travelTo(now()->startOfSecond());
        $user = User::factory()->create();
        $this->makePurchase($user, 'tok', expiresAt: now()->subHour(), lastVerifiedAt: now()->subDays(10));
        $this->play->respond('tok', FakePlayPurchaseVerifier::sync());

        $this->artisan('play:reconcile')->assertExitCode(0);

        $row = PlayPurchase::sole();
        $this->assertTrue($row->last_verified_at->equalTo(now()));
        $this->assertTrue($row->last_checked_at->equalTo(now()));
    }

    public function test_unconfigured_verification_is_skipped_without_error_or_changes(): void
    {
        $user = User::factory()->create(['plans' => ['sync']]);
        $this->makePurchase($user, 'tok', expiresAt: now()->subDay());
        $this->play->configured = false;

        $this->artisan('play:reconcile')
            ->expectsOutputToContain('belum dikonfigurasi')
            ->assertExitCode(0);

        $this->assertSame(['sync'], $user->fresh()->plans);
        $this->assertSame(PurchaseStatus::Active, PlayPurchase::sole()->status);
    }

    public function test_undecryptable_token_is_skipped_without_changes_and_a_reclaim_heals_it(): void
    {
        $user = User::factory()->create(['plans' => ['sync']]);
        $purchase = $this->makePurchase($user, 'tok', expiresAt: now()->subDay());
        // Seolah APP_KEY berganti: ciphertext yang tersimpan tidak bisa dibuka lagi.
        DB::table('play_purchases')->where('id', $purchase->id)->update(['purchase_token' => 'bukan-ciphertext-yang-sah']);
        $this->play->respond('tok', FakePlayPurchaseVerifier::sync());

        $this->artisan('play:reconcile')->expectsOutputToContain('dilewati 1')->assertExitCode(0);

        $this->assertCount(0, $this->play->calls);
        $this->assertSame(['sync'], $user->fresh()->plans);

        // Klaim ulang dari aplikasi (token asli ada di perangkat) menulis ulang token dengan kunci sekarang.
        $this->app['auth']->forgetGuards();
        $this->withHeaders(['Authorization' => 'Bearer '.$user->createToken('t')->plainTextToken])
            ->postJson('/api/purchases/claim', ['purchases' => [['product_id' => 'rizqflow_sync', 'purchase_token' => 'tok']]])
            ->assertOk();
        $this->assertSame('tok', PlayPurchase::sole()->purchase_token);
    }

    public function test_limit_option_bounds_the_run_and_oldest_verification_goes_first(): void
    {
        $user = User::factory()->create();
        $this->makePurchase($user, 'tok-b', expiresAt: now()->subDay(), lastVerifiedAt: now()->subDays(2));
        $this->makePurchase($user, 'tok-a', expiresAt: now()->subDay(), lastVerifiedAt: now()->subDays(9));
        foreach (['tok-a', 'tok-b'] as $token) {
            $this->play->respond($token, FakePlayPurchaseVerifier::sync());
        }

        $this->artisan('play:reconcile', ['--limit' => 1])->assertExitCode(0);

        $this->assertSame(['tok-a'], array_map(fn (array $call) => $call[1], $this->play->calls));
    }

    public function test_command_is_scheduled_daily(): void
    {
        $events = collect(app(Schedule::class)->events())
            ->filter(fn ($event) => str_contains($event->command, 'play:reconcile'));

        $this->assertCount(1, $events);
        $this->assertSame('30 3 * * *', $events->first()->expression);
    }
}
