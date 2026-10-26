<?php

namespace Tests\Feature\Api;

use App\Models\SyncCompactionState;
use App\Models\SyncLogEntry;
use App\Models\SyncQuota;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Tests\TestCase;

/** Kuota per akun (422 quota_exceeded), kursor basi (410 cursor_expired), dan Riwayat sinkron. */
class SyncRetentionApiTest extends TestCase
{
    use RefreshDatabase;

    private function syncUser(): User
    {
        return User::factory()->create(['plans' => ['sync']]);
    }

    private function authHeader(User $user): array
    {
        // Guard Sanctum menyimpan pengguna dari permintaan sebelumnya dalam satu tes; lepaskan supaya
        // permintaan berikutnya benar-benar memakai token pengguna yang diminta.
        $this->app['auth']->forgetGuards();

        return ['Authorization' => 'Bearer '.$user->createToken('test')->plainTextToken];
    }

    private function entry(string $recordId, string $device, int $ms, string $payload = 'cipher', bool $deleted = false): array
    {
        return [
            'record_type' => 'transaction',
            'record_id' => $recordId,
            'device_id' => $device,
            'payload' => $payload,
            'deleted' => $deleted,
            'changed_at' => '2026-10-19T10:00:00Z',
            'changed_at_ms' => $ms,
        ];
    }

    private function push(User $user, array $entries)
    {
        return $this->withHeaders($this->authHeader($user))->postJson('/api/sync/push', ['entries' => $entries]);
    }

    public function test_push_ditolak_422_quota_exceeded_saat_jumlah_baris_akan_melewati_batas(): void
    {
        config(['sync.quota.max_rows' => 2]);
        $user = $this->syncUser();
        $this->push($user, [$this->entry('tx-1', 'pixel-1', 1000)])->assertOk();
        $this->push($user, [$this->entry('tx-2', 'pixel-1', 1000)])->assertOk();

        $response = $this->push($user, [$this->entry('tx-3', 'pixel-1', 1000)]);

        $response->assertStatus(422);
        $response->assertJsonPath('code', 'quota_exceeded');
        $this->assertStringContainsString('tetap aman', $response->json('message'));
        $this->assertSame(2, SyncLogEntry::where('user_id', $user->id)->count());
    }

    public function test_push_ditolak_422_quota_exceeded_saat_byte_payload_akan_melewati_batas(): void
    {
        config(['sync.quota.max_bytes' => 10]);
        $user = $this->syncUser();
        $this->push($user, [$this->entry('tx-1', 'pixel-1', 1000, payload: 'abcdefghij')])->assertOk();

        $response = $this->push($user, [$this->entry('tx-2', 'pixel-1', 1000, payload: 'k')]);

        $response->assertStatus(422);
        $response->assertJsonPath('code', 'quota_exceeded');
        $this->assertSame(1, SyncLogEntry::where('user_id', $user->id)->count());
    }

    public function test_batch_yang_melewati_kuota_ditolak_seluruhnya(): void
    {
        config(['sync.quota.max_rows' => 2]);
        $user = $this->syncUser();

        $response = $this->push($user, [
            $this->entry('tx-1', 'pixel-1', 1000),
            $this->entry('tx-2', 'pixel-1', 1000),
            $this->entry('tx-3', 'pixel-1', 1000),
        ]);

        $response->assertStatus(422);
        $this->assertSame(0, SyncLogEntry::count());
    }

    public function test_duplikat_tetap_diterima_walau_kuota_sudah_penuh(): void
    {
        config(['sync.quota.max_rows' => 1]);
        $user = $this->syncUser();
        $entry = $this->entry('tx-1', 'pixel-1', 1000);
        $this->push($user, [$entry])->assertOk();

        // Ack hilang di klien: push yang sama diulang tidak menambah baris, jadi tidak perlu ruang.
        $response = $this->push($user, [$entry]);

        $response->assertOk();
        $this->assertCount(1, $response->json('ids'));
        $this->assertSame(1, SyncLogEntry::count());
    }

