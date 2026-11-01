<?php

namespace App\Console\Commands;

use App\Support\SyncRetention;
use Illuminate\Console\Command;

/**
 * Buang baris `sync_devices`, `sync_device_cursors`, dan `sync_scope_activity` untuk perangkat yang tidak
 * pernah pull dalam active_device_days (180 hari). Tidak menyentuh log sinkron. Aman dijalankan berulang.
 */
class SyncPruneStaleDevices extends Command
{
    protected $signature = 'sync:prune-stale-devices';

    protected $description = 'Hapus kursor perangkat sinkron yang sudah lama tidak aktif';

    public function handle(SyncRetention $retention): int
    {
        $dihapus = $retention->pruneStaleDevices();

        $this->info("Perangkat tidak aktif dihapus: {$dihapus}.");

        // Pull per cakupan (sync-desain.md bagian G): kursor per cakupan dan catatan waktu per cakupan
        // dibuang dengan batas yang sama.
        $cakupan = $retention->pruneStaleCursors();
        $this->info("Kursor cakupan tidak aktif dihapus: {$cakupan['kursor']}.");
        $this->info("Catatan waktu cakupan dihapus: {$cakupan['aktivitas']}.");

        return self::SUCCESS;
    }
}
