<?php

namespace Tests\Feature\Api;

use App\Models\PlayPurchase;
use App\Models\User;
use App\Play\PlayNotConfigured;
use App\Play\PlayNotificationAuthenticator;
use App\Play\PlayProduct;
use App\Play\PlayPurchaseNotFound;
use App\Play\PlayPurchaseVerifier;
use App\Play\PlayUnavailable;
use App\Play\PurchaseStatus;
use Carbon\CarbonImmutable;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Str;
use Tests\Support\CreatesPlayPurchases;
use Tests\Support\FakePlayNotificationAuthenticator;
use Tests\Support\FakePlayPurchaseVerifier;
use Tests\TestCase;

/**
 * POST /api/play/notifications (push Pub/Sub untuk Real-time Developer Notifications). Autentikator
 * dan verifier palsu: tidak ada jaringan, dan tanda tangan JWT sungguhan diuji terpisah di
 * GoogleOidcNotificationAuthenticatorTest.
 */
class PlayNotificationTest extends TestCase
{
    use CreatesPlayPurchases, RefreshDatabase;

    private const PACKAGE = 'com.roziqrizal.rizqflow';

    private FakePlayPurchaseVerifier $play;

    private FakePlayNotificationAuthenticator $auth;

    protected function setUp(): void
    {
        parent::setUp();

        $this->play = new FakePlayPurchaseVerifier;
        $this->auth = new FakePlayNotificationAuthenticator;
        $this->app->instance(PlayPurchaseVerifier::class, $this->play);
        $this->app->instance(PlayNotificationAuthenticator::class, $this->auth);
    }

    /** @param array<string, mixed> $notification isi DeveloperNotification (tanpa packageName bila tak diberikan) */
    private function pubsub(array $notification, string $package = self::PACKAGE): array
    {
        return [
            'message' => [
                'data' => base64_encode(json_encode(['version' => '1.0', 'packageName' => $package, 'eventTimeMillis' => '1790000000000'] + $notification)),
                'messageId' => '1234567890',
            ],
            'subscription' => 'projects/rizqflow/subscriptions/play-rtdn',
        ];
    }

    private function subscriptionNotification(string $token, int $type = 4): array
    {
        return ['subscriptionNotification' => [
            'version' => '1.0', 'notificationType' => $type, 'purchaseToken' => $token, 'subscriptionId' => 'rizqflow_sync',
        ]];
    }

    private function push(array $body, ?string $bearer = FakePlayNotificationAuthenticator::VALID_TOKEN)
    {
        return $this->withHeaders($bearer === null ? [] : ['Authorization' => 'Bearer '.$bearer])
            ->postJson('/api/play/notifications', $body);
    }

    // --- autentikasi -------------------------------------------------------------------------

    public function test_missing_or_wrong_bearer_token_is_401_and_does_nothing(): void
    {
        $user = User::factory()->create();
        $this->makePurchase($user, 'tok');
        $this->play->respond('tok', FakePlayPurchaseVerifier::sync(PurchaseStatus::Expired));
        $body = $this->pubsub($this->subscriptionNotification('tok', 13));

        $this->push($body, null)->assertUnauthorized()->assertExactJson(['message' => 'Unauthenticated.']);
        $this->push($body, 'token-palsu')->assertUnauthorized()->assertExactJson(['message' => 'Unauthenticated.']);
        $this->push($body, '')->assertUnauthorized();

        $this->assertCount(0, $this->play->calls);
        $this->assertSame(PurchaseStatus::Active, PlayPurchase::sole()->status);
    }

    public function test_a_sanctum_api_token_is_not_accepted_as_pubsub_credentials(): void
    {
        $user = User::factory()->create(['plans' => ['sync']]);

        $this->push($this->pubsub($this->subscriptionNotification('tok')), $user->createToken('t')->plainTextToken)
            ->assertUnauthorized();
    }

    public function test_unconfigured_audience_is_503_and_never_accepts_unauthenticated_requests(): void
    {
        $this->auth->configured = false;
        $user = User::factory()->create();
        $this->makePurchase($user, 'tok');
        $this->play->respond('tok', FakePlayPurchaseVerifier::sync(PurchaseStatus::Expired));
        $body = $this->pubsub($this->subscriptionNotification('tok', 13));

        $this->push($body, null)->assertStatus(503);
        $this->push($body)->assertStatus(503); // bahkan dengan token yang "sah"

        $this->assertCount(0, $this->play->calls);
        $this->assertSame(PurchaseStatus::Active, PlayPurchase::sole()->status);
    }

