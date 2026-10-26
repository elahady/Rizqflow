<?php

namespace Tests\Feature\Play;

use App\Play\GooglePlayPurchaseVerifier;
use App\Play\PlayException;
use App\Play\PlayNotConfigured;
use App\Play\PlayProduct;
use App\Play\PlayPurchaseNotFound;
use App\Play\PlayUnavailable;
use App\Play\PurchaseStatus;
use Google\Client as GoogleClient;
use Google\Service\AndroidPublisher;
use GuzzleHttp\Client as GuzzleClient;
use GuzzleHttp\Exception\ConnectException;
use GuzzleHttp\Handler\MockHandler;
use GuzzleHttp\HandlerStack;
use GuzzleHttp\Middleware;
use GuzzleHttp\Psr7\Request;
use GuzzleHttp\Psr7\Response;
use Illuminate\Support\Facades\Log;
use Tests\TestCase;

/**
 * GooglePlayPurchaseVerifier di atas klien Google ASLI dengan HTTP palsu: yang diuji adalah
 * penguraian jawaban dan pemetaan ke status internal. Bentuk JSON di bawah disusun dari model
 * vendor/google/apiclient-services (AndroidPublisher) dan dokumentasi resminya, BUKAN dari jawaban
 * Play sungguhan (tidak ada kredensial Play di sini).
 */
class GooglePlayPurchaseVerifierTest extends TestCase
{
    private const PACKAGE = 'com.roziqrizal.rizqflow';

    private const TOKEN = 'rahasia-token-pembelian-123';

    /** @var list<array{request: Request}> */
    private array $history = [];

    private function verifier(Response|\Throwable ...$responses): GooglePlayPurchaseVerifier
    {
        $this->history = [];
        $stack = HandlerStack::create(new MockHandler($responses));
        $stack->push(Middleware::history($this->history));

        $client = new GoogleClient;
        $client->setHttpClient(new GuzzleClient(['handler' => $stack, 'http_errors' => false]));
        $service = new AndroidPublisher($client);

        return new GooglePlayPurchaseVerifier(self::PACKAGE, fn () => $service);
    }

    private function playJson(array $body, int $status = 200): Response
    {
        return new Response($status, ['Content-Type' => 'application/json'], json_encode($body));
    }

    private function subscription(string $state, array $lineItem = [], array $extra = []): Response
    {
        return $this->playJson($extra + [
            'kind' => 'androidpublisher#subscriptionPurchaseV2',
            'subscriptionState' => $state,
            'acknowledgementState' => 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
            'lineItems' => [$lineItem + [
                'productId' => 'rizqflow_sync',
                'expiryTime' => '2026-11-24T10:00:00.123Z',
                'autoRenewingPlan' => ['autoRenewEnabled' => true],
            ]],
        ]);
    }

    private function product(string $state, array $offerDetails = ['quantity' => 1, 'refundableQuantity' => 1], string $productId = 'rizqflow_pro'): Response
    {
        return $this->playJson([
            'kind' => 'androidpublisher#productPurchaseV2',
            'orderId' => 'GPA.0000-0000-0000-00000',
            'acknowledgementState' => 'ACKNOWLEDGEMENT_STATE_PENDING',
            'purchaseStateContext' => ['purchaseState' => $state],
            'productLineItem' => [['productId' => $productId, 'productOfferDetails' => $offerDetails]],
        ]);
    }

    public function test_active_subscription_maps_expiry_auto_renew_and_acknowledgement(): void
    {
        $v = $this->verifier($this->subscription('SUBSCRIPTION_STATE_ACTIVE'))->verify(PlayProduct::Sync, self::TOKEN);

        $this->assertSame('rizqflow_sync', $v->productId);
        $this->assertSame(PurchaseStatus::Active, $v->status);
        $this->assertSame('2026-11-24 10:00:00', $v->expiresAt->format('Y-m-d H:i:s'));
        $this->assertTrue($v->autoRenewing);
        $this->assertTrue($v->acknowledged);
    }

    public function test_request_goes_to_the_subscriptionsv2_endpoint_with_our_package_name(): void
    {
        $this->verifier($this->subscription('SUBSCRIPTION_STATE_ACTIVE'))->verify(PlayProduct::Sync, self::TOKEN);

        $uri = (string) $this->history[0]['request']->getUri();
        $this->assertStringContainsString('/applications/'.self::PACKAGE.'/purchases/subscriptionsv2/tokens/', $uri);
    }

