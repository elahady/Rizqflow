<?php

namespace App\Play;

use App\Models\PlayPurchase;
use Illuminate\Support\Facades\Log;

/**
 * Memproses Real-time Developer Notification yang sudah terautentikasi (pembelian baru,
 * perpanjangan, pembatalan, ditahan, kedaluwarsa, pengembalian dana, dan seterusnya).
 *
 * Isi notifikasi TIDAK dipercaya sebagai status: dipakai hanya untuk tahu token mana yang berubah,
 * lalu status diambil ulang dari Play dan baris serta users.plans diperbarui. Karena itu
 * notifikasi duplikat atau berurutan terbalik aman. Yang diabaikan (tanpa efek): paket aplikasi
 * lain, testNotification, dan token yang belum pernah diklaim DAN tidak terbukti rantai dari baris
 * yang sudah diklaim (aplikasi akan mengklaimnya sendiri dan mendapat status terbaru).
 *
 * Token BARU yang belum pernah diklaim mendapat satu kesempatan sebelum diabaikan: ditanyakan ke
 * Play (hanya bila kita tahu produknya dari notifikasi), dan bila Play melaporkan rantai
 * (linkedPurchaseToken/expiredPurchaseToken) ke token yang SUDAH diklaim seseorang, token baru itu
 * otomatis dikaitkan ke akun yang sama lewat PlayPurchaseChainResolver — Tahap 9, "Ikuti token
 * langganan pengganti ke pemilik baris lama". Tanpa rantai yang terbukti dari Play, tetap
 * diabaikan seperti sebelumnya.
 */
class PlayNotificationHandler
{
    public function __construct(
        private readonly PlayPurchaseRefresher $refresher,
        private readonly PlayPurchaseVerifier $verifier,
        private readonly PlayPurchaseChainResolver $chain,
    ) {}

    /**
     * @throws PlayNotConfigured
     * @throws PlayUnavailable Play gagal sementara: pemanggil menjawab 5xx supaya Pub/Sub mengulang
     */
    public function handle(PlayNotification $notification): void
    {
        if ($notification->packageName !== (string) config('services.play.package_name')) {
            Log::info('Notifikasi Play untuk paket lain diabaikan.');

            return;
        }

        if ($notification->purchaseToken === null) {
            return; // testNotification atau jenis tanpa token
        }

        $purchase = PlayPurchase::findByToken($notification->purchaseToken);
        if ($purchase !== null) {
            $this->refresher->refresh($purchase);

            return;
        }

        // Belum pernah diklaim siapa pun: sebelum diabaikan, periksa apakah Play sendiri melaporkan
        // ini penerus token yang sudah diklaim (lihat docblok kelas). Hanya ditanyakan ke Play bila
        // produknya dikenal dari notifikasi; selain itu tetap diabaikan seperti sebelum.
        $product = $notification->product;
        if ($product === null) {
            return;
        }

        try {
            $verification = $this->verifier->verify($product, $notification->purchaseToken);
        } catch (PlayPurchaseNotFound) {
            return; // Play juga tidak mengenal token ini: bukan rantai, diabaikan (204)
        }

        if ($verification->productId !== $product->value) {
            return; // Play melaporkan produk lain: tak bisa dipercaya sebagai rantai produk ini
        }

        $this->chain->resolve($product, $notification->purchaseToken, $verification);
    }
}