    public function test_default_binding_without_audience_is_503(): void
    {
        // Pengikatan sungguhan (bukan palsu) dengan konfigurasi kosong, seperti server baru tanpa env.
        $this->app->forgetInstance(PlayNotificationAuthenticator::class); // kembali ke pengikatan AppServiceProvider
        config(['services.play.rtdn_audience' => null]);

        $this->push($this->pubsub(['testNotification' => ['version' => '1.0']]), 'apa-saja')->assertStatus(503);
        $this->push($this->pubsub(['testNotification' => ['version' => '1.0']]), null)->assertStatus(503);
    }

    // --- pesan yang diabaikan ----------------------------------------------------------------

    public function test_notification_for_another_package_is_ignored(): void
    {
        $user = User::factory()->create();
        $this->makePurchase($user, 'tok');
        $this->play->respond('tok', FakePlayPurchaseVerifier::sync(PurchaseStatus::Expired));

        $this->push($this->pubsub($this->subscriptionNotification('tok', 13), 'com.orang.lain'))->assertNoContent();

        $this->assertCount(0, $this->play->calls);
        $this->assertSame(PurchaseStatus::Active, PlayPurchase::sole()->status);
    }

    public function test_notification_for_an_unclaimed_token_is_ignored(): void
    {
        $this->push($this->pubsub($this->subscriptionNotification('belum-pernah-diklaim')))->assertNoContent();

        $this->assertCount(0, $this->play->calls);
        $this->assertSame(0, PlayPurchase::count());
    }

    public function test_test_notification_is_acknowledged_without_effect(): void
    {
        $this->push($this->pubsub(['testNotification' => ['version' => '1.0']]))->assertNoContent();

        $this->assertCount(0, $this->play->calls);
    }

    public function test_malformed_bodies_are_acknowledged_with_204_so_pubsub_does_not_retry_forever(): void
    {
        $bad = [
            'tanpa message' => [],
            'message bukan objek' => ['message' => 'x'],
            'tanpa data' => ['message' => ['messageId' => '1']],
            'data bukan base64' => ['message' => ['data' => '%%%bukan-base64%%%']],
            'data bukan JSON' => ['message' => ['data' => base64_encode('bukan json')]],
            'JSON bukan objek' => ['message' => ['data' => base64_encode('"teks"')]],
            'tanpa packageName' => ['message' => ['data' => base64_encode(json_encode(['subscriptionNotification' => ['purchaseToken' => 'tok']]))]],
            'data terlalu besar' => ['message' => ['data' => str_repeat('A', 70000)]],
        ];

        foreach ($bad as $name => $body) {
            $this->push($body)->assertNoContent();
        }

        $this->assertCount(0, $this->play->calls);
    }

    public function test_oversized_purchase_token_in_a_notification_is_ignored(): void
    {
        $this->push($this->pubsub($this->subscriptionNotification(Str::random(5000))))->assertNoContent();

        $this->assertCount(0, $this->play->calls);
    }

    // --- verifikasi ulang, bukan percaya isi notifikasi --------------------------------------

    public function test_subscription_notification_reverifies_and_revokes_according_to_play_not_the_message(): void
    {
        $user = User::factory()->create();
        $this->makePurchase($user, 'tok');
        $user->update(['plans' => ['sync']]);
        // Pesan mengaku "PURCHASED" (4), tetapi Play menjawab kedaluwarsa: yang dipercaya Play.
        $this->play->respond('tok', FakePlayPurchaseVerifier::sync(PurchaseStatus::Expired));

        $this->push($this->pubsub($this->subscriptionNotification('tok', 4)))->assertNoContent();

        $this->assertCount(1, $this->play->calls);
        $this->assertSame(PurchaseStatus::Expired, PlayPurchase::sole()->status);
        $this->assertSame([], $user->fresh()->plans);
    }

    public function test_subscription_notification_grants_according_to_play_not_the_message(): void
    {
        $user = User::factory()->create();
        $this->makePurchase($user, 'tok', status: PurchaseStatus::OnHold, expiresAt: now()->subDay());
        // Pesan mengaku "EXPIRED" (13), tetapi Play menjawab aktif (pulih): yang dipercaya Play.
        $expiry = CarbonImmutable::now()->addDays(30)->startOfSecond();
        $this->play->respond('tok', FakePlayPurchaseVerifier::sync(PurchaseStatus::Active, $expiry));

        $this->push($this->pubsub($this->subscriptionNotification('tok', 13)))->assertNoContent();

        $row = PlayPurchase::sole();
        $this->assertSame(PurchaseStatus::Active, $row->status);
        $this->assertTrue($row->expires_at->equalTo($expiry));
        $this->assertSame(['sync'], $user->fresh()->plans);
    }

