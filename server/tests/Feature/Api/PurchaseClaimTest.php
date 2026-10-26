<?php

namespace Tests\Feature\Api;

use App\Models\PlayPurchase;
use App\Models\User;
use App\Play\PlayPurchaseNotFound;
use App\Play\PlayPurchaseVerifier;
use App\Play\PlayUnavailable;
use App\Play\PurchaseStatus;
use App\Support\Feature;
use Carbon\CarbonImmutable;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Str;
use Tests\Support\CreatesPlayPurchases;
use Tests\Support\FakePlayPurchaseVerifier;
use Tests\TestCase;

/** POST /api/purchases/claim (Tahap 9, docs/server-sync.md "Pembelian Pro dan Sync"): verifier selalu palsu, tanpa jaringan. */
class PurchaseClaimTest extends TestCase
{
    use CreatesPlayPurchases, RefreshDatabase;

    private FakePlayPurchaseVerifier $play;

    protected function setUp(): void
    {
        parent::setUp();

        $this->play = new FakePlayPurchaseVerifier;
        $this->app->instance(PlayPurchaseVerifier::class, $this->play);
    }

    private function claim(User $user, array $purchases)
    {
        $this->app['auth']->forgetGuards(); // guard Sanctum menyimpan pengguna antar permintaan dalam satu tes

        return $this->withHeaders(['Authorization' => 'Bearer '.$user->createToken('test')->plainTextToken])
            ->postJson('/api/purchases/claim', ['purchases' => $purchases]);
    }

    private function item(string $productId, string $token): array
    {
        return ['product_id' => $productId, 'purchase_token' => $token];
    }

    // --- klaim sah ---------------------------------------------------------------------------

    public function test_valid_pro_claim_unlocks_pro_and_records_the_purchase(): void
    {
        $user = User::factory()->create();
        $this->play->respond('tok-pro', FakePlayPurchaseVerifier::pro());

        $this->claim($user, [$this->item('rizqflow_pro', 'tok-pro')])
            ->assertOk()
            ->assertExactJson([
                'plans' => ['pro'],
                'results' => [['product_id' => 'rizqflow_pro', 'status' => 'active']],
            ]);

        $this->assertSame(['pro'], $user->fresh()->plans);
        $row = PlayPurchase::sole();
        $this->assertSame($user->id, $row->user_id);
        $this->assertSame('pro', $row->plan->value);
        $this->assertSame(PurchaseStatus::Purchased, $row->status);
        $this->assertSame(hash('sha256', 'tok-pro'), $row->token_hash);
        $this->assertTrue($row->acknowledged);
        $this->assertNotNull($row->last_verified_at);
    }

    public function test_valid_sync_claim_unlocks_sync_with_expiry(): void
    {
        $user = User::factory()->create();
        $expiry = CarbonImmutable::now()->addDays(20)->startOfSecond();
        $this->play->respond('tok-sync', FakePlayPurchaseVerifier::sync(PurchaseStatus::Active, $expiry));

        $this->claim($user, [$this->item('rizqflow_sync', 'tok-sync')])
            ->assertOk()
            ->assertExactJson([
                'plans' => ['sync'],
                'results' => [['product_id' => 'rizqflow_sync', 'status' => 'active']],
            ]);

        $row = PlayPurchase::sole();
        $this->assertSame('sync', $row->plan->value);
        $this->assertTrue($row->expires_at->equalTo($expiry));
        $this->assertTrue($row->auto_renewing);
        $this->assertTrue($user->fresh()->entitlements()->isEnabled(Feature::EncryptedSync));
    }

    public function test_plans_are_ordered_pro_then_sync_and_results_follow_input_order(): void
    {
        $user = User::factory()->create();
        $this->play->respond('tok-pro', FakePlayPurchaseVerifier::pro());
        $this->play->respond('tok-sync', FakePlayPurchaseVerifier::sync());

        $this->claim($user, [$this->item('rizqflow_sync', 'tok-sync'), $this->item('rizqflow_pro', 'tok-pro')])
            ->assertOk()
            ->assertExactJson([
                'plans' => ['pro', 'sync'],
                'results' => [
                    ['product_id' => 'rizqflow_sync', 'status' => 'active'],
                    ['product_id' => 'rizqflow_pro', 'status' => 'active'],
                ],
            ]);
    }

