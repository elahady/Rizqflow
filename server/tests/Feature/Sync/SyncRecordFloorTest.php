<?php

namespace Tests\Feature\Sync;

use App\Models\RoomMember;
use App\Models\SyncLogEntry;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Log;
use Tests\TestCase;

/**
 * Lantai per rekaman (Tahap 9): setelah sync:compact menghapus fisik tombstone, push versi lama yang
 * terlambat untuk rekaman itu tidak boleh menghidupkannya kembali.
 */
class SyncRecordFloorTest extends TestCase
{
    use RefreshDatabase;

    private function syncUser(): User
    {
        return User::factory()->create(['plans' => ['sync']]);
    }

    private function authHeader(User $user): array
    {
        $this->app['auth']->forgetGuards();

        return ['Authorization' => 'Bearer '.$user->createToken('test')->plainTextToken];
    }

    private function pushRaw(User $user, string $recordId, string $device, int $ms, bool $deleted = false, ?string $roomId = null, string $payload = 'cipher')
    {
        return $this->withHeaders($this->authHeader($user))->postJson('/api/sync/push', [
            'entries' => [[
                'room_id' => $roomId,
                'record_type' => 'r',
                'record_id' => $recordId,
                'device_id' => $device,
                'payload' => $payload.'-'.$ms,
                'deleted' => $deleted,
                'changed_at' => '2026-10-19T10:00:00Z',
                'changed_at_ms' => $ms,
            ]],
        ]);
    }

    private function push(User $user, string $recordId, string $device, int $ms, bool $deleted = false, ?string $roomId = null): int
    {
        $response = $this->pushRaw($user, $recordId, $device, $ms, $deleted, $roomId);
        $response->assertOk();

        return (int) $response->json('ids.0');
    }

    private function umur(int $id, int $hari): void
    {
        DB::table('sync_log_entries')->where('id', $id)->update([
            'created_at' => now()->utc()->subDays($hari)->format('Y-m-d H:i:s'),
        ]);
    }

    private function pull(User $user, string $device, int $after = 0)
    {
        return $this->withHeaders($this->authHeader($user))
            ->getJson("/api/sync/pull?after={$after}&limit=500&device_id={$device}");
    }

    /** Rekaman tx-1 dibuat (ms 1000), dihapus (ms 2000), lalu tombstone-nya dikompaksi. */
    private function rekamanDihapusLaluDikompaksi(User $user, ?string $roomId = null): void
    {
        $live = $this->push($user, 'tx-1', 'pixel-1', 1000, roomId: $roomId);
        $tombstone = $this->push($user, 'tx-1', 'pixel-1', 2000, deleted: true, roomId: $roomId);
        $this->umur($live, 500);
        $this->umur($tombstone, 500);
        $this->pull($user, 'pixel-1')->assertOk();

        $this->artisan('sync:compact')->assertSuccessful();

        $this->assertSame(0, SyncLogEntry::where('record_id', 'tx-1')->count(), 'prasyarat: semua baris tx-1 sudah dikompaksi');
    }

    public function test_kompaksi_tombstone_mencatat_lantai_rekaman(): void
    {
        $user = $this->syncUser();

        $this->rekamanDihapusLaluDikompaksi($user);

        $this->assertDatabaseHas('sync_record_floors', [
            'user_id' => $user->id,
            'scope' => '',
            'record_id' => 'tx-1',
            'floor_ms' => 2000,
        ]);
    }

    public function test_push_versi_lama_yang_terlambat_setelah_kompaksi_ditolak_dan_tidak_bangkit(): void
    {
        $user = $this->syncUser();
        $this->rekamanDihapusLaluDikompaksi($user);

        // Perangkat lain yang lama offline akhirnya mendorong versi hidup lama (ms 1500 < tombstone 2000).
        $response = $this->pushRaw($user, 'tx-1', 'pixel-lama', 1500);

        $response->assertOk();
        $this->assertSame([], $response->json('ids'));
        $this->assertSame(0, SyncLogEntry::where('record_id', 'tx-1')->count());

        // Perangkat baru yang pull dari 0 tidak menerima rekaman yang sudah dihapus itu.
        $entries = $this->pull($user, 'pixel-baru')->assertOk()->json('entries');
        $this->assertSame([], array_values(array_filter($entries, fn ($e) => $e['record_id'] === 'tx-1')));
    }

    public function test_cap_waktu_sama_dengan_lantai_juga_ditolak(): void
    {
        $user = $this->syncUser();
        $this->rekamanDihapusLaluDikompaksi($user);

        // Versi hidup dengan ms sama kalah dari tombstone (tombstone menang pada seri); tombstone yang
        // diulang juga tidak perlu disimpan lagi.
        $this->pushRaw($user, 'tx-1', 'pixel-1', 2000)->assertOk()->assertJsonPath('ids', []);
        $this->pushRaw($user, 'tx-1', 'pixel-1', 2000, deleted: true)->assertOk()->assertJsonPath('ids', []);

        $this->assertSame(0, SyncLogEntry::where('record_id', 'tx-1')->count());
    }

