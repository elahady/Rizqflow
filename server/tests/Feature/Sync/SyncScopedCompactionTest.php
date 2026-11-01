<?php

namespace Tests\Feature\Sync;

use App\Models\RoomMember;
use App\Models\SyncCompactionState;
use App\Models\SyncDevice;
use App\Models\SyncDeviceCursor;
use App\Models\SyncScopeWatermark;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\DB;
use Illuminate\Testing\TestResponse;
use Tests\TestCase;

/**
 * Kompaksi (`sync:compact`) dan 410 `cursor_expired` bersama kursor PER CAKUPAN
 * (`sync_device_cursors`, sync-desain.md bagian G): kompaksi tidak boleh menghapus tombstone yang belum
 * dilewati perangkat yang memakai kursor per cakupan, tidak boleh tertahan oleh kursor yang tidak
 * relevan, dan kursor global lama tetap berjalan.
 */
class SyncScopedCompactionTest extends TestCase
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

    private function push(User $user, string $recordId, string $device, int $ms, ?string $roomId = null, bool $deleted = false): int
    {
        $response = $this->withHeaders($this->authHeader($user))->postJson('/api/sync/push', [
            'entries' => [[
                'room_id' => $roomId,
                'record_type' => 'r',
                'record_id' => $recordId,
                'device_id' => $device,
                'payload' => "cipher-{$ms}",
                'deleted' => $deleted,
                'changed_at' => '2026-10-19T10:00:00Z',
                'changed_at_ms' => $ms,
            ]],
        ]);
        $response->assertOk();

        return (int) $response->json('ids.0');
    }

    /** Tombstone berumur 500 hari (siap dikompaksi bila semua perangkat aktif sudah melewatinya). */
    private function tombstoneTua(User $user, string $recordId, ?string $roomId = null): int
    {
        $id = $this->push($user, $recordId, 'hp', 2000, $roomId, true);
        DB::table('sync_log_entries')->where('id', $id)->update([
            'created_at' => now()->utc()->subDays(500)->format('Y-m-d H:i:s'),
        ]);

        return $id;
    }

    private function pullScoped(User $user, string $device, string $scope, ?string $roomId = null, int $after = 0, int $limit = 500): TestResponse
    {
        $query = "scope={$scope}&device_id={$device}&after={$after}&limit={$limit}".($roomId === null ? '' : '&room_id='.urlencode($roomId));

        return $this->withHeaders($this->authHeader($user))->getJson("/api/sync/pull?{$query}");
    }

    private function pullLegacy(User $user, string $device, int $after = 0): TestResponse
    {
        return $this->withHeaders($this->authHeader($user))
            ->getJson("/api/sync/pull?device_id={$device}&after={$after}&limit=500");
    }

    private function adaBaris(int $id): bool
    {
        return DB::table('sync_log_entries')->where('id', $id)->exists();
    }

    /** Buat kursor per cakupan secara langsung, dengan umur terakhir terlihat tertentu. */
    private function kursor(User $user, string $device, ?string $roomId, int $afterId, int $hariLalu = 0): void
    {
        SyncDeviceCursor::create([
            'user_id' => $user->id,
            'device_id' => $device,
            'room_id' => $roomId ?? '',
            'after_id' => $afterId,
            'last_seen_at' => now()->utc()->subDays($hariLalu),
        ]);
    }

    public function test_tombstone_pribadi_menunggu_kursor_cakupan_pribadi_perangkat_aktif(): void
    {
        $user = $this->syncUser();
        $tombstone = $this->tombstoneTua($user, 'tx-1');
        $this->pullScoped($user, 'hp', 'a')->assertOk(); // hp sudah melewati
        $this->kursor($user, 'tablet', null, 0);          // tablet aktif tapi belum melewati

        $this->artisan('sync:compact')->assertSuccessful();
        $this->assertTrue($this->adaBaris($tombstone), 'tombstone harus ditahan kursor cakupan tablet');

        $this->pullScoped($user, 'tablet', 'a')->assertOk(); // tablet menyusul
        $this->artisan('sync:compact')->assertSuccessful();

        $this->assertFalse($this->adaBaris($tombstone));
        $this->assertSame($tombstone, SyncCompactionState::tombstoneWatermark());
        $this->assertSame($tombstone, SyncScopeWatermark::untuk($user->id, null));
    }

    public function test_perangkat_hasil_migrasi_dengan_kursor_global_usang_tidak_menahan_selama_kursor_cakupannya_lewat(): void
    {
        $user = $this->syncUser();
        $this->pullLegacy($user, 'hp')->assertOk();  // masa lalu: kursor global 0
        $tombstone = $this->tombstoneTua($user, 'tx-1');
        $this->pullScoped($user, 'hp', 'a')->assertOk(); // setelah migrasi: kursor cakupan melewati

        $this->assertSame(0, SyncDevice::where('device_id', 'hp')->value('after_id'));
        $this->artisan('sync:compact')->assertSuccessful();

        $this->assertFalse($this->adaBaris($tombstone));
    }

    public function test_kursor_global_yang_sudah_melewati_cukup_walau_kursor_cakupan_usang(): void
    {
        $user = $this->syncUser();
        $tombstone = $this->tombstoneTua($user, 'tx-1');
        $this->kursor($user, 'hp', null, 0); // kursor cakupan lama tertinggal (mis. sempat sinkron ulang)
        $this->pullLegacy($user, 'hp')->assertOk(); // lalu pull lama melewati tombstone

        $this->artisan('sync:compact')->assertSuccessful();

        $this->assertFalse($this->adaBaris($tombstone));
    }

    public function test_perangkat_pull_lama_yang_belum_melewati_tetap_menahan_walau_perangkat_lain_cakupan_sudah(): void
    {
        $user = $this->syncUser();
        $this->pullLegacy($user, 'tablet-lama')->assertOk(); // kursor global 0, tidak pernah per cakupan
        $tombstone = $this->tombstoneTua($user, 'tx-1');
        $this->pullScoped($user, 'hp', 'a')->assertOk();

        $this->artisan('sync:compact')->assertSuccessful();

        $this->assertTrue($this->adaBaris($tombstone));
    }

    public function test_kursor_cakupan_lain_tidak_menahan_tombstone_cakupan_ini(): void
    {
        $user = $this->syncUser();
        $this->push($user, 'r-awal', 'hp', 1000, 'ruang-a'); // user pemilik ruang-a
        $tombstonePribadi = $this->tombstoneTua($user, 'tx-pribadi');
        $tombstoneRuang = $this->tombstoneTua($user, 'tx-ruang', 'ruang-a');
        // Tablet hanya pernah menarik ruang-a dan masih di 0: tidak relevan bagi tombstone pribadi.
        $this->kursor($user, 'tablet', 'ruang-a', 0);
        $this->pullScoped($user, 'hp', 'a')->assertOk();
        $this->pullScoped($user, 'hp', 'r', 'ruang-a')->assertOk();

        $this->artisan('sync:compact')->assertSuccessful();

        $this->assertFalse($this->adaBaris($tombstonePribadi), 'kursor ruang tablet tidak boleh menahan data pribadi');
        $this->assertTrue($this->adaBaris($tombstoneRuang), 'kursor ruang tablet harus menahan tombstone ruang itu');
    }

    public function test_kursor_pribadi_tidak_menahan_tombstone_ruang(): void
    {
        $user = $this->syncUser();
        $this->push($user, 'r-awal', 'hp', 1000, 'ruang-a');
        $tombstoneRuang = $this->tombstoneTua($user, 'tx-ruang', 'ruang-a');
        $this->kursor($user, 'tablet', null, 0); // pribadi di 0 (belum menarik apa pun dari ruang)
        $this->pullScoped($user, 'hp', 'r', 'ruang-a')->assertOk();

        $this->artisan('sync:compact')->assertSuccessful();

        $this->assertFalse($this->adaBaris($tombstoneRuang));
    }

    public function test_tombstone_ruang_menunggu_kursor_ruang_semua_anggota_tetapi_bukan_bekas_anggota(): void
    {
        $pemilik = $this->syncUser();
        $anggota = $this->syncUser();
        $bekas = $this->syncUser();
        $this->push($pemilik, 'r-awal', 'hp-p', 1000, 'ruang-a');
        RoomMember::tambahAnggota($anggota->id, 'ruang-a');
        RoomMember::tambahAnggota($bekas->id, 'ruang-a');
        $tombstone = $this->tombstoneTua($pemilik, 'tx-ruang', 'ruang-a');
        $this->kursor($bekas, 'hp-bekas', 'ruang-a', 0); // masih tercatat, lalu keluar
        RoomMember::where('user_id', $bekas->id)->delete();
        $this->pullScoped($pemilik, 'hp-p', 'r', 'ruang-a')->assertOk();
        $this->kursor($anggota, 'hp-a', 'ruang-a', 0);

        $this->artisan('sync:compact')->assertSuccessful();
        $this->assertTrue($this->adaBaris($tombstone), 'kursor anggota aktif yang tertinggal harus menahan');

        $this->pullScoped($anggota, 'hp-a', 'r', 'ruang-a')->assertOk();
        $this->artisan('sync:compact')->assertSuccessful();

        $this->assertFalse($this->adaBaris($tombstone), 'kursor bekas anggota tidak boleh menahan');
        $this->assertSame($tombstone, SyncScopeWatermark::untuk($pemilik->id, 'ruang-a'));
    }

    public function test_kursor_cakupan_tidak_aktif_lebih_dari_180_hari_tidak_menahan_dan_pulangnya_dijawab_410(): void
    {
        $user = $this->syncUser();
        $this->push($user, 'p-awal', 'hp', 1000); // id 1, supaya tombstone berid > 1
        $tombstone = $this->tombstoneTua($user, 'tx-1');
        $this->kursor($user, 'hp-lama', null, 0, 200);

        $this->artisan('sync:compact')->assertSuccessful();
        $this->assertFalse($this->adaBaris($tombstone));

        // Perangkat lama kembali dengan kursor 1 (< id tombstone): harus tarik ulang dari awal.
        $this->pullScoped($user, 'hp-lama', 'a', null, 1)->assertStatus(410)->assertJsonPath('code', 'cursor_expired');
        $this->pullScoped($user, 'hp-lama', 'a')->assertOk();
    }

    public function test_410_hanya_untuk_cakupan_yang_tombstone_nya_dikompaksi(): void
    {
        $user = $this->syncUser();
        $this->push($user, 'p-1', 'hp', 1000);
        $this->push($user, 'r-1', 'hp', 1000, 'ruang-a');
        $this->push($user, 'r-2', 'hp', 1000, 'ruang-b');
        $tombstone = $this->tombstoneTua($user, 'tx-ruang', 'ruang-a');
        $this->pullScoped($user, 'hp', 'r', 'ruang-a')->assertOk();

        $this->artisan('sync:compact')->assertSuccessful();
        $this->assertFalse($this->adaBaris($tombstone));

        // Kursor 1 < id tombstone: ruang-a kedaluwarsa; pribadi dan ruang-b tidak (kursornya pun tak berubah).
        $this->pullScoped($user, 'hp', 'r', 'ruang-a', 1)->assertStatus(410);
        $this->pullScoped($user, 'hp', 'a', null, 1)->assertOk();
        $this->pullScoped($user, 'hp', 'r', 'ruang-b', 1)->assertOk();
        // Pull lama memakai watermark global dan ikut kedaluwarsa.
        $this->pullLegacy($user, 'hp', 1)->assertStatus(410);
    }

    public function test_cakupan_sepi_tidak_terjebak_410_setelah_tarik_ulang(): void
    {
        $user = $this->syncUser();
        $this->push($user, 'r-1', 'hp', 1000, 'ruang-a');
        $this->push($user, 'r-2', 'hp', 1500, 'ruang-a');
        $tombstone = $this->tombstoneTua($user, 'tx-akhir', 'ruang-a'); // baris terakhir ruang itu
        $this->pullScoped($user, 'hp', 'r', 'ruang-a')->assertOk();
        $this->artisan('sync:compact')->assertSuccessful();
        $this->assertFalse($this->adaBaris($tombstone));

        $this->pullScoped($user, 'hp', 'r', 'ruang-a', 1)->assertStatus(410);
        $ulang = $this->pullScoped($user, 'hp', 'r', 'ruang-a')->assertOk();
        $this->assertCount(2, $ulang->json('entries'));

        // Kursor dari tarik ulang harus lolos pull berikutnya (tanpa ini 410 berulang tanpa akhir).
        $this->pullScoped($user, 'hp', 'r', 'ruang-a', $ulang->json('next_after'))->assertOk();
        $this->assertGreaterThanOrEqual($tombstone, $ulang->json('next_after'));
    }

    public function test_tombstone_akun_lain_tidak_ditahan_kursor_pribadi_akun_ini(): void
    {
        $saya = $this->syncUser();
        $lain = $this->syncUser();
        $tombstone = $this->tombstoneTua($saya, 'tx-1');
        $this->pullScoped($saya, 'hp', 'a')->assertOk();
        $this->kursor($lain, 'hp-lain', null, 0); // kursor pribadi akun LAIN di 0

        $this->artisan('sync:compact')->assertSuccessful();

        $this->assertFalse($this->adaBaris($tombstone));
    }

    public function test_kompaksi_idempoten_dan_menyisakan_baris_hidup(): void
    {
        $user = $this->syncUser();
        $hidup = $this->push($user, 'tx-hidup', 'hp', 1000);
        $tombstone = $this->tombstoneTua($user, 'tx-1');
        $this->pullScoped($user, 'hp', 'a')->assertOk();

        $this->artisan('sync:compact')->assertSuccessful();
        $this->artisan('sync:compact')->assertSuccessful();

        $this->assertFalse($this->adaBaris($tombstone));
        $this->assertTrue($this->adaBaris($hidup));
        $this->assertSame(1, SyncScopeWatermark::query()->count());
    }

    public function test_watermark_cakupan_tidak_pernah_turun(): void
    {
        $saya = $this->syncUser();
        $lain = $this->syncUser();
        SyncScopeWatermark::naikkan($saya->id, null, 50);
        SyncScopeWatermark::naikkan($saya->id, null, 20);
        SyncScopeWatermark::naikkan($saya->id, 'ruang-a', 7);
        SyncScopeWatermark::naikkan($lain->id, 'ruang-a', 9); // satu watermark bersama per ruang

        $this->assertSame(50, SyncScopeWatermark::untuk($saya->id, null));
        $this->assertSame(0, SyncScopeWatermark::untuk($lain->id, null));
        $this->assertSame(9, SyncScopeWatermark::untuk($saya->id, 'ruang-a'));
        $this->assertSame(9, SyncScopeWatermark::untuk($lain->id, 'ruang-a'));
    }
}