    public function test_purchase_token_is_encrypted_at_rest_and_never_in_the_response(): void
    {
        $user = User::factory()->create();
        $this->play->respond('tok-rahasia-xyz', FakePlayPurchaseVerifier::pro());

        $response = $this->claim($user, [$this->item('rizqflow_pro', 'tok-rahasia-xyz')])->assertOk();

        $this->assertStringNotContainsString('tok-rahasia-xyz', $response->getContent());
        $this->assertNotSame('tok-rahasia-xyz', DB::table('play_purchases')->value('purchase_token'));
        $this->assertSame('tok-rahasia-xyz', PlayPurchase::sole()->purchase_token);
        $this->assertArrayNotHasKey('purchase_token', PlayPurchase::sole()->toArray());
    }

    // --- status langganan --------------------------------------------------------------------

    public function test_pending_payment_unlocks_nothing(): void
    {
        $user = User::factory()->create();
        $this->play->respond('tok-pro', FakePlayPurchaseVerifier::pro(PurchaseStatus::Pending));
        $this->play->respond('tok-sync', FakePlayPurchaseVerifier::sync(PurchaseStatus::Pending));

        $this->claim($user, [$this->item('rizqflow_pro', 'tok-pro'), $this->item('rizqflow_sync', 'tok-sync')])
            ->assertOk()
            ->assertExactJson([
                'plans' => [],
                'results' => [
                    ['product_id' => 'rizqflow_pro', 'status' => 'pending'],
                    ['product_id' => 'rizqflow_sync', 'status' => 'pending'],
                ],
            ]);

        $this->assertSame([], $user->fresh()->plans ?? []);
        // Dicatat, supaya notifikasi RTDN bisa membukanya begitu pembayaran selesai.
        $this->assertSame(2, PlayPurchase::where('status', 'pending')->count());
    }

    public function test_canceled_subscription_is_entitled_until_expiry_and_not_after(): void
    {
        $future = User::factory()->create();
        $past = User::factory()->create();
        $this->play->respond('tok-masa-depan', FakePlayPurchaseVerifier::sync(PurchaseStatus::Canceled, CarbonImmutable::now()->addDays(3)));
        $this->play->respond('tok-lewat', FakePlayPurchaseVerifier::sync(PurchaseStatus::Canceled, CarbonImmutable::now()->subDays(3)));

        $this->claim($future, [$this->item('rizqflow_sync', 'tok-masa-depan')])
            ->assertOk()->assertJsonPath('results.0.status', 'active')->assertJsonPath('plans', ['sync']);
        $this->claim($past, [$this->item('rizqflow_sync', 'tok-lewat')])
            ->assertOk()->assertJsonPath('results.0.status', 'inactive')->assertJsonPath('plans', []);

        $this->assertSame(['sync'], $future->fresh()->plans);
        $this->assertEmpty($past->fresh()->plans ?? []);
    }

    public function test_grace_period_subscription_is_entitled(): void
    {
        $user = User::factory()->create();
        $this->play->respond('tok', FakePlayPurchaseVerifier::sync(PurchaseStatus::GracePeriod, CarbonImmutable::now()->addDays(2)));

        $this->claim($user, [$this->item('rizqflow_sync', 'tok')])
            ->assertOk()->assertJsonPath('results.0.status', 'active')->assertJsonPath('plans', ['sync']);
    }

    public function test_on_hold_paused_and_expired_subscriptions_are_inactive(): void
    {
        foreach ([PurchaseStatus::OnHold, PurchaseStatus::Paused, PurchaseStatus::Expired] as $status) {
            $user = User::factory()->create();
            $token = 'tok-'.$status->value;
            // Expiry di masa depan sengaja: status yang tidak berhak tidak boleh dibuka oleh waktu.
            $this->play->respond($token, FakePlayPurchaseVerifier::sync($status, CarbonImmutable::now()->addDays(10)));

            $this->claim($user, [$this->item('rizqflow_sync', $token)])
                ->assertOk()->assertJsonPath('results.0.status', 'inactive')->assertJsonPath('plans', []);

            $this->assertEmpty($user->fresh()->plans ?? [], $status->value);
        }
    }