    public function test_one_time_product_notification_completes_a_pending_purchase(): void
    {
        $user = User::factory()->create();
        $this->makePurchase($user, 'tok', PlayProduct::Pro, PurchaseStatus::Pending);
        $this->play->respond('tok', FakePlayPurchaseVerifier::pro());

        $this->push($this->pubsub(['oneTimeProductNotification' => [
            'version' => '1.0', 'notificationType' => 1, 'purchaseToken' => 'tok', 'sku' => 'rizqflow_pro',
        ]]))->assertNoContent();

        $this->assertSame(PurchaseStatus::Purchased, PlayPurchase::sole()->status);
        $this->assertSame(['pro'], $user->fresh()->plans);
    }

    public function test_voided_purchase_marks_the_refund_and_revokes_pro(): void
    {
        $user = User::factory()->create();
        $this->makePurchase($user, 'tok', PlayProduct::Pro);
        $user->update(['plans' => ['pro']]);
        $this->play->respond('tok', FakePlayPurchaseVerifier::pro(PurchaseStatus::Refunded));

        $this->push($this->pubsub(['voidedPurchaseNotification' => [
            'purchaseToken' => 'tok', 'orderId' => 'GPA.1234', 'productType' => 2, 'refundType' => 1,
        ]]))->assertNoContent();

        $this->assertSame(PurchaseStatus::Refunded, PlayPurchase::sole()->status);
        $this->assertSame([], $user->fresh()->plans);
    }

    public function test_voided_notification_is_not_trusted_over_play(): void
    {
        // Play (sumber kebenaran) belum menandai batal: baris tidak diubah hanya karena pesan mengatakannya.
        $user = User::factory()->create();
        $this->makePurchase($user, 'tok', PlayProduct::Pro);
        $user->update(['plans' => ['pro']]);
        $this->play->respond('tok', FakePlayPurchaseVerifier::pro());

        $this->push($this->pubsub(['voidedPurchaseNotification' => ['purchaseToken' => 'tok', 'productType' => 2]]))->assertNoContent();

        $this->assertSame(PurchaseStatus::Purchased, PlayPurchase::sole()->status);
        $this->assertSame(['pro'], $user->fresh()->plans);
    }

    public function test_duplicate_notifications_are_safe(): void
    {
        $user = User::factory()->create();
        $this->makePurchase($user, 'tok');
        $user->update(['plans' => ['sync']]);
        $this->play->respond('tok', FakePlayPurchaseVerifier::sync(PurchaseStatus::Expired));
        $body = $this->pubsub($this->subscriptionNotification('tok', 13));

        $this->push($body)->assertNoContent();
        $this->push($body)->assertNoContent();

        $this->assertSame(1, PlayPurchase::count());
        $this->assertSame(PurchaseStatus::Expired, PlayPurchase::sole()->status);
        $this->assertSame([], $user->fresh()->plans);
    }

    public function test_out_of_order_notifications_end_in_the_state_play_reports_now(): void
    {
        $user = User::factory()->create();
        $this->makePurchase($user, 'tok', status: PurchaseStatus::OnHold, expiresAt: now()->subDay());
        $this->play->respond('tok', FakePlayPurchaseVerifier::sync(PurchaseStatus::Active, CarbonImmutable::now()->addDays(30)));

        // "Ditahan" (5) tiba SESUDAH "pulih" (1), tetapi Play sekarang menjawab aktif untuk keduanya.
        $this->push($this->pubsub($this->subscriptionNotification('tok', 1)))->assertNoContent();
        $this->push($this->pubsub($this->subscriptionNotification('tok', 5)))->assertNoContent();

        $this->assertSame(PurchaseStatus::Active, PlayPurchase::sole()->status);
        $this->assertSame(['sync'], $user->fresh()->plans);
    }

    public function test_refreshing_one_row_does_not_revoke_another_plan_whose_row_looks_stale(): void
    {
        // Pro dikembalikan dananya (Play menjawab begitu). Baris Sync akun yang sama tampak kedaluwarsa menurut
        // data tersimpan, tetapi Sync tidak ditanyakan ke Play pada notifikasi ini, jadi Sync tidak boleh ikut dicabut.
        $user = User::factory()->create(['plans' => ['pro', 'sync']]);
        $this->makePurchase($user, 'tok-pro', PlayProduct::Pro);
        $this->makePurchase($user, 'tok-sync', status: PurchaseStatus::Active, expiresAt: now()->subHour());
        $this->play->respond('tok-pro', FakePlayPurchaseVerifier::pro(PurchaseStatus::Refunded));

        $this->push($this->pubsub(['voidedPurchaseNotification' => ['purchaseToken' => 'tok-pro', 'productType' => 2]]))
            ->assertNoContent();

        $this->assertCount(1, $this->play->calls);
        $this->assertSame(['sync'], $user->fresh()->plans);
    }

