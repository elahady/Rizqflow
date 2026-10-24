<?php

namespace App\Play;

use App\Support\Plan;

/**
 * Produk Play yang dikenal server beserta paket yang dibukanya. ID produk harus sama dengan yang
 * dipakai aplikasi Android (BillingGateway): `rizqflow_pro` produk sekali beli (INAPP) dan
 * `rizqflow_sync` langganan (SUBS, base plan monthly dan yearly sama-sama membuka Sync).
 */
enum PlayProduct: string
{
    case Pro = 'rizqflow_pro';
    case Sync = 'rizqflow_sync';

    public function plan(): Plan
    {
        return match ($this) {
            self::Pro => Plan::Pro,
            self::Sync => Plan::Sync,
        };
    }

    /** Langganan diverifikasi lewat subscriptionsv2; produk sekali beli lewat productsv2. */
    public function isSubscription(): bool
    {
        return $this === self::Sync;
    }

    /** @return list<string> ID produk yang berupa langganan. */
    public static function subscriptionIds(): array
    {
        return array_values(array_map(
            fn (self $product) => $product->value,
            array_filter(self::cases(), fn (self $product) => $product->isSubscription()),
        ));
    }
}