    public function test_final_tokens_are_not_recorded_but_recoverable_ones_are(): void
    {
        $user = User::factory()->create();
        $this->play->respond('tok-expired', FakePlayPurchaseVerifier::sync(PurchaseStatus::Expired));
        $this->play->respond('tok-refunded', FakePlayPurchaseVerifier::pro(PurchaseStatus::Refunded));
        $this->play->respond('tok-hold', FakePlayPurchaseVerifier::sync(PurchaseStatus::OnHold));

        $this->claim($user, [
            $this->item('rizqflow_sync', 'tok-expired'),
            $this->item('rizqflow_pro', 'tok-refunded'),
            $this->item('rizqflow_sync', 'tok-hold'),
        ])->assertOk();

        $this->assertSame(['on_hold'], PlayPurchase::pluck('status')->map->value->all());
    }

    // --- satu token, satu akun ---------------------------------------------------------------

    public function test_token_claimed_by_another_account_is_rejected_without_leaking_or_changing_plans(): void
    {
        $owner = User::factory()->create(['email' => 'pemilik@example.com']);
        $other = User::factory()->create(['plans' => ['pro']]); // Pro dari seed, tak boleh berubah
        $this->play->respond('tok-sync', FakePlayPurchaseVerifier::sync());
        $this->claim($owner, [$this->item('rizqflow_sync', 'tok-sync')])->assertOk();
        $callsAfterOwner = count($this->play->calls);

        $response = $this->claim($other, [$this->item('rizqflow_sync', 'tok-sync')]);

        $response->assertOk()->assertExactJson([
            'plans' => ['pro'],
            'results' => [['product_id' => 'rizqflow_sync', 'status' => 'claimed_by_other']],
        ]);
        $this->assertStringNotContainsString('pemilik@example.com', $response->getContent());
        $this->assertSame(['pro'], $other->fresh()->plans);
        $this->assertSame($owner->id, PlayPurchase::sole()->user_id);
        $this->assertCount($callsAfterOwner, $this->play->calls, 'klaim ditolak sebelum menanyai Play');
    }

    public function test_another_account_cannot_claim_a_token_whose_chain_belongs_to_someone_else(): void
    {
        // Akun lain tidak boleh mencuri rantai token: Play melaporkan 'tok-baru' sebagai penerus
        // 'tok-lama' (linkedPurchaseToken), dan 'tok-lama' sudah diklaim pemilik. Akun lain yang
        // mengklaim 'tok-baru' lebih dulu (sebelum RTDN/aplikasi pemilik menyusul) harus ditolak,
        // walau belum ada baris play_purchases untuk 'tok-baru' sama sekali.
        $owner = User::factory()->create();
        $this->makePurchase($owner, 'tok-lama');
        $other = User::factory()->create(['plans' => ['pro']]); // tak boleh berubah
        $this->play->respond('tok-baru', FakePlayPurchaseVerifier::sync(predecessorToken: 'tok-lama'));

        $this->claim($other, [$this->item('rizqflow_sync', 'tok-baru')])
            ->assertOk()
            ->assertExactJson([
                'plans' => ['pro'],
                'results' => [['product_id' => 'rizqflow_sync', 'status' => 'claimed_by_other']],
            ]);

        $this->assertSame(['pro'], $other->fresh()->plans);
        $this->assertSame(0, PlayPurchase::where('token_hash', hash('sha256', 'tok-baru'))->count());
    }

