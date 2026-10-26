<?php

namespace Tests\Support;

use App\Play\PlayNotConfigured;
use App\Play\PlayProduct;
use App\Play\PlayPurchaseNotFound;
use App\Play\PlayPurchaseVerifier;
use App\Play\PlayVerification;
use App\Play\PurchaseStatus;
use Carbon\CarbonImmutable;
use Closure;
use Throwable;

/**
 * Pengganti PlayPurchaseVerifier untuk tes (tanpa jaringan): jawaban per token diatur lewat
 * respond(); token yang tidak diatur dijawab "tak dikenal Play". Semua panggilan dicatat di $calls.
 */
class FakePlayPurchaseVerifier implements PlayPurchaseVerifier
{
    public bool $configured = true;

    /** @var list<array{0: PlayProduct, 1: string}> */
    public array $calls = [];

    /** Dipanggil di awal verify(), mis. untuk menyisipkan klaim pesaing di tengah balapan. */
    public ?Closure $beforeVerify = null;

    /** @var array<string, PlayVerification|Throwable> */
    private array $responses = [];

    public function respond(string $token, PlayVerification|Throwable $response): static
    {
        $this->responses[$token] = $response;

        return $this;
    }

    public function ensureConfigured(): void
    {
        if (! $this->configured) {
            throw new PlayNotConfigured;
        }
    }

    public function verify(PlayProduct $expected, string $purchaseToken): PlayVerification
    {
        $this->ensureConfigured();
        $this->calls[] = [$expected, $purchaseToken];

        if ($this->beforeVerify !== null) {
            ($this->beforeVerify)($purchaseToken);
        }

        $response = $this->responses[$purchaseToken] ?? throw new PlayPurchaseNotFound;
        if ($response instanceof Throwable) {
            throw $response;
        }

        return $response;
    }

    public static function pro(PurchaseStatus $status = PurchaseStatus::Purchased): PlayVerification
    {
        return new PlayVerification(PlayProduct::Pro->value, $status, null, null, true);
    }

    /** @param ?string $predecessorToken token pendahulu yang "dilaporkan Play" (linkedPurchaseToken/expiredPurchaseToken), untuk tes rantai token. */
    public static function sync(PurchaseStatus $status = PurchaseStatus::Active, ?CarbonImmutable $expiresAt = null, ?string $predecessorToken = null): PlayVerification
    {
        return new PlayVerification(PlayProduct::Sync->value, $status, $expiresAt ?? CarbonImmutable::now()->addMonth(), true, true, $predecessorToken);
    }
}