    public function test_every_subscription_state_maps_to_the_expected_internal_status(): void
    {
        $expected = [
            'SUBSCRIPTION_STATE_ACTIVE' => PurchaseStatus::Active,
            'SUBSCRIPTION_STATE_CANCELED' => PurchaseStatus::Canceled,
            'SUBSCRIPTION_STATE_IN_GRACE_PERIOD' => PurchaseStatus::GracePeriod,
            'SUBSCRIPTION_STATE_ON_HOLD' => PurchaseStatus::OnHold,
            'SUBSCRIPTION_STATE_PAUSED' => PurchaseStatus::Paused,
            'SUBSCRIPTION_STATE_PENDING' => PurchaseStatus::Pending,
            'SUBSCRIPTION_STATE_EXPIRED' => PurchaseStatus::Expired,
            'SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED' => PurchaseStatus::Expired,
        ];

        foreach ($expected as $state => $status) {
            $v = $this->verifier($this->subscription($state))->verify(PlayProduct::Sync, self::TOKEN);
            $this->assertSame($status, $v->status, $state);
        }
    }

    public function test_unspecified_subscription_state_is_unverifiable_not_a_guess(): void
    {
        $this->expectException(PlayUnavailable::class);

        $this->verifier($this->subscription('SUBSCRIPTION_STATE_UNSPECIFIED'))->verify(PlayProduct::Sync, self::TOKEN);
    }

    public function test_entitled_subscription_state_without_expiry_is_unverifiable(): void
    {
        $response = $this->playJson([
            'subscriptionState' => 'SUBSCRIPTION_STATE_ACTIVE',
            'lineItems' => [['productId' => 'rizqflow_sync']],
        ]);

        $this->expectException(PlayUnavailable::class);

        $this->verifier($response)->verify(PlayProduct::Sync, self::TOKEN);
    }

    public function test_subscription_picks_the_line_item_for_the_claimed_product(): void
    {
        $response = $this->playJson([
            'subscriptionState' => 'SUBSCRIPTION_STATE_ACTIVE',
            'lineItems' => [
                ['productId' => 'produk_lain', 'expiryTime' => '2030-01-01T00:00:00Z'],
                ['productId' => 'rizqflow_sync', 'expiryTime' => '2026-12-01T00:00:00Z'],
            ],
        ]);

        $v = $this->verifier($response)->verify(PlayProduct::Sync, self::TOKEN);

        $this->assertSame('rizqflow_sync', $v->productId);
        $this->assertSame('2026-12-01', $v->expiresAt->format('Y-m-d'));
    }

    public function test_subscription_reports_the_linked_purchase_token_as_the_predecessor(): void
    {
        $v = $this->verifier($this->subscription('SUBSCRIPTION_STATE_ACTIVE', extra: ['linkedPurchaseToken' => 'tok-lama']))
            ->verify(PlayProduct::Sync, self::TOKEN);

        $this->assertSame('tok-lama', $v->predecessorToken);
    }

    public function test_subscription_reports_the_expired_out_of_app_token_as_the_predecessor_when_there_is_no_linked_token(): void
    {
        $v = $this->verifier($this->subscription('SUBSCRIPTION_STATE_ACTIVE', extra: [
            'outOfAppPurchaseContext' => ['expiredPurchaseToken' => 'tok-lapsed'],
        ]))->verify(PlayProduct::Sync, self::TOKEN);

        $this->assertSame('tok-lapsed', $v->predecessorToken);
    }

    public function test_linked_purchase_token_is_preferred_over_the_expired_out_of_app_token(): void
    {
        $v = $this->verifier($this->subscription('SUBSCRIPTION_STATE_ACTIVE', extra: [
            'linkedPurchaseToken' => 'tok-linked',
            'outOfAppPurchaseContext' => ['expiredPurchaseToken' => 'tok-expired'],
        ]))->verify(PlayProduct::Sync, self::TOKEN);

        $this->assertSame('tok-linked', $v->predecessorToken);
    }

    public function test_subscription_without_any_chain_field_has_a_null_predecessor(): void
    {
        $v = $this->verifier($this->subscription('SUBSCRIPTION_STATE_ACTIVE'))->verify(PlayProduct::Sync, self::TOKEN);

        $this->assertNull($v->predecessorToken);
    }

