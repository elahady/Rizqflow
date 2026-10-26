<?php

namespace Tests\Feature\Sync;

use App\Models\RoomMember;
use App\Models\SyncCompactionState;
use App\Models\SyncLogEntry;
use App\Models\SyncQuota;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\DB;
use Tests\TestCase;

/** Kompaksi fisik `sync:compact` (Tahap 7, sync-desain.md bagian J #6): versi kalah dan tombstone. */
class SyncCompactCommandTest extends TestCase
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

    /** Push satu baris dan kembalikan id-nya. */
    private function push(User $user, string $recordId, string $device, int $ms, bool $deleted = false, ?string $roomId = null): int
    {
        $response = $this->withHeaders($this->authHeader($user))->postJson('/api/sync/push', [
            'entries' => [[
                'room_id' => $roomId,
                'record_type' => 'transaction',
                'record_id' => $recordId,
                'device_id' => $device,
                'payload' => 'cipher-'.$ms,
                'deleted' => $deleted,
                'changed_at' => '2026-10-19T10:00:00Z',
                'changed_at_ms' => $ms,
            ]],
        ]);
        $response->assertOk();

        return (int) $response->json('ids.0');
    }

    /** Mundurkan created_at (umur baris, jam server) sejumlah hari. */
    private function umur(int $id, int $hari): void
    {
        DB::table('sync_log_entries')->where('id', $id)->update([
            'created_at' => now()->utc()->subDays($hari)->format('Y-m-d H:i:s'),
        ]);
    }

    /** Tarik perubahan sebagai perangkat tertentu; dicatat di sync_devices (after_id = id terakhir yang diterima). */
    private function pull(User $user, string $device, int $after = 0)
    {
        return $this->withHeaders($this->authHeader($user))
            ->getJson("/api/sync/pull?after={$after}&limit=500&device_id={$device}");
    }

    public function test_versi_kalah_berumur_90_hari_dihapus_dan_pemenang_tetap(): void
    {
        $user = $this->syncUser();
        $kalah = $this->push($user, 'tx-1', 'pixel-1', 1000);
        $menang = $this->push($user, 'tx-1', 'pixel-1', 2000);
        $this->umur($kalah, 100);
        $this->umur($menang, 100);

        $this->artisan('sync:compact')->assertSuccessful();

        $this->assertDatabaseMissing('sync_log_entries', ['id' => $kalah]);
        $this->assertDatabaseHas('sync_log_entries', ['id' => $menang]);
    }

    public function test_versi_kalah_yang_masih_muda_tidak_dihapus(): void
    {
        $user = $this->syncUser();
        $kalah = $this->push($user, 'tx-1', 'pixel-1', 1000);
        $menang = $this->push($user, 'tx-1', 'pixel-1', 2000);
        $this->umur($kalah, 89);

        $this->artisan('sync:compact')->assertSuccessful();

        $this->assertDatabaseHas('sync_log_entries', ['id' => $kalah]);
        $this->assertDatabaseHas('sync_log_entries', ['id' => $menang]);
    }

    public function test_tombstone_berumur_400_hari_dihapus_bila_semua_perangkat_aktif_sudah_melewatinya(): void
    {
        $user = $this->syncUser();
        $live = $this->push($user, 'tx-1', 'pixel-1', 1000);
        $tombstone = $this->push($user, 'tx-1', 'pixel-1', 2000, deleted: true);
        $this->umur($live, 500);
        $this->umur($tombstone, 500);
        $this->pull($user, 'pixel-1')->assertOk(); // kursor perangkat sampai ke id terakhir

        $this->artisan('sync:compact')->assertSuccessful();

        $this->assertDatabaseMissing('sync_log_entries', ['id' => $tombstone]);
        $this->assertDatabaseMissing('sync_log_entries', ['id' => $live]); // versi kalah ikut hilang
        $this->assertSame($tombstone, SyncCompactionState::tombstoneWatermark());
    }

    public function test_tombstone_tidak_dihapus_bila_perangkat_aktif_belum_melewatinya(): void
    {
        $user = $this->syncUser();
        $this->pull($user, 'pixel-2')->assertOk(); // perangkat kedua aktif, kursornya 0
        $tombstone = $this->push($user, 'tx-1', 'pixel-1', 2000, deleted: true);
        $this->umur($tombstone, 500);
        $this->pull($user, 'pixel-1')->assertOk();

        $this->artisan('sync:compact')->assertSuccessful();

        $this->assertDatabaseHas('sync_log_entries', ['id' => $tombstone]);
        $this->assertSame(0, SyncCompactionState::tombstoneWatermark());
    }

    public function test_perangkat_yang_sudah_tidak_aktif_tidak_menahan_tombstone(): void
    {
        $user = $this->syncUser();
        $this->pull($user, 'pixel-lama')->assertOk();
        DB::table('sync_devices')->where('device_id', 'pixel-lama')->update([
            'last_seen_at' => now()->utc()->subDays(200)->format('Y-m-d H:i:s'),
        ]);
        $tombstone = $this->push($user, 'tx-1', 'pixel-1', 2000, deleted: true);
        $this->umur($tombstone, 500);
        $this->pull($user, 'pixel-1')->assertOk();

        $this->artisan('sync:compact')->assertSuccessful();

        $this->assertDatabaseMissing('sync_log_entries', ['id' => $tombstone]);
    }

    public function test_tombstone_yang_belum_400_hari_tidak_dihapus(): void
    {
        $user = $this->syncUser();
        $tombstone = $this->push($user, 'tx-1', 'pixel-1', 2000, deleted: true);
        $this->umur($tombstone, 399);
        $this->pull($user, 'pixel-1')->assertOk();

        $this->artisan('sync:compact')->assertSuccessful();

        $this->assertDatabaseHas('sync_log_entries', ['id' => $tombstone]);
    }

    public function test_tombstone_tidak_dihapus_selama_masih_ada_versi_lain_untuk_rekaman_itu(): void
    {
        $user = $this->syncUser();
        $live = $this->push($user, 'tx-1', 'pixel-1', 1000); // versi kalah tapi masih muda
        $tombstone = $this->push($user, 'tx-1', 'pixel-1', 2000, deleted: true);
        $this->umur($tombstone, 500);
        $this->pull($user, 'pixel-1')->assertOk();

        $this->artisan('sync:compact')->assertSuccessful();

        $this->assertDatabaseHas('sync_log_entries', ['id' => $live]);
        $this->assertDatabaseHas('sync_log_entries', ['id' => $tombstone]);
    }

    public function test_tombstone_ruang_bersama_menunggu_perangkat_semua_anggota(): void
    {
        $pemilik = $this->syncUser();
        $anggota = $this->syncUser();
        RoomMember::tambahAnggota($anggota->id, 'ruang-1');
        $this->pull($anggota, 'pixel-anggota')->assertOk(); // anggota aktif, belum melewati apa pun

        $tombstone = $this->push($pemilik, 'tx-1', 'pixel-pemilik', 2000, deleted: true, roomId: 'ruang-1');
        $this->umur($tombstone, 500);
        $this->pull($pemilik, 'pixel-pemilik')->assertOk();

        $this->artisan('sync:compact')->assertSuccessful();
        $this->assertDatabaseHas('sync_log_entries', ['id' => $tombstone]);

        // Anggota menarik sampai melewati tombstone, lalu kompaksi boleh menghapusnya.
        $this->pull($anggota, 'pixel-anggota')->assertOk();
        $this->artisan('sync:compact')->assertSuccessful();
        $this->assertDatabaseMissing('sync_log_entries', ['id' => $tombstone]);
    }

    public function test_kompaksi_idempoten_saat_dijalankan_berulang(): void
    {
        $user = $this->syncUser();
        $kalah = $this->push($user, 'tx-1', 'pixel-1', 1000);
        $this->push($user, 'tx-1', 'pixel-1', 2000);
        $this->umur($kalah, 100);

        $this->artisan('sync:compact')->expectsOutputToContain('Versi kalah dihapus: 1')->assertSuccessful();
        $jumlahSetelahPertama = SyncLogEntry::count();

        $this->artisan('sync:compact')->expectsOutputToContain('Versi kalah dihapus: 0')->assertSuccessful();

        $this->assertSame($jumlahSetelahPertama, SyncLogEntry::count());
    }

    public function test_kompaksi_mengurangi_penghitung_kuota_akun(): void
    {
        $user = $this->syncUser();
        $kalah = $this->push($user, 'tx-1', 'pixel-1', 1000);
        $menang = $this->push($user, 'tx-1', 'pixel-1', 2000);
        $this->assertSame(2, SyncQuota::find($user->id)->row_count);
        $this->umur($kalah, 100);

        $this->artisan('sync:compact')->assertSuccessful();

        $kuota = SyncQuota::find($user->id);
        $this->assertSame(1, $kuota->row_count);
        $this->assertSame(strlen('cipher-2000'), $kuota->payload_bytes);
        $this->assertDatabaseHas('sync_log_entries', ['id' => $menang]);
    }
}