    public function test_the_predecessors_own_owner_can_still_claim_the_successor_token_manually(): void
    {
        // Jalur biasa (app dibuka dan mengklaim ulang): pemilik pendahulu sendiri yang mengklaim
        // token penerus tetap berhasil seperti klaim baru biasa; rantai tidak menghalangi pemiliknya.
        $owner = User::factory()->create();
        $this->makePurchase($owner, 'tok-lama');
        $this->play->respond('tok-baru', FakePlayPurchaseVerifier::sync(predecessorToken: 'tok-lama'));

        $this->claim($owner, [$this->item('rizqflow_sync', 'tok-baru')])
            ->assertOk()->assertJsonPath('results.0.status', 'active')->assertJsonPath('plans', ['sync']);

        $this->assertSame($owner->id, PlayPurchase::where('token_hash', hash('sha256', 'tok-baru'))->sole()->user_id);
    }

    public function test_a_token_with_a_chain_to_an_unclaimed_predecessor_can_still_be_claimed_by_anyone(): void
    {
        // Rantai ke pendahulu yang juga belum pernah diklaim siapa pun tidak memberi bukti pemilik
        // apa pun: aturan lama "siapa cepat dia dapat" tetap berlaku, tidak diblokir oleh rantai.
        $user = User::factory()->create();
        $this->play->respond('tok-baru', FakePlayPurchaseVerifier::sync(predecessorToken: 'tok-lama-tak-pernah-diklaim'));

        $this->claim($user, [$this->item('rizqflow_sync', 'tok-baru')])
            ->assertOk()->assertJsonPath('results.0.status', 'active')->assertJsonPath('plans', ['sync']);
    }

    public function test_reclaim_by_the_same_account_is_idempotent_and_refreshes_status(): void
    {
        $user = User::factory()->create();
        $this->play->respond('tok', FakePlayPurchaseVerifier::sync(PurchaseStatus::Active, CarbonImmutable::now()->addDays(5)));
        $this->claim($user, [$this->item('rizqflow_sync', 'tok')])->assertOk();

        $renewed = CarbonImmutable::now()->addDays(35)->startOfSecond();
        $this->play->respond('tok', FakePlayPurchaseVerifier::sync(PurchaseStatus::Active, $renewed));
        $this->claim($user, [$this->item('rizqflow_sync', 'tok')])
            ->assertOk()->assertJsonPath('results.0.status', 'active')->assertJsonPath('plans', ['sync']);

        $this->assertSame(1, PlayPurchase::count());
        $this->assertTrue(PlayPurchase::sole()->expires_at->equalTo($renewed));
    }

    public function test_last_verified_at_is_stamped_on_a_new_claim_and_moved_forward_by_a_reclaim(): void
    {
        $this->travelTo(now()->startOfSecond());
        $user = User::factory()->create();
        $this->play->respond('tok', FakePlayPurchaseVerifier::sync());

        $this->claim($user, [$this->item('rizqflow_sync', 'tok')])->assertOk();
        $this->assertTrue(PlayPurchase::sole()->last_verified_at->equalTo(now()), 'klaim baru: sekarang');

        $this->travel(2)->days();
        $this->claim($user, [$this->item('rizqflow_sync', 'tok')])->assertOk();
        $this->assertTrue(PlayPurchase::sole()->last_verified_at->equalTo(now()), 'klaim ulang: maju ke sekarang');
    }

    public function test_same_token_twice_in_one_request_asks_play_once(): void
    {
        $user = User::factory()->create();
        $this->play->respond('tok', FakePlayPurchaseVerifier::pro());

        $this->claim($user, [$this->item('rizqflow_pro', 'tok'), $this->item('rizqflow_pro', 'tok')])
            ->assertOk()
            ->assertJsonPath('results.0.status', 'active')
            ->assertJsonPath('results.1.status', 'active');

        $this->assertCount(1, $this->play->calls);
        $this->assertSame(1, PlayPurchase::count());
    }

    public function test_token_already_recorded_for_another_product_is_inactive_and_unchanged(): void
    {
        $user = User::factory()->create();
        $this->play->respond('tok', FakePlayPurchaseVerifier::pro());
        $this->claim($user, [$this->item('rizqflow_pro', 'tok')])->assertOk();

        $this->claim($user, [$this->item('rizqflow_sync', 'tok')])
            ->assertOk()->assertJsonPath('results.0.status', 'inactive');

        $this->assertSame('rizqflow_pro', PlayPurchase::sole()->product_id);
        $this->assertSame(['pro'], $user->fresh()->plans);
    }

