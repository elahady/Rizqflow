<?php

namespace Tests\Feature\Sync;

use App\Models\RoomMember;
use App\Models\SyncLogEntry;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Tests\TestCase;

/**
 * Waktu push dan pull per cakupan (pribadi `a`, ruang bersama `r:<roomId>`) di `GET /api/sync/history`,
 * bidang tambahan `scopes` (sync-desain.md bagian G). Hanya metadata.
 */
class SyncScopeHistoryTest extends TestCase
{
    use RefreshDatabase;

    private function syncUser(): User
    {
        return User::factory()->create(['plans' => ['sync']]);
    }

    private function authHeader(User $user): array
    {
        // Lepaskan guard Sanctum yang tersimpan dari permintaan sebelumnya dalam satu tes.
        $this->app['auth']->forgetGuards();

        return ['Authorization' => 'Bearer '.$user->createToken('test')->plainTextToken];
    }

    private function entry(string $recordId, string $device, ?string $roomId = null, int $ms = 1000): array
    {
        return [
            'room_id' => $roomId,
            'record_type' => 'r',
            'record_id' => $recordId,
            'device_id' => $device,
            'payload' => 'cipher',
            'deleted' => false,
            'changed_at' => '2026-10-19T10:00:00Z',
            'changed_at_ms' => $ms,
        ];
    }

    private function push(User $user, array $entries)
    {
        return $this->withHeaders($this->authHeader($user))->postJson('/api/sync/push', ['entries' => $entries]);
    }

    private function pull(User $user, string $device)
    {
        return $this->withHeaders($this->authHeader($user))->getJson("/api/sync/pull?device_id={$device}&limit=500");
    }

    private function history(User $user)
    {
        return $this->withHeaders($this->authHeader($user))->getJson('/api/sync/history');
    }

    /** Cakupan di tingkat akun, diindeks menurut `scope`. */
    private function scopes($response): array
    {
        return collect($response->json('scopes'))->keyBy('scope')->all();
    }

    public function test_push_mencatat_waktu_push_per_cakupan(): void
    {
        $this->travelTo('2030-01-01 10:00:00');
        $user = $this->syncUser();
        $this->push($user, [$this->entry('p-1', 'pixel-1')])->assertOk();

        $this->travelTo('2030-01-01 11:00:00');
        $this->push($user, [$this->entry('r-1', 'pixel-1', 'ruang-a')])->assertOk();

        $scopes = $this->scopes($this->history($user)->assertOk());
        $this->assertSame('2030-01-01T10:00:00Z', $scopes['a']['last_push_at']);
        $this->assertSame('2030-01-01T11:00:00Z', $scopes['r:ruang-a']['last_push_at']);
        $this->assertNull($scopes['a']['last_pull_at']);
        $this->assertNull($scopes['a']['room_id']);
        $this->assertSame('ruang-a', $scopes['r:ruang-a']['room_id']);
    }

    public function test_pull_tanpa_scope_mencatat_waktu_pull_untuk_pribadi_dan_semua_ruang_yang_diikuti(): void
    {
        $user = $this->syncUser();
        $this->push($user, [$this->entry('r-1', 'pixel-1', 'ruang-a')])->assertOk();
        RoomMember::tambahAnggota($user->id, 'ruang-b');

        $this->travelTo('2026-11-02 08:30:00');
        $this->pull($user, 'pixel-1')->assertOk();

        $scopes = $this->scopes($this->history($user));
        foreach (['a', 'r:ruang-a', 'r:ruang-b'] as $scope) {
            $this->assertSame('2026-11-02T08:30:00Z', $scopes[$scope]['last_pull_at'], $scope);
        }
    }

    public function test_push_tidak_menghapus_waktu_pull_dan_sebaliknya(): void
    {
        $user = $this->syncUser();
        $this->travelTo('2026-11-02 08:00:00');
        $this->pull($user, 'pixel-1')->assertOk();
        $this->travelTo('2026-11-02 09:00:00');
        $this->push($user, [$this->entry('p-1', 'pixel-1')])->assertOk();
        $this->travelTo('2026-11-02 10:00:00');
        $this->pull($user, 'pixel-1')->assertOk();

        $a = $this->scopes($this->history($user))['a'];
        $this->assertSame('2026-11-02T09:00:00Z', $a['last_push_at']);
        $this->assertSame('2026-11-02T10:00:00Z', $a['last_pull_at']);
    }

    public function test_cakupan_pribadi_dan_semua_ruang_diikuti_selalu_tampil_walau_belum_ada_aktivitas(): void
    {
        $user = $this->syncUser();
        RoomMember::tambahAnggota($user->id, 'ruang-a');

        $scopes = $this->scopes($this->history($user)->assertOk());

        $this->assertSame(['a', 'r:ruang-a'], array_keys($scopes));
        $this->assertNull($scopes['a']['last_push_at']);
        $this->assertNull($scopes['r:ruang-a']['last_pull_at']);
    }

    public function test_waktu_push_dilengkapi_dari_log_untuk_data_sebelum_pencatatan_ada(): void
    {
        $user = $this->syncUser();
        SyncLogEntry::create([
            'user_id' => $user->id,
            'room_id' => null,
            'record_type' => 'r',
            'record_id' => 'lama-1',
            'device_id' => 'pixel-lama',
            'payload' => 'cipher',
            'deleted' => false,
            'changed_at' => now(),
            'changed_at_ms' => 1000,
        ]);

        $a = $this->scopes($this->history($user))['a'];

        $this->assertNotNull($a['last_push_at']);
    }

