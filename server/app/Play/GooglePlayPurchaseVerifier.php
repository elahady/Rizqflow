<?php

namespace App\Play;

use Carbon\CarbonImmutable;
use Closure;
use Google\Service\AndroidPublisher;
use Google\Service\AndroidPublisher\ProductLineItem;
use Google\Service\AndroidPublisher\ProductPurchaseV2;
use Google\Service\AndroidPublisher\SubscriptionPurchaseLineItem;
use Google\Service\AndroidPublisher\SubscriptionPurchaseV2;
use Google\Service\Exception as GoogleServiceException;
use Illuminate\Support\Facades\Log;
use Throwable;

/**
 * Verifikasi lewat Google Play Developer API (`Google\Service\AndroidPublisher`):
 * - langganan: `purchases.subscriptionsv2.get` (subscriptionState + lineItems[].expiryTime);
 * - produk sekali beli: `purchases.productsv2.getproductpurchasev2` (purchaseStateContext +
 *   productLineItem[].productId), yang cukup dengan token (tanpa productId di URL) dan tetap
 *   melaporkan produknya sehingga bisa dicocokkan dengan yang diklaim.
 *
 * Jawaban Play tidak memuat packageName; paket diperiksa lewat URL permintaan (token milik paket
 * lain dijawab 400/404 oleh Play, lalu menjadi PlayPurchaseNotFound). Klien Google dibangun malas
 * lewat $serviceFactory supaya server tanpa kredensial tetap bisa jalan (PlayNotConfigured).
 *
 * Pesan pengecualian Google/Guzzle tidak pernah dicatat atau disambung: memuat URL, dan token ada
 * di URL itu. Yang dicatat hanya kelas galat dan kode HTTP.
 */
class GooglePlayPurchaseVerifier implements PlayPurchaseVerifier
{
    private ?AndroidPublisher $service = null;

    /** @param Closure(): AndroidPublisher $serviceFactory melempar PlayNotConfigured bila kredensial belum ada */
    public function __construct(
        private readonly string $packageName,
        private readonly Closure $serviceFactory,
    ) {}

    public function ensureConfigured(): void
    {
        $this->service();
    }

    public function verify(PlayProduct $expected, string $purchaseToken): PlayVerification
    {
        $service = $this->service();

        try {
            return $expected->isSubscription()
                ? $this->verifySubscription($service, $expected, $purchaseToken)
                : $this->verifyOneTime($service, $expected, $purchaseToken);
        } catch (PlayException $e) {
            throw $e;
        } catch (GoogleServiceException $e) {
            $code = (int) $e->getCode();

            // 410 untuk langganan: "tidak lagi tersedia untuk ditanyakan karena sudah lama
            // kedaluwarsa". Itu jawaban pasti (kedaluwarsa), bukan galat sementara.
            if ($code === 410 && $expected->isSubscription()) {
                return new PlayVerification($expected->value, PurchaseStatus::Expired);
            }
            if (in_array($code, [400, 404, 410], true)) {
                throw new PlayPurchaseNotFound;
            }

            // 401/403 berarti akun layanan ditolak (kredensial salah atau belum diberi izin di Play Console).
            $code === 401 || $code === 403
                ? Log::error('Play API menolak akun layanan.', ['http_status' => $code])
                : Log::warning('Play API menjawab galat.', ['http_status' => $code]);

            throw new PlayUnavailable;
        } catch (Throwable $e) {
            Log::warning('Panggilan ke Play API gagal.', ['exception' => $e::class]);

            throw new PlayUnavailable;
        }
    }

    private function service(): AndroidPublisher
    {
        return $this->service ??= ($this->serviceFactory)();
    }