    public function test_losing_a_race_to_another_account_is_claimed_by_other(): void
    {
        $winner = User::factory()->create();
        $loser = User::factory()->create();
        $this->play->respond('tok', FakePlayPurchaseVerifier::sync());
        // Pesaing mencatat token tepat setelah pemeriksaan awal kita dan sebelum kita menyimpan.
        $this->play->beforeVerify = fn () => $this->makePurchase($winner, 'tok');

        $this->claim($loser, [$this->item('rizqflow_sync', 'tok')])
            ->assertOk()
            ->assertJsonPath('results.0.status', 'claimed_by_other')
            ->assertJsonPath('plans', []);

        $this->assertSame($winner->id, PlayPurchase::sole()->user_id);
        $this->assertEmpty($loser->fresh()->plans ?? []);
    }

    public function test_losing_a_race_to_the_same_account_is_idempotent(): void
    {
        $user = User::factory()->create();
        $this->play->respond('tok', FakePlayPurchaseVerifier::sync());
        $this->play->beforeVerify = fn () => $this->makePurchase($user, 'tok', status: PurchaseStatus::Pending);

        $this->claim($user, [$this->item('rizqflow_sync', 'tok')])
            ->assertOk()
            ->assertJsonPath('results.0.status', 'active')
            ->assertJsonPath('plans', ['sync']);

        $this->assertSame(1, PlayPurchase::count());
        $this->assertSame(PurchaseStatus::Active, PlayPurchase::sole()->status);
    }

    // --- penolakan ---------------------------------------------------------------------------

    public function test_unknown_product_is_reported_without_asking_play(): void
    {
        $user = User::factory()->create();

        $this->claim($user, [$this->item('produk_misterius', 'tok')])
            ->assertOk()
            ->assertExactJson([
                'plans' => [],
                'results' => [['product_id' => 'produk_misterius', 'status' => 'unknown_product']],
            ]);

        $this->assertCount(0, $this->play->calls);
        $this->assertSame(0, PlayPurchase::count());
    }

    public function test_play_reporting_a_different_product_than_claimed_is_rejected(): void
    {
        $user = User::factory()->create();
        // Klien mengaku rizqflow_pro, tetapi token itu langganan Sync menurut Play.
        $this->play->respond('tok-sync', FakePlayPurchaseVerifier::sync());

        $this->claim($user, [$this->item('rizqflow_pro', 'tok-sync')])
            ->assertOk()
            ->assertJsonPath('results.0.status', 'inactive')
            ->assertJsonPath('plans', []);

        $this->assertSame(0, PlayPurchase::count());
        $this->assertEmpty($user->fresh()->plans ?? []);
    }

    public function test_token_unknown_to_play_is_inactive_and_not_recorded(): void
    {
        $user = User::factory()->create();
        $this->play->respond('tok-palsu', new PlayPurchaseNotFound);

        $this->claim($user, [$this->item('rizqflow_pro', 'tok-palsu')])
            ->assertOk()->assertJsonPath('results.0.status', 'inactive');

        $this->assertSame(0, PlayPurchase::count());
    }

    public function test_unconfigured_verification_is_503_and_changes_nothing(): void
    {
        $user = User::factory()->create(['plans' => ['pro']]);
        $this->play->configured = false;
        $this->play->respond('tok', FakePlayPurchaseVerifier::sync());

        $this->claim($user, [$this->item('rizqflow_sync', 'tok')])
            ->assertStatus(503)
            ->assertJsonStructure(['message']);
        // Tetap 503 walau semua itemnya produk yang tak dikenal: "belum dikonfigurasi" tak bergantung isi.
        $this->claim($user, [$this->item('produk_misterius', 'tok')])->assertStatus(503);

        $this->assertSame(['pro'], $user->fresh()->plans);
        $this->assertSame(0, PlayPurchase::count());
    }

