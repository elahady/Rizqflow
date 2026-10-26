<?php

namespace App\Play;

use App\Models\PlayPurchase;

/**
 * Isi minimal sebuah Real-time Developer Notification yang kita pedulikan: paket aplikasi dan
 * token pembelian yang berubah. Jenis dan tipe notifikasinya sengaja TIDAK dibaca: notifikasi
 * hanya penunjuk "token ini berubah"; status sebenarnya selalu diambil ulang dari Play.
 *
 * Pengecualian: `$product` (dari `subscriptionId`/`sku` notifikasi) TIDAK dipakai sebagai status,
 * hanya supaya PlayNotificationHandler tahu produk mana yang harus ditanyakan ke Play ketika token
 * di notifikasi ini belum pernah diklaim siapa pun — satu-satunya cara untuk tahu apakah token itu
 * penerus token lain lewat rantai linkedPurchaseToken (lihat PlayPurchaseChainResolver). Untuk token
 * yang sudah tercatat, produk yang dipakai tetap product_id baris itu, tidak pernah ini.
 */
final class PlayNotification
{
    /** Batas ukuran `message.data` (base64): pesan RTDN yang sah jauh lebih kecil. */
    private const MAX_DATA_LENGTH = 65536;

    /** Field pembawa ID produk per jenis notifikasi; null berarti jenis itu tidak membawanya. */
    private const PRODUCT_FIELDS = [
        'subscriptionNotification' => 'subscriptionId',
        'oneTimeProductNotification' => 'sku',
        'voidedPurchaseNotification' => null,
    ];

    private function __construct(
        public readonly string $packageName,
        public readonly ?string $purchaseToken,
        public readonly ?PlayProduct $product = null,
    ) {}

    /**
     * Urai badan push Pub/Sub: {"message":{"data":"<base64 JSON DeveloperNotification>",...}}.
     * Mengembalikan null bila bentuknya rusak. Untuk testNotification (atau jenis lain tanpa token),
     * purchaseToken null.
     *
     * @param  array<string, mixed>  $body
     */
    public static function fromPubSub(array $body): ?self
    {
        $message = $body['message'] ?? null;
        $data = is_array($message) ? ($message['data'] ?? null) : null;
        if (! is_string($data) || $data === '' || strlen($data) > self::MAX_DATA_LENGTH) {
            return null;
        }

        $json = base64_decode($data, true);
        $notification = $json === false ? null : json_decode($json, true);
        if (! is_array($notification) || ! is_string($notification['packageName'] ?? null)) {
            return null;
        }

        $token = null;
        $product = null;
        foreach (self::PRODUCT_FIELDS as $kind => $productField) {
            $payload = is_array($notification[$kind] ?? null) ? $notification[$kind] : null;
            $candidate = $payload['purchaseToken'] ?? null;
            if (! is_string($candidate) || $candidate === '' || strlen($candidate) > PlayPurchase::MAX_TOKEN_LENGTH) {
                continue;
            }
            $token = $candidate;
            if ($productField !== null && is_string($payload[$productField] ?? null)) {
                $product = PlayProduct::tryFrom($payload[$productField]);
            }
            break;
        }

        return new self($notification['packageName'], $token, $product);
    }
}