    public function test_ruang_yang_sudah_ditinggalkan_tidak_ditampilkan(): void
    {
        $pemilik = $this->syncUser();
        $anggota = $this->syncUser();
        $this->push($pemilik, [$this->entry('r-1', 'pixel-1', 'ruang-a')])->assertOk();
        RoomMember::tambahAnggota($anggota->id, 'ruang-a'); // push tidak lagi mendaftarkan non-anggota (key_grant langkah 1)
        $this->push($anggota, [$this->entry('r-2', 'pixel-2', 'ruang-a')])->assertOk();
        $this->assertArrayHasKey('r:ruang-a', $this->scopes($this->history($anggota)));

        $this->withHeaders($this->authHeader($anggota))->postJson('/api/rooms/ruang-a/leave')->assertNoContent();

        $scopes = $this->scopes($this->history($anggota));
        $this->assertArrayNotHasKey('r:ruang-a', $scopes);
        $this->assertArrayHasKey('a', $scopes);
        $this->assertSame([], collect($this->history($anggota)->json('devices.0.scopes'))->where('scope', 'r:ruang-a')->all());
    }

    public function test_per_perangkat_memuat_cakupan_milik_perangkat_itu_saja(): void
    {
        $user = $this->syncUser();
        $this->push($user, [$this->entry('p-1', 'pixel-1')])->assertOk();
        $this->push($user, [$this->entry('r-1', 'tablet-1', 'ruang-a')])->assertOk();

        $devices = collect($this->history($user)->assertOk()->json('devices'))->keyBy('device_id');

        $this->assertSame(['a'], array_column($devices['pixel-1']['scopes'], 'scope'));
        $this->assertSame(['r:ruang-a'], array_column($devices['tablet-1']['scopes'], 'scope'));
    }

    public function test_pull_per_cakupan_mengisi_waktu_pull_kursor_dan_menandai_perangkat_aktif(): void
    {
        $user = $this->syncUser();
        $this->push($user, [$this->entry('r-1', 'pixel-1', 'ruang-a')])->assertOk();
        $this->travelTo('2026-11-03 07:00:00');
        $this->withHeaders($this->authHeader($user))->getJson('/api/sync/pull?scope=r&room_id=ruang-a&device_id=tablet-1')->assertOk();

        $response = $this->history($user)->assertOk();
        $devices = collect($response->json('devices'))->keyBy('device_id');

        // Perangkat yang hanya memakai pull per cakupan tetap muncul, aktif, tanpa kursor global.
        $this->assertTrue($devices['tablet-1']['active']);
        $this->assertNull($devices['tablet-1']['cursor']);
        $this->assertSame('2026-11-03T07:00:00Z', $devices['tablet-1']['last_pull_at']);
        $scopeTablet = collect($devices['tablet-1']['scopes'])->firstWhere('scope', 'r:ruang-a');
        $this->assertSame(1, $scopeTablet['cursor']);
        $this->assertSame('2026-11-03T07:00:00Z', $scopeTablet['last_pull_at']);
        // Tingkat akun tidak membawa kursor (banyak perangkat) dan mengambil yang terbaru.
        $this->assertArrayNotHasKey('cursor', $this->scopes($response)['r:ruang-a']);
        $this->assertSame('2026-11-03T07:00:00Z', $this->scopes($response)['r:ruang-a']['last_pull_at']);
    }

    public function test_aktivitas_akun_lain_tidak_bocor(): void
    {
        $saya = $this->syncUser();
        $lain = $this->syncUser();
        $this->push($lain, [$this->entry('r-1', 'pixel-x', 'ruang-rahasia')])->assertOk();
        $this->pull($lain, 'pixel-x')->assertOk();

        $response = $this->history($saya)->assertOk();

        $this->assertSame(['a'], array_keys($this->scopes($response)));
        $this->assertSame([], $response->json('devices'));
        $this->assertStringNotContainsString('ruang-rahasia', $response->getContent());
        $this->assertStringNotContainsString('pixel-x', $response->getContent());
    }

    public function test_push_yang_gagal_kuota_tidak_mencatat_apa_pun(): void
    {
        config(['sync.quota.max_rows' => 1]);
        $user = $this->syncUser();

        $this->push($user, [$this->entry('p-1', 'pixel-1'), $this->entry('r-1', 'pixel-1', 'ruang-a')])->assertStatus(422);

        $this->assertDatabaseCount('sync_scope_activity', 0);
    }

    public function test_baris_yang_ditolak_penjaga_penulis_pertama_tidak_mencatat_push_ruang_itu(): void
    {
        $pemilik = $this->syncUser();
        $penyusup = $this->syncUser();
        $this->push($pemilik, [$this->entry('r-1', 'pixel-1', 'ruang-a', 1000)])->assertOk();

        // Anggota lain mencoba menimpa rekaman milik penulis pertama: baris ditolak diam-diam.
        RoomMember::tambahAnggota($penyusup->id, 'ruang-a');
        $this->push($penyusup, [$this->entry('r-1', 'pixel-2', 'ruang-a', 2000)])->assertOk();

        $this->assertDatabaseMissing('sync_scope_activity', ['user_id' => $penyusup->id, 'room_id' => 'ruang-a']);
    }

    public function test_isi_respons_hanya_metadata(): void
    {
        $user = $this->syncUser();
        $this->push($user, [$this->entry('rahasia-id', 'pixel-1', 'ruang-a')])->assertOk();

        $scope = $this->scopes($this->history($user))['r:ruang-a'];

        $this->assertEqualsCanonicalizing(['scope', 'room_id', 'last_push_at', 'last_pull_at'], array_keys($scope));
    }
}