    private function verifySubscription(AndroidPublisher $service, PlayProduct $expected, string $token): PlayVerification
    {
        /** @var SubscriptionPurchaseV2 $purchase */
        $purchase = $service->purchases_subscriptionsv2->get($this->packageName, $token);

        $status = match ($purchase->getSubscriptionState()) {
            SubscriptionPurchaseV2::SUBSCRIPTION_STATE_SUBSCRIPTION_STATE_ACTIVE => PurchaseStatus::Active,
            SubscriptionPurchaseV2::SUBSCRIPTION_STATE_SUBSCRIPTION_STATE_CANCELED => PurchaseStatus::Canceled,
            SubscriptionPurchaseV2::SUBSCRIPTION_STATE_SUBSCRIPTION_STATE_IN_GRACE_PERIOD => PurchaseStatus::GracePeriod,
            SubscriptionPurchaseV2::SUBSCRIPTION_STATE_SUBSCRIPTION_STATE_ON_HOLD => PurchaseStatus::OnHold,
            SubscriptionPurchaseV2::SUBSCRIPTION_STATE_SUBSCRIPTION_STATE_PAUSED => PurchaseStatus::Paused,
            SubscriptionPurchaseV2::SUBSCRIPTION_STATE_SUBSCRIPTION_STATE_PENDING => PurchaseStatus::Pending,
            SubscriptionPurchaseV2::SUBSCRIPTION_STATE_SUBSCRIPTION_STATE_EXPIRED,
            SubscriptionPurchaseV2::SUBSCRIPTION_STATE_SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED => PurchaseStatus::Expired,
            // UNSPECIFIED atau nilai baru yang belum dikenal: jangan menebak.
            default => throw new PlayUnavailable('Status langganan dari Google Play tidak dikenali.'),
        };

        // Rantai token (Tahap 9, "Ikuti token langganan pengganti ke pemilik baris lama"): dilaporkan
        // Play sendiri, bukan diklaim klien. linkedPurchaseToken didahulukan karena mencakup lebih
        // banyak kasus (ganti base plan, re-signup yang belum lapsed, pindah prabayar<->otomatis);
        // outOfAppPurchaseContext.expiredPurchaseToken hanya untuk re-subscribe dari Play Store setelah
        // langganan lama sepenuhnya kedaluwarsa (linkedPurchaseToken tidak berlaku untuk kasus itu).
        $predecessorToken = $purchase->getLinkedPurchaseToken()
            ?: $purchase->getOutOfAppPurchaseContext()?->getExpiredPurchaseToken();
        $predecessorToken = ($predecessorToken === null || $predecessorToken === '') ? null : $predecessorToken;

        $items = $purchase->getLineItems() ?? [];
        /** @var SubscriptionPurchaseLineItem|null $item */
        $item = null;
        foreach ($items as $candidate) {
            if ($candidate->getProductId() === $expected->value) {
                $item = $candidate;
                break;
            }
        }

        if ($item === null) {
            // Play melaporkan produk lain (atau tak ada item): pemanggil menolak karena tak cocok.
            return new PlayVerification((string) (($items[0] ?? null)?->getProductId() ?? ''), $status, predecessorToken: $predecessorToken);
        }

        $expiresAt = self::parseTime($item->getExpiryTime());
        if ($expiresAt === null && in_array($status, [PurchaseStatus::Active, PurchaseStatus::Canceled, PurchaseStatus::GracePeriod], true)) {
            // Tanpa expiryTime tak bisa diputuskan berhak atau tidak; jangan memberi atau mencabut.
            throw new PlayUnavailable('Jawaban Google Play tidak memuat masa berlaku langganan.');
        }

        return new PlayVerification(
            $expected->value,
            $status,
            $expiresAt,
            $item->getAutoRenewingPlan()?->getAutoRenewEnabled(),
            self::acknowledged($purchase->getAcknowledgementState()),
            $predecessorToken,
        );
    }

    private function verifyOneTime(AndroidPublisher $service, PlayProduct $expected, string $token): PlayVerification
    {
        /** @var ProductPurchaseV2 $purchase */
        $purchase = $service->purchases_productsv2->getproductpurchasev2($this->packageName, $token);

        $items = $purchase->getProductLineItem() ?? [];
        /** @var ProductLineItem|null $item */
        $item = null;
        foreach ($items as $candidate) {
            if ($candidate->getProductId() === $expected->value) {
                $item = $candidate;
                break;
            }
        }
        $reported = $item ?? ($items[0] ?? null);
        // Dikembalikan dananya: jumlah yang masih bisa direfund menjadi 0.
        $refundable = $reported?->getProductOfferDetails()?->getRefundableQuantity();
        // Tidak ada predecessorToken di sini: `productsv2.getproductpurchasev2` tidak punya field
        // rantai seperti linkedPurchaseToken/expiredPurchaseToken (itu hanya ada di subscriptionsv2.get
        // di atas); produk sekali beli (INAPP) tidak mengenal konsep rantai token di Play API.

        $status = match ($purchase->getPurchaseStateContext()?->getPurchaseState()) {
            'PURCHASED' => $refundable === 0 ? PurchaseStatus::Refunded : PurchaseStatus::Purchased,
            'PENDING' => PurchaseStatus::Pending,
            'CANCELLED' => PurchaseStatus::Refunded,
            default => throw new PlayUnavailable('Status pembelian dari Google Play tidak dikenali.'),
        };

        return new PlayVerification(
            (string) ($reported?->getProductId() ?? ''),
            $status,
            null,
            null,
            self::acknowledged($purchase->getAcknowledgementState()),
        );
    }

    private static function acknowledged(?string $state): ?bool
    {
        return match ($state) {
            'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED' => true,
            'ACKNOWLEDGEMENT_STATE_PENDING' => false,
            default => null,
        };
    }

    private static function parseTime(?string $value): ?CarbonImmutable
    {
        if ($value === null || $value === '') {
            return null;
        }

        try {
            return CarbonImmutable::parse($value)->setTimezone((string) config('app.timezone', 'UTC'));
        } catch (Throwable) {
            return null;
        }
    }
}
