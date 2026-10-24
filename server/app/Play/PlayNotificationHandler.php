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
 * lain, testNotification, dan token yang belum pernah diklaim (aplikasi akan mengklaimnya sendiri
 * dan mendapat status terbaru).
 */
class PlayNotificationHandler
{
    public function __construct(private readonly PlayPurchaseRefresher $refresher) {}

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
        if ($purchase === null) {
            return; // belum pernah diklaim
        }

        $this->refresher->refresh($purchase);
    }
}
