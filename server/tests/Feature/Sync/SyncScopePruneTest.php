<?php

namespace Tests\Feature\Sync;

use App\Models\SyncDevice;
use App\Models\SyncDeviceCursor;
use App\Models\SyncLogEntry;
use App\Models\SyncScopeActivity;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Tests\TestCase;

/** `sync:prune-stale-devices` ikut membuang kursor per cakupan dan catatan waktu per cakupan yang tidak aktif. */
class SyncScopePruneTest extends TestCase
{
    use RefreshDatabase;

    private function kursor(User $user, string $device, string $room, int $hariLalu): void
    {
        SyncDeviceCursor::create([
            'user_id' => $user->id,
            'device_id' => $device,
            'room_id' => $room,
            'after_id' => 0,
            'last_seen_at' => now()->utc()->subDays($hariLalu),
        ]);
    }

    private function aktivitas(User $user, string $device, string $room, ?int $pullHariLalu, ?int $pushHariLalu): void
    {
        SyncScopeActivity::create([
            'user_id' => $user->id,
            'device_id' => $device,
            'room_id' => $room,
            'last_pull_at' => $pullHariLalu === null ? null : now()->utc()->subDays($pullHariLalu),
            'last_push_at' => $pushHariLalu === null ? null : now()->utc()->subDays($pushHariLalu),
        ]);
    }

    public function test_kursor_dan_catatan_waktu_cakupan_yang_tidak_aktif_lebih_dari_180_hari_dibuang(): void
    {
        $user = User::factory()->create(['plans' => ['sync']]);
        $this->kursor($user, 'hp-lama', '', 200);
        $this->kursor($user, 'hp-lama', 'ruang-a', 200);
        $this->kursor($user, 'hp-baru', '', 10);
        $this->aktivitas($user, 'hp-lama', '', 200, 250);
        $this->aktivitas($user, 'hp-baru', '', 10, 400);          // pull baru, push lama: tetap
        $this->aktivitas($user, 'hp-pusher', 'ruang-a', null, 5); // hanya push baru: tetap
        $this->aktivitas($user, 'hp-kosong', 'ruang-a', null, null);

        $this->artisan('sync:prune-stale-devices')
            ->expectsOutputToContain('Kursor cakupan tidak aktif dihapus: 2')
            ->expectsOutputToContain('Catatan waktu cakupan dihapus: 2')
            ->assertSuccessful();

        $this->assertDatabaseMissing('sync_device_cursors', ['device_id' => 'hp-lama']);
        $this->assertDatabaseHas('sync_device_cursors', ['device_id' => 'hp-baru']);
        $this->assertDatabaseMissing('sync_scope_activity', ['device_id' => 'hp-lama']);
        $this->assertDatabaseMissing('sync_scope_activity', ['device_id' => 'hp-kosong']);
        $this->assertDatabaseHas('sync_scope_activity', ['device_id' => 'hp-baru']);
        $this->assertDatabaseHas('sync_scope_activity', ['device_id' => 'hp-pusher']);
    }

    public function test_prune_tetap_membuang_kursor_global_dan_tidak_menyentuh_log(): void
    {
        $user = User::factory()->create(['plans' => ['sync']]);
        SyncDevice::create(['user_id' => $user->id, 'device_id' => 'lama', 'after_id' => 0, 'last_seen_at' => now()->utc()->subDays(200)]);
        SyncLogEntry::create([
            'user_id' => $user->id, 'room_id' => null, 'record_type' => 'r', 'record_id' => 'tx-1', 'device_id' => 'lama',
            'payload' => 'cipher', 'deleted' => false, 'changed_at' => now(), 'changed_at_ms' => 1000,
        ]);
        $this->kursor($user, 'lama', '', 200);

        $this->artisan('sync:prune-stale-devices')->expectsOutputToContain('Perangkat tidak aktif dihapus: 1')->assertSuccessful();

        $this->assertDatabaseCount('sync_devices', 0);
        $this->assertDatabaseCount('sync_device_cursors', 0);
        $this->assertSame(1, SyncLogEntry::count());
    }

    public function test_prune_idempoten_untuk_data_cakupan(): void
    {
        $user = User::factory()->create(['plans' => ['sync']]);
        $this->kursor($user, 'lama', '', 200);

        $this->artisan('sync:prune-stale-devices')->assertSuccessful();
        $this->artisan('sync:prune-stale-devices')->expectsOutputToContain('Kursor cakupan tidak aktif dihapus: 0')->assertSuccessful();
    }
}
