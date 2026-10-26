<?php

namespace App\Console\Commands;

use App\Support\SyncRetention;
use Illuminate\Console\Command;

/**
 * Kompaksi fisik log sinkron: versi yang kalah (>= 90 hari) dan tombstone (>= 400 hari, sudah dilewati
 * semua perangkat aktif). Aturan lengkap ada di App\Support\SyncRetention. Aman dijalankan berulang.
 *
 * Dijadwalkan harian di routes/console.php; sama seperti jadwal lain, baru berjalan bila ada cron
 * `schedule:run` di server (Tahap 12).
 */
class SyncCompact extends Command
{
    protected $signature = 'sync:compact';

    protected $description = 'Hapus fisik versi sinkron yang kalah dan tombstone yang sudah dilewati semua perangkat aktif';

    public function handle(SyncRetention $retention): int
    {
        $hasil = $retention->compact();

        $this->info("Versi kalah dihapus: {$hasil['versi']}. Tombstone dihapus: {$hasil['tombstone']}.");

        return self::SUCCESS;
    }
}