    public function test_edit_yang_lebih_baru_dari_lantai_tetap_diterima(): void
    {
        $user = $this->syncUser();
        $this->rekamanDihapusLaluDikompaksi($user);

        $id = $this->push($user, 'tx-1', 'pixel-1', 2001);

        $this->assertDatabaseHas('sync_log_entries', ['id' => $id, 'record_id' => 'tx-1', 'changed_at_ms' => 2001]);
    }

    public function test_lantai_pribadi_tidak_berlaku_untuk_akun_lain(): void
    {
        $user = $this->syncUser();
        $lain = $this->syncUser();
        $this->rekamanDihapusLaluDikompaksi($user);

        // record_id sama kebetulan, tetapi milik akun lain: tidak boleh terpengaruh.
        $id = $this->push($lain, 'tx-1', 'pixel-lain', 1500);

        $this->assertDatabaseHas('sync_log_entries', ['id' => $id, 'user_id' => $lain->id]);
    }

    public function test_lantai_pribadi_tidak_berlaku_untuk_rekaman_ruang_dengan_id_sama(): void
    {
        $user = $this->syncUser();
        $this->rekamanDihapusLaluDikompaksi($user);

        $id = $this->push($user, 'tx-1', 'pixel-1', 1500, roomId: 'ruang-1');

        $this->assertDatabaseHas('sync_log_entries', ['id' => $id, 'room_id' => 'ruang-1']);
    }

    public function test_lantai_ruang_bersama_berlaku_untuk_semua_anggota(): void
    {
        $pemilik = $this->syncUser();
        $anggota = $this->syncUser();
        RoomMember::tambahAnggota($pemilik->id, 'ruang-1');
        RoomMember::tambahAnggota($anggota->id, 'ruang-1');
        $this->pull($anggota, 'pixel-anggota')->assertOk();

        $live = $this->push($pemilik, 'tx-1', 'pixel-1', 1000, roomId: 'ruang-1');
        $tombstone = $this->push($pemilik, 'tx-1', 'pixel-1', 2000, deleted: true, roomId: 'ruang-1');
        $this->umur($live, 500);
        $this->umur($tombstone, 500);
        $this->pull($pemilik, 'pixel-1')->assertOk();
        $this->pull($anggota, 'pixel-anggota')->assertOk();
        $this->artisan('sync:compact')->assertSuccessful();
        $this->assertSame(0, SyncLogEntry::where('record_id', 'tx-1')->count());

        // Setelah tombstone hilang, penjaga penulis pertama tidak lagi menemukan baris; lantai ruang
        // tetap menolak versi lama dari anggota mana pun.
        $this->pushRaw($anggota, 'tx-1', 'pixel-anggota', 1500, roomId: 'ruang-1')->assertOk()->assertJsonPath('ids', []);
        $this->pushRaw($pemilik, 'tx-1', 'pixel-1', 1000, roomId: 'ruang-1')->assertOk()->assertJsonPath('ids', []);

        $this->assertSame(0, SyncLogEntry::where('record_id', 'tx-1')->count());
    }

    public function test_versi_lama_dalam_batch_ditolak_sendirian_baris_lain_tetap_tersimpan(): void
    {
        $user = $this->syncUser();
        $this->rekamanDihapusLaluDikompaksi($user);

        $response = $this->withHeaders($this->authHeader($user))->postJson('/api/sync/push', [
            'entries' => [
                ['record_type' => 'r', 'record_id' => 'tx-1', 'device_id' => 'pixel-lama', 'payload' => 'lama', 'changed_at' => '2026-10-19T10:00:00Z', 'changed_at_ms' => 1500],
                ['record_type' => 'r', 'record_id' => 'tx-2', 'device_id' => 'pixel-lama', 'payload' => 'baru', 'changed_at' => '2026-10-19T10:00:00Z', 'changed_at_ms' => 1500],
            ],
        ]);

        $response->assertOk();
        $this->assertCount(1, $response->json('ids'));
        $this->assertDatabaseHas('sync_log_entries', ['record_id' => 'tx-2']);
        $this->assertDatabaseMissing('sync_log_entries', ['record_id' => 'tx-1']);
    }

    public function test_penolakan_lantai_tidak_menulis_payload_ke_log(): void
    {
        $user = $this->syncUser();
        $this->rekamanDihapusLaluDikompaksi($user);

        $konteks = [];
        Log::listen(function ($pesan) use (&$konteks) {
            $konteks[] = $pesan->message.' '.json_encode($pesan->context);
        });

        $this->pushRaw($user, 'tx-1', 'pixel-lama', 1500, payload: 'RAHASIA-PAYLOAD')->assertOk();

        $this->assertNotEmpty($konteks);
        foreach ($konteks as $baris) {
            $this->assertStringNotContainsString('RAHASIA-PAYLOAD', $baris);
        }
    }

    public function test_lantai_ikut_terhapus_saat_akun_dihapus(): void
    {
        $user = $this->syncUser();
        $this->rekamanDihapusLaluDikompaksi($user);

        $user->delete();

        $this->assertDatabaseCount('sync_record_floors', 0);
    }
}