    public function test_one_time_product_never_reports_a_predecessor_token(): void
    {
        // Play API tidak punya field rantai untuk produk sekali beli (INAPP).
        $v = $this->verifier($this->product('PURCHASED'))->verify(PlayProduct::Pro, self::TOKEN);

        $this->assertNull($v->predecessorToken);
    }

    public function test_subscription_for_a_different_product_reports_that_product(): void
    {
        $response = $this->subscription('SUBSCRIPTION_STATE_ACTIVE', ['productId' => 'produk_lain']);

        $v = $this->verifier($response)->verify(PlayProduct::Sync, self::TOKEN);

        $this->assertSame('produk_lain', $v->productId);
        $this->assertNotSame(PlayProduct::Sync->value, $v->productId);
    }

    public function test_purchased_one_time_product_maps_to_purchased(): void
    {
        $v = $this->verifier($this->product('PURCHASED'))->verify(PlayProduct::Pro, self::TOKEN);

        $this->assertSame('rizqflow_pro', $v->productId);
        $this->assertSame(PurchaseStatus::Purchased, $v->status);
        $this->assertNull($v->expiresAt);
        $this->assertFalse($v->acknowledged);
    }

    public function test_one_time_request_goes_to_the_productsv2_endpoint(): void
    {
        $this->verifier($this->product('PURCHASED'))->verify(PlayProduct::Pro, self::TOKEN);

        $uri = (string) $this->history[0]['request']->getUri();
        $this->assertStringContainsString('/applications/'.self::PACKAGE.'/purchases/productsv2/tokens/', $uri);
    }

    public function test_one_time_pending_cancelled_and_refunded_are_not_purchased(): void
    {
        $pending = $this->verifier($this->product('PENDING'))->verify(PlayProduct::Pro, self::TOKEN);
        $cancelled = $this->verifier($this->product('CANCELLED'))->verify(PlayProduct::Pro, self::TOKEN);
        $refunded = $this->verifier($this->product('PURCHASED', ['quantity' => 1, 'refundableQuantity' => 0]))
            ->verify(PlayProduct::Pro, self::TOKEN);

        $this->assertSame(PurchaseStatus::Pending, $pending->status);
        $this->assertSame(PurchaseStatus::Refunded, $cancelled->status);
        $this->assertSame(PurchaseStatus::Refunded, $refunded->status);
    }

    public function test_one_time_purchase_without_refundable_quantity_is_still_purchased(): void
    {
        $v = $this->verifier($this->product('PURCHASED', ['quantity' => 1]))->verify(PlayProduct::Pro, self::TOKEN);

        $this->assertSame(PurchaseStatus::Purchased, $v->status);
    }

    public function test_one_time_unknown_state_is_unverifiable(): void
    {
        $this->expectException(PlayUnavailable::class);

        $this->verifier($this->product('PURCHASE_STATE_UNSPECIFIED'))->verify(PlayProduct::Pro, self::TOKEN);
    }

    public function test_one_time_product_reports_the_product_play_returned(): void
    {
        $v = $this->verifier($this->product('PURCHASED', productId: 'produk_lain'))->verify(PlayProduct::Pro, self::TOKEN);

        $this->assertSame('produk_lain', $v->productId);
    }

    public function test_http_400_and_404_mean_play_does_not_know_the_token(): void
    {
        foreach ([400, 404] as $status) {
            try {
                $this->verifier($this->playJson(['error' => ['code' => $status, 'message' => 'tidak ditemukan']], $status))
                    ->verify(PlayProduct::Sync, self::TOKEN);
                $this->fail("HTTP {$status} harus menjadi PlayPurchaseNotFound");
            } catch (PlayPurchaseNotFound) {
                $this->addToAssertionCount(1);
            }
        }
    }

    public function test_http_410_for_a_subscription_means_definitively_expired(): void
    {
        $v = $this->verifier($this->playJson(['error' => ['code' => 410, 'message' => 'gone']], 410))
            ->verify(PlayProduct::Sync, self::TOKEN);

        $this->assertSame(PurchaseStatus::Expired, $v->status);
        $this->assertSame('rizqflow_sync', $v->productId);
    }

    public function test_server_errors_credential_rejections_and_quota_are_unverifiable(): void
    {
        foreach ([401, 403, 429, 500, 503] as $status) {
            try {
                $this->verifier($this->playJson(['error' => ['code' => $status, 'message' => 'x']], $status))
                    ->verify(PlayProduct::Sync, self::TOKEN);
                $this->fail("HTTP {$status} harus menjadi PlayUnavailable");
            } catch (PlayUnavailable) {
                $this->addToAssertionCount(1);
            }
        }
    }

