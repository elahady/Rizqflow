<?php

namespace App\Console\Commands;

use App\Models\PlayPurchase;
use App\Play\PlayNotConfigured;
use App\Play\PlayPurchaseRefresher;
use App\Play\PlayPurchaseVerifier;
use Illuminate\Console\Command;
use Illuminate\Support\Facades\Log;
use Throwable;

/**
 * Verifikasi ulang pembelian Play yang sudah atau hampir lewat masa berlakunya (atau yang
 * verifikasinya sudah basi), supaya langganan yang kedaluwarsa tanpa notifikasi RTDN tetap
 * dicabut dan perpanjangan yang notifikasinya hilang tetap diberi. Kriteria pemilihan ada di
 * PlayPurchase::scopeDueForReconcile. Kegagalan menghubungi Play tidak pernah mengubah paket.
 * Urutan giliran ada di PlayPurchase::scopeInReconcileOrder: tiap percobaan dicatat di
 * last_checked_at, jadi baris yang terus dilewati atau gagal pindah ke belakang antrean dan tidak
 * menutup baris lain bila jumlahnya mencapai --limit.
 *
 * Dijadwalkan harian di routes/console.php, tetapi penjadwal Laravel baru jalan bila ada cron
 * `schedule:run` di server (lihat DEPLOY.md "Pembelian Google Play"); tanpa itu jalankan perintah
 * ini sendiri.
 */
class PlayReconcile extends Command
{
    protected $signature = 'play:reconcile {--limit=200 : Jumlah pembelian maksimum yang diverifikasi ulang per jalan}';

    protected $description = 'Verifikasi ulang pembelian Google Play yang hampir atau sudah lewat masa berlaku';

    public function handle(PlayPurchaseVerifier $verifier, PlayPurchaseRefresher $refresher): int
    {
        try {
            $verifier->ensureConfigured();
        } catch (PlayNotConfigured $e) {
            // Bukan galat: server tanpa kredensial memang tidak memverifikasi apa pun.
            $this->warn($e->getMessage().' Dilewati.');

            return self::SUCCESS;
        }

        $limit = max(1, (int) $this->option('limit'));
        $due = PlayPurchase::dueForReconcile(now())->inReconcileOrder()->limit($limit)->get();

        $updated = 0;
        $skipped = 0;
        $failed = 0;

        foreach ($due as $purchase) {
            // Dicatat sebelum mencoba, apa pun hasilnya (berhasil, dilewati, atau gagal): baris yang
            // terus macet pindah ke belakang antrean dan tidak menutup baris lain di jalan berikutnya.
            $purchase->markChecked();

            try {
                $refresher->refresh($purchase) ? $updated++ : $skipped++;
            } catch (Throwable $e) {
                // Play gagal sementara untuk baris ini: biarkan apa adanya, coba lagi di jalan berikutnya.
                // Pesan pengecualian tidak dicatat (bisa memuat URL berisi token), hanya kelasnya.
                $failed++;
                Log::warning('play:reconcile gagal memverifikasi satu pembelian.', [
                    'purchase_id' => $purchase->id,
                    'exception' => $e::class,
                ]);
            }
        }

        $this->info("Diperiksa {$due->count()}: diperbarui {$updated}, dilewati {$skipped}, gagal {$failed}.");

        return $failed > 0 ? self::FAILURE : self::SUCCESS;
    }
}