    public function test_transient_play_failure_is_unverifiable_and_changes_nothing(): void
    {
        $user = User::factory()->create(['plans' => ['pro']]);
        $this->play->respond('tok-gagal', new PlayUnavailable);
        $this->play->respond('tok-ok', FakePlayPurchaseVerifier::sync());

        $this->claim($user, [$this->item('rizqflow_pro', 'tok-gagal'), $this->item('rizqflow_sync', 'tok-ok')])
            ->assertOk()
            ->assertExactJson([
                'plans' => ['pro', 'sync'],
                'results' => [
                    ['product_id' => 'rizqflow_pro', 'status' => 'unverifiable'],
                    ['product_id' => 'rizqflow_sync', 'status' => 'active'],
                ],
            ]);

        $this->assertSame(1, PlayPurchase::count());
    }

    public function test_play_failure_never_revokes_a_previously_granted_plan(): void
    {
        $user = User::factory()->create();
        $this->play->respond('tok', FakePlayPurchaseVerifier::pro());
        $this->claim($user, [$this->item('rizqflow_pro', 'tok')])->assertOk();
        $before = PlayPurchase::sole()->fresh();

        $this->play->respond('tok', new PlayUnavailable);
        $this->claim($user, [$this->item('rizqflow_pro', 'tok')])
            ->assertOk()->assertJsonPath('results.0.status', 'unverifiable')->assertJsonPath('plans', ['pro']);

        $this->assertSame(['pro'], $user->fresh()->plans);
        $this->assertEquals($before->only(['status', 'last_verified_at']), PlayPurchase::sole()->only(['status', 'last_verified_at']));
    }

    public function test_play_not_recognising_an_already_recorded_token_does_not_revoke(): void
    {
        $user = User::factory()->create();
        $this->play->respond('tok', FakePlayPurchaseVerifier::pro());
        $this->claim($user, [$this->item('rizqflow_pro', 'tok')])->assertOk();

        $this->play->respond('tok', new PlayPurchaseNotFound);
        $this->claim($user, [$this->item('rizqflow_pro', 'tok')])
            ->assertOk()->assertJsonPath('results.0.status', 'unverifiable')->assertJsonPath('plans', ['pro']);
    }

    public function test_partial_claim_with_a_failing_sync_item_does_not_revoke_sync_by_its_stale_stored_expiry(): void
    {
        // Sync tercatat Active tetapi expires_at-nya sejam lalu (perpanjangan belum diketahui server).
        // Klaim berisi Pro (sah) dan Sync (Play gagal): Sync dilaporkan unverifiable dan TIDAK boleh dicabut.
        $user = User::factory()->create(['plans' => ['sync']]);
        $this->makePurchase($user, 'tok-sync', status: PurchaseStatus::Active, expiresAt: now()->subHour());
        $this->play->respond('tok-pro', FakePlayPurchaseVerifier::pro());
        $this->play->respond('tok-sync', new PlayUnavailable);

        $this->claim($user, [$this->item('rizqflow_pro', 'tok-pro'), $this->item('rizqflow_sync', 'tok-sync')])
            ->assertOk()
            ->assertExactJson([
                'plans' => ['pro', 'sync'],
                'results' => [
                    ['product_id' => 'rizqflow_pro', 'status' => 'active'],
                    ['product_id' => 'rizqflow_sync', 'status' => 'unverifiable'],
                ],
            ]);

        $this->assertSame(['pro', 'sync'], $user->fresh()->plans);
    }

    public function test_claiming_only_pro_does_not_ask_play_about_sync_nor_revoke_it(): void
    {
        $user = User::factory()->create(['plans' => ['sync']]);
        $this->makePurchase($user, 'tok-sync', status: PurchaseStatus::Active, expiresAt: now()->subHour());
        $this->play->respond('tok-pro', FakePlayPurchaseVerifier::pro());

        $this->claim($user, [$this->item('rizqflow_pro', 'tok-pro')])
            ->assertOk()->assertJsonPath('plans', ['pro', 'sync']);

        $this->assertCount(1, $this->play->calls, 'Play hanya ditanya soal Pro');
        $this->assertSame(['pro', 'sync'], $user->fresh()->plans);
    }

