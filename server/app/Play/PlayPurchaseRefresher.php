<?php

namespace App\Play;

use App\Models\PlayPurchase;
use Illuminate\Contracts\Encryption\DecryptException;
use Illuminate\Support\Facades\Log;

/**
 * Verifikasi ulang baris yang sudah tercatat: dipakai notifikasi RTDN dan `play:reconcile`.
 * Selalu membaca status terbaru dari Play (bukan dari isi notifikasi), jadi urutan terbalik dan
 * duplikat aman. Log hanya memuat id baris, tidak pernah tokennya.
 */
class PlayPurchaseRefresher
{
    public function __construct(
        private readonly PlayPurchaseVerifier $verifier,
        private readonly PlanSynchronizer $plans,
    ) {}

    /**
     * @return bool true bila Play menjawab dan baris diperbarui; false bila baris dilewati tanpa
     *              perubahan (token tak terbaca, produk tak dikenal, atau Play menjawab ambigu).
     *
     * @throws PlayNotConfigured
     * @throws PlayUnavailable Play tak terjangkau atau gagal sementara: pemanggil boleh mencoba lagi
     */
    public function refresh(PlayPurchase $purchase): bool
    {
        $product = PlayProduct::tryFrom($purchase->product_id);
        if ($product === null) {
            Log::warning('Pembelian Play dengan produk yang tidak dikenal dilewati.', ['purchase_id' => $purchase->id]);

            return false;
        }

        try {
            $token = $purchase->purchase_token;
        } catch (DecryptException) {
            Log::error('Token pembelian Play tidak bisa didekripsi (APP_KEY berubah?).', ['purchase_id' => $purchase->id]);

            return false;
        }

        try {
            $verification = $this->verifier->verify($product, $token);
        } catch (PlayPurchaseNotFound) {
            // Baris ini dulu lolos verifikasi, jadi "tak dikenal" sekarang ambigu (salah konfigurasi?).
            // Jangan mencabut apa pun; biarkan baris apa adanya.
            Log::warning('Play tidak mengenal token pembelian yang tercatat; baris dibiarkan.', ['purchase_id' => $purchase->id]);

            return false;
        }

        if ($verification->productId !== $product->value) {
            Log::warning('Play melaporkan produk lain untuk token yang tercatat; baris dibiarkan.', ['purchase_id' => $purchase->id]);

            return false;
        }

        $purchase->applyVerification($verification);
        // Hanya paket baris ini yang dinilai ulang; paket lain di akun yang sama tidak ikut disentuh.
        $this->plans->sync($purchase->user, [$purchase->plan]);

        return true;
    }
}
