<?php

namespace App\Play;

use App\Models\PlayPurchase;
use Illuminate\Database\UniqueConstraintViolationException;

/**
 * Menindaklanjuti rantai token yang DILAPORKAN PLAY SENDIRI saat memverifikasi sebuah token:
 * `linkedPurchaseToken` (ganti base plan, atau re-signup langganan yang dibatalkan tapi belum
 * lapsed) dan `outOfAppPurchaseContext.expiredPurchaseToken` (berlangganan lagi dari Play Store
 * setelah langganan lama sepenuhnya kedaluwarsa) — keduanya dari `subscriptionsv2.get`, lihat
 * PlayVerification::$predecessorToken dan GooglePlayPurchaseVerifier::verifySubscription().
 *
 * Dipakai PlayNotificationHandler saat RTDN menerima token BARU yang belum pernah diklaim siapa
 * pun (Tahap 9, "Ikuti token langganan pengganti ke pemilik baris lama"): tanpa ini, pelanggan
 * yang mengganti base plan Sync (bulanan<->tahunan) atau berlangganan lagi dari Play Store tanpa
 * membuka aplikasi kehilangan Sync di server dan web sampai aplikasi dibuka dan mengklaim ulang
 * (PurchaseClaimCoordinator).
 *
 * TIDAK PERNAH mempercayai klien untuk rantai ini: `$verification->predecessorToken` hanya pernah
 * berasal dari jawaban Play API (lewat PlayPurchaseVerifier::verify, bukan dari permintaan HTTP
 * manapun). Hanya mengambil alih bila baris pendahulu SUDAH diklaim satu akun; bila pendahulu juga
 * belum pernah diklaim siapa pun, tidak ada pemilik yang bisa diikuti dan token baru tetap
 * menunggu klaim manual seperti biasa (lihat PurchaseClaimService, yang menjalankan pemeriksaan
 * rantai yang sama untuk arah sebaliknya: menolak klaim manual oleh akun lain atas token penerus
 * yang pendahulunya sudah dimiliki akun lain).
 */
class PlayPurchaseChainResolver
{
    public function __construct(private readonly PlanSynchronizer $plans) {}

    /**
     * @return bool true bila token baru berhasil dikaitkan otomatis ke pemilik baris pendahulu
     *              (baris baru dicatat dan paket akun itu disegarkan).
     */
    public function resolve(PlayProduct $product, string $token, PlayVerification $verification): bool
    {
        if ($verification->predecessorToken === null) {
            return false; // Play tidak melaporkan rantai apa pun untuk token ini
        }

        $predecessor = PlayPurchase::findByToken($verification->predecessorToken);
        if ($predecessor === null) {
            // Pendahulu juga belum pernah diklaim siapa pun: tidak ada pemilik yang bisa diikuti.
            return false;
        }

        $owner = $predecessor->user;
        if ($owner === null) {
            return false; // akun pendahulu sudah terhapus
        }

        $successor = $this->record($owner->id, $product, $token, $verification);
        if ($successor === null) {
            // Kalah balapan: token baru ini sudah tercatat milik akun lain lewat jalur lain
            // (mis. diklaim manual tepat di antara pemeriksaan ini).
            return false;
        }

        // Jejak audit di baris lama: tidak pernah dibaca ulang untuk memutuskan pemilik mana pun.
        $predecessor->markSupersededBy($successor->token_hash);

        $this->plans->sync($owner, [$product->plan()]);

        return true;
    }

    private function record(int $userId, PlayProduct $product, string $token, PlayVerification $verification): ?PlayPurchase
    {
        try {
            return PlayPurchase::create([
                'user_id' => $userId,
                'plan' => $product->plan(),
                'product_id' => $product->value,
                'token_hash' => PlayPurchase::hashToken($token),
                'purchase_token' => $token,
                'status' => $verification->status,
                'expires_at' => $verification->expiresAt,
                'auto_renewing' => $verification->autoRenewing,
                'acknowledged' => $verification->acknowledged,
                'last_verified_at' => now(),
            ]);
        } catch (UniqueConstraintViolationException) {
            $winner = PlayPurchase::findByToken($token);

            return $winner !== null && (int) $winner->user_id === $userId ? $winner : null;
        }
    }
}