    public function test_refresh_stamps_last_verified_at_with_the_current_time(): void
    {
        $this->travelTo(now()->startOfSecond());
        $user = User::factory()->create();
        $this->makePurchase($user, 'tok', lastVerifiedAt: now()->subDays(10));
        $this->play->respond('tok', FakePlayPurchaseVerifier::sync());

        $this->push($this->pubsub($this->subscriptionNotification('tok')))->assertNoContent();

        $this->assertTrue(PlayPurchase::sole()->last_verified_at->equalTo(now()));
    }

    public function test_play_reporting_another_product_for_a_recorded_token_leaves_the_row_alone(): void
    {
        // Baris tercatat sebagai Sync, tetapi Play melaporkan produk Pro untuk token itu: baris tidak ditimpa.
        $user = User::factory()->create(['plans' => ['sync']]);
        $this->makePurchase($user, 'tok');
        $before = PlayPurchase::sole();
        $this->play->respond('tok', FakePlayPurchaseVerifier::pro(PurchaseStatus::Refunded));

        $this->push($this->pubsub($this->subscriptionNotification('tok')))->assertNoContent();

        $after = PlayPurchase::sole();
        $this->assertSame(PurchaseStatus::Active, $after->status);
        $this->assertTrue($after->last_verified_at->equalTo($before->last_verified_at));
        $this->assertSame(['sync'], $user->fresh()->plans);
    }

    public function test_plans_without_purchase_rows_survive_a_revocation(): void
    {
        $user = User::factory()->create(['plans' => ['pro', 'sync']]); // Pro dari seed
        $this->makePurchase($user, 'tok');
        $this->play->respond('tok', FakePlayPurchaseVerifier::sync(PurchaseStatus::Expired));

        $this->push($this->pubsub($this->subscriptionNotification('tok', 13)))->assertNoContent();

        $this->assertSame(['pro'], $user->fresh()->plans);
    }

    // --- kegagalan Play ----------------------------------------------------------------------

    public function test_transient_play_failure_is_500_so_pubsub_retries_and_changes_nothing(): void
    {
        $user = User::factory()->create(['plans' => ['sync']]);
        $this->makePurchase($user, 'tok');
        $this->play->respond('tok', new PlayUnavailable);

        $this->push($this->pubsub($this->subscriptionNotification('tok', 13)))
            ->assertStatus(500)
            ->assertJsonMissingPath('exception');

        $this->assertSame(PurchaseStatus::Active, PlayPurchase::sole()->status);
        $this->assertSame(['sync'], $user->fresh()->plans);
    }

    public function test_unconfigured_play_credentials_are_503_so_pubsub_retries(): void
    {
        $user = User::factory()->create(['plans' => ['sync']]);
        $this->makePurchase($user, 'tok');
        $this->play->respond('tok', new PlayNotConfigured);

        $this->push($this->pubsub($this->subscriptionNotification('tok')))->assertStatus(503);

        $this->assertSame(['sync'], $user->fresh()->plans);
    }

    public function test_play_not_recognising_a_recorded_token_does_not_revoke_and_is_acknowledged(): void
    {
        $user = User::factory()->create(['plans' => ['sync']]);
        $this->makePurchase($user, 'tok');
        $this->play->respond('tok', new PlayPurchaseNotFound);

        $this->push($this->pubsub($this->subscriptionNotification('tok')))->assertNoContent();

        $this->assertSame(PurchaseStatus::Active, PlayPurchase::sole()->status);
        $this->assertSame(['sync'], $user->fresh()->plans);
    }

    public function test_purchase_token_never_appears_in_responses(): void
    {
        $user = User::factory()->create();
        $this->makePurchase($user, 'tok-rahasia-abc');
        $this->play->respond('tok-rahasia-abc', new PlayUnavailable);

        $response = $this->push($this->pubsub($this->subscriptionNotification('tok-rahasia-abc')));

        $this->assertStringNotContainsString('tok-rahasia-abc', $response->getContent());
    }

    public function test_endpoint_is_throttled_per_ip(): void
    {
        $body = $this->pubsub(['testNotification' => ['version' => '1.0']]);

        for ($i = 0; $i < 120; $i++) {
            $this->push($body)->assertNoContent();
        }
        $this->push($body)->assertStatus(429);
    }
}