    public function test_a_stale_sync_row_is_revoked_once_play_itself_says_it_expired(): void
    {
        // Pasangan dari dua tes di atas: yang menentukan tetap jawaban Play, bukan ketiadaan jawaban.
        $user = User::factory()->create(['plans' => ['sync']]);
        $this->makePurchase($user, 'tok-sync', status: PurchaseStatus::Active, expiresAt: now()->subHour());
        $this->play->respond('tok-sync', FakePlayPurchaseVerifier::sync(PurchaseStatus::Expired, CarbonImmutable::now()->subHour()));

        $this->claim($user, [$this->item('rizqflow_sync', 'tok-sync')])
            ->assertOk()->assertJsonPath('results.0.status', 'inactive')->assertJsonPath('plans', []);

        $this->assertSame([], $user->fresh()->plans);
    }

    // --- derivasi paket ----------------------------------------------------------------------

    public function test_a_purchase_that_was_entitled_and_then_expires_revokes_the_plan(): void
    {
        $user = User::factory()->create();
        $this->play->respond('tok', FakePlayPurchaseVerifier::sync());
        $this->claim($user, [$this->item('rizqflow_sync', 'tok')])->assertOk()->assertJsonPath('plans', ['sync']);

        $this->play->respond('tok', FakePlayPurchaseVerifier::sync(PurchaseStatus::Expired));
        $this->claim($user, [$this->item('rizqflow_sync', 'tok')])
            ->assertOk()->assertJsonPath('results.0.status', 'inactive')->assertJsonPath('plans', []);

        $this->assertSame([], $user->fresh()->plans);
        $this->assertSame(PurchaseStatus::Expired, PlayPurchase::sole()->status);
    }

    public function test_a_refunded_pro_purchase_revokes_pro(): void
    {
        $user = User::factory()->create();
        $this->play->respond('tok', FakePlayPurchaseVerifier::pro());
        $this->claim($user, [$this->item('rizqflow_pro', 'tok')])->assertOk();

        $this->play->respond('tok', FakePlayPurchaseVerifier::pro(PurchaseStatus::Refunded));
        $this->claim($user, [$this->item('rizqflow_pro', 'tok')])
            ->assertOk()->assertJsonPath('results.0.status', 'inactive')->assertJsonPath('plans', []);
    }

    public function test_plans_without_any_purchase_row_survive_another_purchase_expiring(): void
    {
        // Pro dari seed/tinker (tanpa baris pembelian) tidak boleh ikut hilang saat Sync kedaluwarsa.
        $user = User::factory()->create(['plans' => ['pro', 'sync']]);
        $this->play->respond('tok', FakePlayPurchaseVerifier::sync(PurchaseStatus::OnHold));

        $this->claim($user, [$this->item('rizqflow_sync', 'tok')])
            ->assertOk()->assertJsonPath('results.0.status', 'inactive')->assertJsonPath('plans', ['pro']);

        $this->assertSame(['pro'], $user->fresh()->plans);
    }

    public function test_another_entitled_purchase_of_the_same_plan_keeps_the_plan(): void
    {
        $user = User::factory()->create();
        $this->makePurchase($user, 'tok-lama', status: PurchaseStatus::Active);
        $this->play->respond('tok-baru', FakePlayPurchaseVerifier::sync(PurchaseStatus::OnHold));

        $this->claim($user, [$this->item('rizqflow_sync', 'tok-baru')])
            ->assertOk()->assertJsonPath('plans', ['sync']);
    }

    public function test_plans_in_the_response_follow_the_fixed_order_even_if_stored_in_reverse(): void
    {
        // users.plans bisa tersimpan terbalik (seed/tinker): kontraknya tetap "pro lalu sync".
        $user = User::factory()->create(['plans' => ['sync', 'pro']]);

        $this->claim($user, [$this->item('produk_misterius', 'tok')])
            ->assertOk()->assertExactJson([
                'plans' => ['pro', 'sync'],
                'results' => [['product_id' => 'produk_misterius', 'status' => 'unknown_product']],
            ]);
    }

