<?php

namespace Tests\Feature\Sync;

use App\Models\SyncDevice;
use App\Models\SyncLogEntry;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Tests\TestCase;

/** `sync:prune-stale-devices` (Tahap 7): buang kursor perangkat yang tidak aktif 180 hari. */
class SyncPruneStaleDevicesCommandTest extends TestCase
{
    use RefreshDatabase;

    private function perangkat(User $user, string $deviceId, int $hariLalu): void
    {
        SyncDevice::create([
            'user_id' => $user->id,
            'device_id' => $deviceId,
            'after_id' => 0,
            'last_seen_at' => now()->utc()->subDays($hariLalu),
        ]);
    }

    public function test_perangkat_yang_tidak_aktif_lebih_dari_180_hari_dibuang(): void
    {
        $user = User::factory()->create(['plans' => ['sync']]);
        $this->perangkat($user, 'pixel-lama', 200);
        $this->perangkat($user, 'pixel-baru', 10);

        $this->artisan('sync:prune-stale-devices')->expectsOutputToContain('Perangkat tidak aktif dihapus: 1')->assertSuccessful();

        $this->assertDatabaseMissing('sync_devices', ['device_id' => 'pixel-lama']);
        $this->assertDatabaseHas('sync_devices', ['device_id' => 'pixel-baru']);
    }

    public function test_prune_tidak_menyentuh_log_sinkron(): void
    {
        $user = User::factory()->create(['plans' => ['sync']]);
        SyncLogEntry::create([
            'user_id' => $user->id,
            'room_id' => null,
            'record_type' => 'transaction',
            'record_id' => 'tx-1',
            'device_id' => 'pixel-lama',
            'payload' => 'cipher',
            'deleted' => false,
            'changed_at' => now(),
            'changed_at_ms' => 1000,
        ]);
        $this->perangkat($user, 'pixel-lama', 400);

        $this->artisan('sync:prune-stale-devices')->assertSuccessful();

        $this->assertSame(1, SyncLogEntry::count());
    }

    public function test_prune_idempoten(): void
    {
        $user = User::factory()->create(['plans' => ['sync']]);
        $this->perangkat($user, 'pixel-lama', 200);

        $this->artisan('sync:prune-stale-devices')->assertSuccessful();
        $this->artisan('sync:prune-stale-devices')->expectsOutputToContain('Perangkat tidak aktif dihapus: 0')->assertSuccessful();

        $this->assertDatabaseCount('sync_devices', 0);
    }
}
