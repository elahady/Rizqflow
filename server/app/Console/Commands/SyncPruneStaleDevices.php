<?php

namespace App\Console\Commands;

use App\Support\SyncRetention;
use Illuminate\Console\Command;

/**
 * Buang baris `sync_devices` untuk perangkat yang tidak pernah pull dalam active_device_days (180 hari).
 * Tidak menyentuh log sinkron. Aman dijalankan berulang.
 */
class SyncPruneStaleDevices extends Command
{
    protected $signature = 'sync:prune-stale-devices';

    protected $description = 'Hapus kursor perangkat sinkron yang sudah lama tidak aktif';

    public function handle(SyncRetention $retention): int
    {
        $dihapus = $retention->pruneStaleDevices();

        $this->info("Perangkat tidak aktif dihapus: {$dihapus}.");

        return self::SUCCESS;
    }
}
