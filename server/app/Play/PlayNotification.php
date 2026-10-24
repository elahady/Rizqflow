<?php

namespace App\Play;

use App\Models\PlayPurchase;

/**
 * Isi minimal sebuah Real-time Developer Notification yang kita pedulikan: paket aplikasi dan
 * token pembelian yang berubah. Jenis dan tipe notifikasinya sengaja TIDAK dibaca: notifikasi
 * hanya penunjuk "token ini berubah"; status sebenarnya selalu diambil ulang dari Play.
 */
final class PlayNotification
{
    /** Batas ukuran `message.data` (base64): pesan RTDN yang sah jauh lebih kecil. */
    private const MAX_DATA_LENGTH = 65536;

    private function __construct(
        public readonly string $packageName,
        public readonly ?string $purchaseToken,
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
        foreach (['subscriptionNotification', 'oneTimeProductNotification', 'voidedPurchaseNotification'] as $kind) {
            $candidate = is_array($notification[$kind] ?? null) ? ($notification[$kind]['purchaseToken'] ?? null) : null;
            if (is_string($candidate) && $candidate !== '' && strlen($candidate) <= PlayPurchase::MAX_TOKEN_LENGTH) {
                $token = $candidate;
                break;
            }
        }

        return new self($notification['packageName'], $token);
    }
}
