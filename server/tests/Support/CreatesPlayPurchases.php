<?php

namespace Tests\Support;

use App\Models\PlayPurchase;
use App\Models\User;
use App\Play\PlayProduct;
use App\Play\PurchaseStatus;
use Carbon\CarbonInterface;

/** Membuat baris play_purchases langsung (tanpa lewat klaim) untuk menyiapkan keadaan awal tes. */
trait CreatesPlayPurchases
{
    protected function makePurchase(
        User $user,
        string $token,
        PlayProduct $product = PlayProduct::Sync,
        ?PurchaseStatus $status = null,
        ?CarbonInterface $expiresAt = null,
        ?CarbonInterface $lastVerifiedAt = null,
    ): PlayPurchase {
        $status ??= $product->isSubscription() ? PurchaseStatus::Active : PurchaseStatus::Purchased;
        if ($expiresAt === null && $product->isSubscription() && $status !== PurchaseStatus::Pending) {
            $expiresAt = now()->addMonth();
        }

        return PlayPurchase::create([
            'user_id' => $user->id,
            'plan' => $product->plan(),
            'product_id' => $product->value,
            'token_hash' => PlayPurchase::hashToken($token),
            'purchase_token' => $token,
            'status' => $status,
            'expires_at' => $expiresAt,
            'last_verified_at' => $lastVerifiedAt ?? now(),
        ]);
    }
}