    public function test_network_failure_is_unverifiable(): void
    {
        $this->expectException(PlayUnavailable::class);

        $this->verifier(new ConnectException('cURL error 6', new Request('GET', 'https://example.test')))
            ->verify(PlayProduct::Pro, self::TOKEN);
    }

    public function test_purchase_token_never_reaches_logs_or_exception_messages(): void
    {
        $logged = [];
        Log::shouldReceive('warning', 'error')->andReturnUsing(function (...$args) use (&$logged) {
            $logged[] = json_encode($args);
        });

        // Pesan Guzzle asli memuat URL (dan karenanya token); tidak boleh sampai ke log atau pengecualian kita.
        $leaky = new ConnectException(
            'cURL error 6: Could not resolve host for https://androidpublisher.googleapis.com/x/tokens/'.self::TOKEN,
            new Request('GET', 'https://androidpublisher.googleapis.com/x/tokens/'.self::TOKEN),
        );

        try {
            $this->verifier($leaky)->verify(PlayProduct::Pro, self::TOKEN);
            $this->fail('Harus melempar PlayUnavailable');
        } catch (PlayUnavailable $e) {
            $this->assertStringNotContainsString(self::TOKEN, $e->getMessage());
            $this->assertNull($e->getPrevious());
        }

        $this->assertNotEmpty($logged);
        $this->assertStringNotContainsString(self::TOKEN, implode("\n", $logged));
    }

    public function test_expiry_time_with_a_non_utc_offset_is_converted_to_the_app_timezone(): void
    {
        // Eloquent menyimpan jam dinding Carbon tanpa memperhatikan zonanya, jadi expiresAt HARUS sudah
        // berada di zona aplikasi: 17:00 di +07:00 adalah 10:00 UTC, bukan 17:00.
        $response = fn () => $this->subscription('SUBSCRIPTION_STATE_ACTIVE', ['expiryTime' => '2026-11-24T17:00:00+07:00']);

        config(['app.timezone' => 'UTC']);
        $utc = $this->verifier($response())->verify(PlayProduct::Sync, self::TOKEN);
        config(['app.timezone' => 'Asia/Jakarta']);
        $jakarta = $this->verifier($response())->verify(PlayProduct::Sync, self::TOKEN);

        $this->assertSame('2026-11-24 10:00:00', $utc->expiresAt->format('Y-m-d H:i:s'));
        $this->assertSame('2026-11-24 17:00:00', $jakarta->expiresAt->format('Y-m-d H:i:s'));
        $this->assertTrue($utc->expiresAt->equalTo($jakarta->expiresAt), 'instan yang sama');
    }

    public function test_purchase_token_never_reaches_logs_or_messages_on_any_http_error_branch(): void
    {
        $logged = [];
        Log::shouldReceive('warning', 'error')->andReturnUsing(function (...$args) use (&$logged) {
            $logged[] = json_encode($args);
        });

        // Badan galat Play bisa memuat token (Google\Service\Exception memakai badan jawaban sebagai pesannya).
        foreach ([400, 401, 403, 404, 410, 429, 500, 503] as $status) {
            foreach ([PlayProduct::Sync, PlayProduct::Pro] as $product) {
                $body = ['error' => ['code' => $status, 'message' => 'token '.self::TOKEN.' ditolak']];
                try {
                    $this->verifier($this->playJson($body, $status))->verify($product, self::TOKEN);
                } catch (PlayException $e) {
                    $this->assertStringNotContainsString(self::TOKEN, $e->getMessage(), "HTTP {$status}");
                    $this->assertNull($e->getPrevious());
                }
            }
        }

        $this->assertNotEmpty($logged, 'kegagalan 401/403/429/5xx dicatat');
        $this->assertStringNotContainsString(self::TOKEN, implode("\n", $logged));
    }

    public function test_missing_credentials_surface_as_not_configured_and_clients_are_built_lazily(): void
    {
        $built = 0;
        $verifier = new GooglePlayPurchaseVerifier(self::PACKAGE, function () use (&$built) {
            $built++;
            throw new PlayNotConfigured;
        });

        $this->assertSame(0, $built);

        $this->expectException(PlayNotConfigured::class);
        $verifier->ensureConfigured();
    }
}