    public function test_kuota_akun_lama_dihitung_dari_log_saat_push_pertama(): void
    {
        config(['sync.quota.max_rows' => 3]);
        $user = $this->syncUser();
        foreach (['tx-1', 'tx-2'] as $recordId) {
            SyncLogEntry::create([
                'user_id' => $user->id,
                'room_id' => null,
                'record_type' => 'transaction',
                'record_id' => $recordId,
                'device_id' => 'pixel-1',
                'payload' => 'cipher',
                'deleted' => false,
                'changed_at' => now(),
                'changed_at_ms' => 1000,
            ]);
        }

        $this->push($user, [$this->entry('tx-3', 'pixel-1', 1000)])->assertOk();
        $response = $this->push($user, [$this->entry('tx-4', 'pixel-1', 1000)]);

        $response->assertStatus(422);
        $this->assertSame(3, SyncQuota::find($user->id)->row_count);
    }

    public function test_akun_lain_tidak_terpengaruh_kuota_akun_ini(): void
    {
        config(['sync.quota.max_rows' => 1]);
        $penuh = $this->syncUser();
        $lain = $this->syncUser();
        $this->push($penuh, [$this->entry('tx-1', 'pixel-1', 1000)])->assertOk();
        $this->push($penuh, [$this->entry('tx-2', 'pixel-1', 1000)])->assertStatus(422);

        $this->push($lain, [$this->entry('tx-1', 'pixel-2', 1000)])->assertOk();
    }

    public function test_pull_dengan_kursor_di_bawah_watermark_dijawab_410_cursor_expired(): void
    {
        $user = $this->syncUser();
        SyncCompactionState::catatJalan(0, 0, 10);

        $response = $this->withHeaders($this->authHeader($user))->getJson('/api/sync/pull?after=5');

        $response->assertStatus(410);
        $response->assertJsonPath('code', 'cursor_expired');
    }

    public function test_pull_dari_0_dan_kursor_di_atas_watermark_tetap_dilayani(): void
    {
        $user = $this->syncUser();
        SyncCompactionState::catatJalan(0, 0, 10);

        $this->withHeaders($this->authHeader($user))->getJson('/api/sync/pull?after=0')->assertOk();
        $this->withHeaders($this->authHeader($user))->getJson('/api/sync/pull?after=10')->assertOk();
    }

    public function test_riwayat_sinkron_hanya_berisi_metadata_dan_hanya_milik_akun_sendiri(): void
    {
        $user = $this->syncUser();
        $lain = $this->syncUser();
        $rahasia = 'cipher-rahasia-jangan-bocor';
        $this->push($user, [$this->entry('tx-1', 'pixel-1', 1000, payload: $rahasia)])->assertOk();
        $this->push($lain, [$this->entry('tx-9', 'pixel-lain', 1000)])->assertOk();
        $this->withHeaders($this->authHeader($user))->getJson('/api/sync/pull?after=0&device_id=pixel-1')->assertOk();

        $response = $this->withHeaders($this->authHeader($user))->getJson('/api/sync/history');

        $response->assertOk();
        $response->assertJsonCount(1, 'devices');
        $response->assertJsonPath('devices.0.device_id', 'pixel-1');
        $response->assertJsonPath('devices.0.rows', 1);
        $response->assertJsonPath('devices.0.active', true);
        $this->assertNotNull($response->json('devices.0.last_push_at'));
        $this->assertNotNull($response->json('devices.0.last_pull_at'));
        $response->assertJsonPath('quota.rows', 1);
        $response->assertJsonPath('quota.max_rows', 250000);
        $response->assertJsonPath('quota.max_bytes', 150 * 1024 * 1024);
        $response->assertJsonPath('compaction.last_run_at', null);
        $this->assertStringNotContainsString($rahasia, $response->getContent());
    }

    public function test_riwayat_sinkron_mencatat_kompaksi_terakhir(): void
    {
        $user = $this->syncUser();
        SyncCompactionState::catatJalan(4, 2, null);

        $response = $this->withHeaders($this->authHeader($user))->getJson('/api/sync/history');

        $response->assertOk();
        $response->assertJsonPath('compaction.last_removed_versions', 4);
        $response->assertJsonPath('compaction.last_removed_tombstones', 2);
        $this->assertNotNull($response->json('compaction.last_run_at'));
    }

    public function test_riwayat_sinkron_tidak_butuh_langganan_sync_tetapi_butuh_login(): void
    {
        $tanpaSync = User::factory()->create(['plans' => []]);

        $this->withHeaders($this->authHeader($tanpaSync))->getJson('/api/sync/history')->assertOk();
        $this->flushHeaders(); // withHeaders bertahan antar permintaan dalam satu tes
        $this->app['auth']->forgetGuards();
        $this->getJson('/api/sync/history')->assertUnauthorized();
    }
}