    // --- autentikasi, validasi, throttle, hapus akun -----------------------------------------

    public function test_claim_requires_an_api_token(): void
    {
        $this->postJson('/api/purchases/claim', ['purchases' => [$this->item('rizqflow_pro', 'tok')]])
            ->assertUnauthorized();

        $this->assertCount(0, $this->play->calls);
    }

    public function test_validation_rejects_malformed_bodies(): void
    {
        $user = User::factory()->create();
        $eleven = array_map(fn (int $i) => $this->item('rizqflow_pro', "tok-{$i}"), range(1, 11));

        $cases = [
            'tanpa purchases' => [[], 'purchases'],
            'purchases kosong' => [['purchases' => []], 'purchases'],
            'purchases bukan array' => [['purchases' => 'x'], 'purchases'],
            'lebih dari 10 item' => [['purchases' => $eleven], 'purchases'],
            'tanpa product_id' => [['purchases' => [['purchase_token' => 'tok']]], 'purchases.0.product_id'],
            'tanpa purchase_token' => [['purchases' => [['product_id' => 'rizqflow_pro']]], 'purchases.0.purchase_token'],
            'token bukan string' => [['purchases' => [['product_id' => 'rizqflow_pro', 'purchase_token' => ['a']]]], 'purchases.0.purchase_token'],
            'token terlalu panjang' => [['purchases' => [$this->item('rizqflow_pro', Str::random(4097))]], 'purchases.0.purchase_token'],
            'product_id terlalu panjang' => [['purchases' => [$this->item(Str::random(101), 'tok')]], 'purchases.0.product_id'],
            'item bukan objek' => [['purchases' => ['rizqflow_pro']], 'purchases.0'],
        ];

        foreach ($cases as $name => [$body, $errorKey]) {
            $this->withHeaders(['Authorization' => 'Bearer '.$user->createToken('t')->plainTextToken])
                ->postJson('/api/purchases/claim', $body)
                ->assertUnprocessable()
                ->assertJsonValidationErrors($errorKey);
        }

        $this->assertCount(0, $this->play->calls);
    }

    public function test_exactly_ten_items_and_a_4096_character_token_are_accepted(): void
    {
        $user = User::factory()->create();
        $ten = array_map(fn (int $i) => $this->item('produk_misterius', "tok-{$i}"), range(1, 9));
        $ten[] = $this->item('produk_misterius', Str::random(4096));

        $this->claim($user, $ten)->assertOk()->assertJsonCount(10, 'results');
    }

    public function test_claim_endpoint_is_throttled_per_account(): void
    {
        $user = User::factory()->create();
        $headers = ['Authorization' => 'Bearer '.$user->createToken('t')->plainTextToken];
        $body = ['purchases' => [$this->item('produk_misterius', 'tok')]];

        for ($i = 0; $i < 10; $i++) {
            $this->withHeaders($headers)->postJson('/api/purchases/claim', $body)->assertOk();
        }
        $this->withHeaders($headers)->postJson('/api/purchases/claim', $body)->assertStatus(429);

        // Akun lain tidak ikut terkena batas.
        $this->claim(User::factory()->create(), $body['purchases'])->assertOk();
    }

    public function test_deleting_the_account_deletes_its_purchase_rows_and_frees_the_token(): void
    {
        $user = User::factory()->create();
        $this->play->respond('tok', FakePlayPurchaseVerifier::pro());
        $this->claim($user, [$this->item('rizqflow_pro', 'tok')])->assertOk();
        $this->assertSame(1, PlayPurchase::count());

        $this->withHeaders(['Authorization' => 'Bearer '.$user->createToken('hapus')->plainTextToken])
            ->deleteJson('/api/account')
            ->assertNoContent();

        $this->assertSame(0, PlayPurchase::count());

        $next = User::factory()->create();
        $this->claim($next, [$this->item('rizqflow_pro', 'tok')])->assertOk()->assertJsonPath('plans', ['pro']);
    }
}
