<?php

namespace App\Console\Commands;

use App\Support\UserMetrics;
use Illuminate\Console\Command;

/**
 * Metrik pengguna anonim (Tahap 9): jumlah akun server, Pro, dan Sync. Tanpa `--simpan` hanya
 * menampilkan angka; dengan `--simpan` juga menulis potret hari ini (UTC) ke `user_metric_snapshots`.
 * Dijadwalkan harian dengan `--simpan` di routes/console.php (baru berjalan bila ada cron
 * `schedule:run`, Tahap 12). Tidak ada identitas atau data keuangan di keluaran maupun tabelnya.
 */
class MetrikPengguna extends Command
{
    protected $signature = 'metrik:pengguna {--simpan : Simpan potret hari ini ke user_metric_snapshots}';

    protected $description = 'Tampilkan (dan simpan) jumlah pengguna, Pro, dan Sync tanpa data pribadi';

    public function handle(UserMetrics $metrics): int
    {
        $angka = $this->option('simpan')
            ? $metrics->simpan(now())->only(['pengguna_total', 'pengguna_pro', 'pengguna_sync'])
            : $metrics->hitung();

        $this->info("Pengguna: {$angka['pengguna_total']}. Pro: {$angka['pengguna_pro']}. Sync: {$angka['pengguna_sync']}.");
        if ($this->option('simpan')) {
            $this->info('Potret hari ini disimpan.');
        }

        return self::SUCCESS;
    }
}
