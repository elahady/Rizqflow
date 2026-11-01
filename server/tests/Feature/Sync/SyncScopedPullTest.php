<?php

namespace Tests\Feature\Sync;

use App\Models\RoomMember;
use App\Models\SyncCompactionState;
use App\Models\SyncScopeWatermark;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Testing\TestResponse;
use Tests\TestCase;

/**
 * Pull per cakupan: `GET /api/sync/pull?scope=a` dan `?scope=r&room_id=X` (sync-desain.md bagian G).
 * Isolasi (pribadi tidak bocor lewat `r`, ruang tidak bocor lewat `a`, bukan anggota ditolak), kursor
 * per cakupan, perilaku lama tanpa `scope`, dan konvergensi antar perangkat.
 */
class SyncScopedPullTest extends TestCase
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

    private function push(User $user, string $recordId, string $device, int $ms, ?string $roomId = null, bool $deleted = false): int
    {
        $response = $this->withHeaders($this->authHeader($user))->postJson('/api/sync/push', [
            'entries' => [[
                'room_id' => $roomId,
                'record_type' => 'r',
                'record_id' => $recordId,
                'device_id' => $device,
                'payload' => "cipher-{$recordId}-{$ms}",
                'deleted' => $deleted,
                'changed_at' => '2026-10-19T10:00:00Z',
                'changed_at_ms' => $ms,
            ]],
        ]);
        $response->assertOk();

        return (int) $response->json('ids.0');
    }

    private function pullScoped(User $user, string $device, string $scope, ?string $roomId = null, int $after = 0, int $limit = 500): TestResponse
    {
        $query = "scope={$scope}&device_id={$device}&after={$after}&limit={$limit}".($roomId === null ? '' : '&room_id='.urlencode($roomId));

        return $this->withHeaders($this->authHeader($user))->getJson("/api/sync/pull?{$query}");
    }

    private function pullLegacy(User $user, string $device, int $after = 0, int $limit = 500): TestResponse
    {
        return $this->withHeaders($this->authHeader($user))
            ->getJson("/api/sync/pull?device_id={$device}&after={$after}&limit={$limit}");
    }

    /** @return string[] record_id yang dikembalikan, urut id. */
    private function recordIds(TestResponse $response): array
    {
        return array_column($response->json('entries'), 'record_id');
    }

    public function test_scope_a_hanya_mengembalikan_baris_pribadi_akun_sendiri(): void
    {
        $saya = $this->syncUser();
        $lain = $this->syncUser();
        $this->push($saya, 'pribadi-saya', 'hp', 1000);
        $this->push($saya, 'ruang-saya', 'hp', 1000, 'ruang-a');
        $this->push($lain, 'pribadi-lain', 'hp-lain', 1000);
        $this->push($lain, 'ruang-lain', 'hp-lain', 1000, 'ruang-a');

        $response = $this->pullScoped($saya, 'hp', 'a')->assertOk();

        $this->assertSame(['pribadi-saya'], $this->recordIds($response));
        $response->assertJsonPath('scope', 'a');
        $response->assertJsonPath('room_id', null);
    }

    public function test_scope_r_mengembalikan_semua_baris_ruang_itu_dari_semua_anggota_tanpa_data_pribadi(): void
    {
        $saya = $this->syncUser();
        $pasangan = $this->syncUser();
        $this->push($saya, 'ruang-saya', 'hp', 1000, 'ruang-a');
        RoomMember::tambahAnggota($pasangan->id, 'ruang-a');
        $this->push($pasangan, 'ruang-pasangan', 'hp-p', 1000, 'ruang-a');
        $this->push($pasangan, 'pribadi-pasangan', 'hp-p', 1000);
        $this->push($saya, 'pribadi-saya', 'hp', 1000);
        $this->push($saya, 'ruang-b-saya', 'hp', 1000, 'ruang-b');

        $response = $this->pullScoped($saya, 'hp', 'r', 'ruang-a')->assertOk();

        $this->assertSame(['ruang-saya', 'ruang-pasangan'], $this->recordIds($response));
        $response->assertJsonPath('scope', 'r');
        $response->assertJsonPath('room_id', 'ruang-a');
        foreach ($response->json('entries') as $entry) {
            $this->assertSame('ruang-a', $entry['room_id']);
        }
    }

    public function test_bukan_anggota_ditolak_403_dan_tidak_didaftarkan_sebagai_anggota(): void
    {
        $pemilik = $this->syncUser();
        $orangAsing = $this->syncUser();
        $this->push($pemilik, 'rahasia', 'hp', 1000, 'ruang-a');

        $response = $this->pullScoped($orangAsing, 'hp-asing', 'r', 'ruang-a');

        $response->assertForbidden();
        $response->assertJsonPath('code', 'not_room_member');
        $this->assertArrayNotHasKey('entries', $response->json());
        $this->assertStringNotContainsString('cipher', $response->getContent());
        // Pull tidak boleh mendaftarkan siapa pun (beda dengan push dan join).
        $this->assertDatabaseMissing('room_members', ['user_id' => $orangAsing->id, 'room_id' => 'ruang-a']);
        // Dan tidak meninggalkan kursor atau catatan aktivitas untuk ruang itu.
        $this->assertDatabaseCount('sync_device_cursors', 0);
        $this->assertDatabaseMissing('sync_scope_activity', ['user_id' => $orangAsing->id]);
    }

    public function test_anggota_ruang_a_tidak_bisa_menarik_ruang_b(): void
    {
        $pemilikA = $this->syncUser();
        $pemilikB = $this->syncUser();
        $this->push($pemilikA, 'milik-a', 'hp-a', 1000, 'ruang-a');
        $this->push($pemilikB, 'milik-b', 'hp-b', 1000, 'ruang-b');

        $this->pullScoped($pemilikA, 'hp-a', 'r', 'ruang-b')->assertForbidden()->assertJsonPath('code', 'not_room_member');
        $this->pullScoped($pemilikB, 'hp-b', 'r', 'ruang-a')->assertForbidden()->assertJsonPath('code', 'not_room_member');
        // Anggota tetap bisa menarik ruangnya sendiri.
        $this->assertSame(['milik-a'], $this->recordIds($this->pullScoped($pemilikA, 'hp-a', 'r', 'ruang-a')->assertOk()));
    }

    public function test_bekas_anggota_yang_keluar_ditolak_dan_baris_yang_ia_tulis_tetap_untuk_anggota_lain(): void
    {
        $pemilik = $this->syncUser();
        $anggota = $this->syncUser();
        $this->push($pemilik, 'dari-pemilik', 'hp-p', 1000, 'ruang-a');
        $this->push($anggota, 'dari-anggota', 'hp-a', 1000, 'ruang-a');
        $this->pullScoped($anggota, 'hp-a', 'r', 'ruang-a')->assertOk();

        $this->withHeaders($this->authHeader($anggota))->postJson('/api/rooms/ruang-a/leave')->assertNoContent();

        $this->pullScoped($anggota, 'hp-a', 'r', 'ruang-a')->assertForbidden()->assertJsonPath('code', 'not_room_member');
        // Pemilik masih menerima kedua baris.
        $this->assertSame(['dari-pemilik', 'dari-anggota'], $this->recordIds($this->pullScoped($pemilik, 'hp-p', 'r', 'ruang-a')->assertOk()));
    }

    public function test_anggota_yang_dikeluarkan_pemilik_ditolak_menarik_ruang(): void
    {
        $pemilik = $this->syncUser();
        $anggota = $this->syncUser();
        $this->push($pemilik, 'dari-pemilik', 'hp-p', 1000, 'ruang-a');
        RoomMember::tambahAnggota($anggota->id, 'ruang-a');
        $this->pullScoped($anggota, 'hp-a', 'r', 'ruang-a')->assertOk();

        $this->withHeaders($this->authHeader($pemilik))
            ->deleteJson("/api/rooms/ruang-a/members/{$anggota->id}")->assertNoContent();

        $this->pullScoped($anggota, 'hp-a', 'r', 'ruang-a')->assertForbidden()->assertJsonPath('code', 'not_room_member');
        // Tarikan lama tanpa scope tetap melayani baris tulisan sendiri (perilaku lama tidak berubah),
        // tetapi baris pemilik tidak lagi sampai ke bekas anggota.
        $this->assertNotContains('dari-pemilik', $this->recordIds($this->pullLegacy($anggota, 'hp-a')->assertOk()));
    }

    public function test_bekas_anggota_tidak_mendapat_baris_ruang_lewat_scope_a(): void
    {
        $pemilik = $this->syncUser();
        $anggota = $this->syncUser();
        $this->push($pemilik, 'dari-pemilik', 'hp-p', 1000, 'ruang-a');
        $this->push($anggota, 'ruang-tulisan-anggota', 'hp-a', 1000, 'ruang-a');
        $this->withHeaders($this->authHeader($anggota))->postJson('/api/rooms/ruang-a/leave')->assertNoContent();

        // Baris ruang bukan milik cakupan pribadi, walau penulisnya dia sendiri.
        $this->assertSame([], $this->recordIds($this->pullScoped($anggota, 'hp-a', 'a')->assertOk()));
    }

    public function test_parameter_cakupan_yang_tidak_sah_ditolak_422(): void
    {
        $user = $this->syncUser();

        $this->pullScoped($user, 'hp', 'x')->assertStatus(422)->assertJsonPath('code', 'invalid_scope');
        $this->pullScoped($user, 'hp', 'r')->assertStatus(422)->assertJsonPath('code', 'invalid_scope'); // tanpa room_id
        $this->pullScoped($user, 'hp', 'a', 'ruang-a')->assertStatus(422)->assertJsonPath('code', 'invalid_scope'); // a + room_id
        // room_id tanpa scope itu ambigu: ditolak, bukan diam-diam dianggap pull lama.
        $this->withHeaders($this->authHeader($user))
            ->getJson('/api/sync/pull?device_id=hp&room_id=ruang-a')->assertStatus(422)->assertJsonPath('code', 'invalid_scope');
        // scope kosong atau berbentuk larik tidak lolos jadi pull lama.
        $this->withHeaders($this->authHeader($user))
            ->getJson('/api/sync/pull?device_id=hp&scope[]=a')->assertStatus(422)->assertJsonPath('code', 'invalid_scope');
        $this->pullScoped($user, 'hp', 'r', str_repeat('x', 101))->assertStatus(422)->assertJsonPath('code', 'invalid_room_id');
        $this->assertDatabaseCount('sync_device_cursors', 0);
    }

    public function test_urutan_galat_401_403_sync_lalu_422_device_id(): void
    {
        $this->getJson('/api/sync/pull?scope=a&device_id=hp')->assertUnauthorized();

        $tanpaSync = User::factory()->create();
        $this->pullScoped($tanpaSync, 'hp', 'a')->assertForbidden()->assertJsonPath('code', 'sync_required');

        $user = $this->syncUser();
        $this->withHeaders($this->authHeader($user))->getJson('/api/sync/pull?scope=a')
            ->assertStatus(422)->assertJsonPath('code', 'device_id_required');
    }

    public function test_pull_tanpa_scope_tetap_berperilaku_lama_dan_tanpa_bidang_baru(): void
    {
        $saya = $this->syncUser();
        $pasangan = $this->syncUser();
        $this->push($saya, 'pribadi-saya', 'hp', 1000);
        $this->push($saya, 'ruang-saya', 'hp', 1000, 'ruang-a');
        RoomMember::tambahAnggota($pasangan->id, 'ruang-a');
        $this->push($pasangan, 'ruang-pasangan', 'hp-p', 1000, 'ruang-a');
        $this->push($pasangan, 'pribadi-pasangan', 'hp-p', 1000);

        $response = $this->pullLegacy($saya, 'hp')->assertOk();

        $this->assertSame(['pribadi-saya', 'ruang-saya', 'ruang-pasangan'], $this->recordIds($response));
        $this->assertArrayNotHasKey('scope', $response->json());
        $this->assertArrayNotHasKey('room_id', $response->json());
        // Pull lama memakai kursor global dan tidak menyentuh kursor per cakupan.
        $this->assertDatabaseHas('sync_devices', ['user_id' => $saya->id, 'device_id' => 'hp']);
        $this->assertDatabaseCount('sync_device_cursors', 0);
    }

    public function test_pull_per_cakupan_menyimpan_kursor_per_cakupan_dan_tidak_menyentuh_kursor_global(): void
    {
        $user = $this->syncUser();
        $this->push($user, 'p-1', 'hp', 1000);
        $idRuang = $this->push($user, 'r-1', 'hp', 1000, 'ruang-a');

        $a = $this->pullScoped($user, 'hp', 'a')->assertOk();
        $r = $this->pullScoped($user, 'hp', 'r', 'ruang-a')->assertOk();

        $this->assertDatabaseHas('sync_device_cursors', ['user_id' => $user->id, 'device_id' => 'hp', 'room_id' => '', 'after_id' => $a->json('next_after')]);
        $this->assertDatabaseHas('sync_device_cursors', ['user_id' => $user->id, 'device_id' => 'hp', 'room_id' => 'ruang-a', 'after_id' => $idRuang]);
        $this->assertSame($idRuang, $r->json('next_after'));
        $this->assertDatabaseMissing('sync_devices', ['user_id' => $user->id, 'device_id' => 'hp']);
    }

    public function test_kursor_per_cakupan_bergerak_mundur_saat_klien_sinkron_ulang_dari_nol(): void
    {
        $user = $this->syncUser();
        $this->push($user, 'p-1', 'hp', 1000);
        $this->push($user, 'p-2', 'hp', 1000);
        $this->pullScoped($user, 'hp', 'a')->assertOk();
        $this->assertDatabaseHas('sync_device_cursors', ['room_id' => '', 'after_id' => 2]);

        // Klien memulihkan dari cadangan dan mulai dari 0 dengan halaman kecil: kursor server ikut turun,
        // supaya kompaksi kembali menahan tombstone yang belum ia lihat.
        $this->pullScoped($user, 'hp', 'a', null, 0, 1)->assertOk();

        $this->assertDatabaseHas('sync_device_cursors', ['room_id' => '', 'after_id' => 1]);
        $this->assertDatabaseCount('sync_device_cursors', 1);
    }

    public function test_paginasi_per_cakupan_dengan_has_more_dan_kursor_antar_cakupan_tidak_tercampur(): void
    {
        $user = $this->syncUser();
        $this->push($user, 'r-1', 'hp', 1000, 'ruang-a');
        $this->push($user, 'p-1', 'hp', 1000);
        $this->push($user, 'r-2', 'hp', 1000, 'ruang-a');
        $this->push($user, 'p-2', 'hp', 1000);
        $this->push($user, 'r-3', 'hp', 1000, 'ruang-a');

        $halaman1 = $this->pullScoped($user, 'hp', 'r', 'ruang-a', 0, 2)->assertOk();
        $this->assertSame(['r-1', 'r-2'], $this->recordIds($halaman1));
        $this->assertTrue($halaman1->json('has_more'));

        $halaman2 = $this->pullScoped($user, 'hp', 'r', 'ruang-a', $halaman1->json('next_after'), 2)->assertOk();
        $this->assertSame(['r-3'], $this->recordIds($halaman2));
        $this->assertFalse($halaman2->json('has_more'));

        // Kursor ruang tidak memajukan cakupan pribadi: pribadi masih mulai dari 0.
        $this->assertSame(['p-1', 'p-2'], $this->recordIds($this->pullScoped($user, 'hp', 'a')->assertOk()));
    }

    public function test_halaman_terakhir_memajukan_kursor_ke_watermark_cakupan_dan_tidak_terjebak_410(): void
    {
        $user = $this->syncUser();
        $this->push($user, 'p-1', 'hp', 1000);
        $this->push($user, 'p-2', 'hp', 1000);
        // Tombstone terakhir cakupan ini (id 3) sudah dikompaksi: baris yang tersisa berid lebih kecil.
        SyncScopeWatermark::naikkan($user->id, null, 3);

        $this->pullScoped($user, 'hp', 'a', null, 1)->assertStatus(410)->assertJsonPath('code', 'cursor_expired');
        $dariNol = $this->pullScoped($user, 'hp', 'a')->assertOk();
        $this->assertSame(['p-1', 'p-2'], $this->recordIds($dariNol));
        $this->assertSame(3, $dariNol->json('next_after'));

        // Tarikan berikutnya dengan kursor itu lolos (sebelumnya 2 < 3 menjawab 410 selamanya).
        $this->pullScoped($user, 'hp', 'a', null, $dariNol->json('next_after'))->assertOk()->assertJsonPath('entries', []);
    }

    public function test_410_hanya_mengenai_cakupan_yang_tombstone_nya_dikompaksi(): void
    {
        $saya = $this->syncUser();
        $lain = $this->syncUser();
        $this->push($saya, 'p-1', 'hp', 1000);
        $this->push($saya, 'r-1', 'hp', 1000, 'ruang-a');
        $this->push($saya, 'r-2', 'hp', 1000, 'ruang-b');
        $this->push($lain, 'p-lain', 'hp-lain', 1000);
        // Watermark: pribadi akun saya = 50, ruang-a = 40. Global juga 50.
        SyncScopeWatermark::naikkan($saya->id, null, 50);
        SyncScopeWatermark::naikkan($saya->id, 'ruang-a', 40);
        SyncCompactionState::catatJalan(0, 0, 50);

        $this->pullScoped($saya, 'hp', 'a', null, 10)->assertStatus(410);
        $this->pullScoped($saya, 'hp', 'r', 'ruang-a', 10)->assertStatus(410);
        // Ruang lain, dan akun lain, tidak ikut kedaluwarsa walau di bawah watermark global.
        $this->pullScoped($saya, 'hp', 'r', 'ruang-b', 10)->assertOk();
        $this->pullScoped($lain, 'hp-lain', 'a', null, 10)->assertOk();
        // Pull lama tetap memakai watermark global.
        $this->pullLegacy($saya, 'hp', 10)->assertStatus(410);
        // after=0 selalu diizinkan.
        $this->pullScoped($saya, 'hp', 'a')->assertOk();
    }

    public function test_pull_lama_juga_tidak_terjebak_410_saat_baris_terakhirnya_di_bawah_watermark_global(): void
    {
        $user = $this->syncUser();
        $this->push($user, 'p-1', 'hp', 1000);
        SyncCompactionState::catatJalan(0, 0, 9);

        $dariNol = $this->pullLegacy($user, 'hp')->assertOk();
        $this->assertSame(9, $dariNol->json('next_after'));

        $this->pullLegacy($user, 'hp', 9)->assertOk();
    }

    public function test_authors_buram_dan_tidak_ada_id_pengguna_di_jawaban_per_cakupan(): void
    {
        $saya = $this->syncUser();
        $pasangan = $this->syncUser();
        $this->push($saya, 'r-1', 'hp', 1000, 'ruang-a');
        RoomMember::tambahAnggota($pasangan->id, 'ruang-a');
        $this->push($pasangan, 'r-2', 'hp-p', 1000, 'ruang-a');

        $response = $this->pullScoped($saya, 'hp', 'r', 'ruang-a')->assertOk();

        $authors = array_column($response->json('entries'), 'author');
        $this->assertCount(2, array_unique($authors));
        foreach ($response->json('entries') as $entry) {
            $this->assertArrayNotHasKey('user_id', $entry);
        }
    }

    /**
     * Simulasi klien: terapkan baris dengan urutan total (cap waktu, tombstone menang, device_id terbesar)
     * seperti `SyncOrdering` di Android, lalu kembalikan {record_id => 'hidup'|'hapus'}.
     *
     * @param  array<int, array<string, mixed>>  $entries
     * @param  array<string, array<string, mixed>>  $state
     * @return array<string, array<string, mixed>>
     */
    private function terapkan(array $state, array $entries): array
    {
        foreach ($entries as $e) {
            $key = $e['room_id'].'|'.$e['record_id'];
            $order = [$e['changed_at_ms'], $e['deleted'] ? 1 : 0, $e['device_id']];
            if (! isset($state[$key]) || $order > $state[$key]['order']) {
                $state[$key] = ['order' => $order, 'deleted' => $e['deleted']];
            }
        }

        return $state;
    }

    /** @return array<string, string> */
    private function ringkas(array $state): array
    {
        $hasil = [];
        foreach ($state as $key => $s) {
            $hasil[$key] = $s['deleted'] ? 'hapus' : 'hidup';
        }
        ksort($hasil);

        return $hasil;
    }

    public function test_konvergensi_dua_perangkat_per_cakupan_sama_dengan_pull_lama(): void
    {
        $saya = $this->syncUser();
        $pasangan = $this->syncUser();

        // Aktivitas dari beberapa perangkat dan dua akun: tambah, ubah, hapus, hidupkan lagi.
        $this->push($saya, 'p-1', 'hp', 1000);
        $this->push($saya, 'p-2', 'hp', 1000);
        $this->push($saya, 'r-1', 'hp', 1000, 'ruang-a');
        RoomMember::tambahAnggota($pasangan->id, 'ruang-a');
        $this->push($pasangan, 'r-2', 'hp-p', 1500, 'ruang-a');
        $this->push($saya, 'p-1', 'tablet', 2000, null, true);          // hapus p-1 dari tablet
        $this->push($saya, 'p-2', 'tablet', 2500);                      // ubah p-2
        $this->push($saya, 'r-1', 'hp', 3000, 'ruang-a', true);         // hapus r-1
        $this->push($saya, 'r-1', 'tablet', 3500, 'ruang-a');           // hidupkan lagi r-1
        $this->push($saya, 'p-3', 'tablet', 4000);

        // Perangkat A menarik per cakupan dengan halaman kecil; perangkat B memakai pull lama.
        $state = [];
        foreach (['a' => null, 'r' => 'ruang-a'] as $scope => $room) {
            $after = 0;
            do {
                $halaman = $this->pullScoped($saya, 'perangkat-a', $scope, $room, $after, 2)->assertOk();
                $state = $this->terapkan($state, $halaman->json('entries'));
                $after = $halaman->json('next_after');
            } while ($halaman->json('has_more'));
        }

        $stateLama = [];
        $after = 0;
        do {
            $halaman = $this->pullLegacy($saya, 'perangkat-b', $after, 2)->assertOk();
            $stateLama = $this->terapkan($stateLama, $halaman->json('entries'));
            $after = $halaman->json('next_after');
        } while ($halaman->json('has_more'));

        $this->assertSame($this->ringkas($stateLama), $this->ringkas($state));
        $this->assertSame(
            ['ruang-a|r-1' => 'hidup', 'ruang-a|r-2' => 'hidup', '|p-1' => 'hapus', '|p-2' => 'hidup', '|p-3' => 'hidup'],
            $this->ringkas($state),
        );
    }

    public function test_dua_anggota_ruang_konvergen_dan_pribadi_masing_masing_tidak_tercampur(): void
    {
        $saya = $this->syncUser();
        $pasangan = $this->syncUser();
        $this->push($saya, 'r-1', 'hp', 1000, 'ruang-a');
        RoomMember::tambahAnggota($pasangan->id, 'ruang-a');
        $this->push($pasangan, 'r-2', 'hp-p', 1100, 'ruang-a');
        $this->push($saya, 'p-saya', 'hp', 1200);
        $this->push($pasangan, 'p-pasangan', 'hp-p', 1300);
        $this->push($saya, 'r-1', 'hp', 1400, 'ruang-a', true);

        $sayaRuang = $this->terapkan([], $this->pullScoped($saya, 'hp', 'r', 'ruang-a')->assertOk()->json('entries'));
        $pasanganRuang = $this->terapkan([], $this->pullScoped($pasangan, 'hp-p', 'r', 'ruang-a')->assertOk()->json('entries'));

        $this->assertSame($this->ringkas($sayaRuang), $this->ringkas($pasanganRuang));
        $this->assertSame(['p-saya'], $this->recordIds($this->pullScoped($saya, 'hp', 'a')->assertOk()));
        $this->assertSame(['p-pasangan'], $this->recordIds($this->pullScoped($pasangan, 'hp-p', 'a')->assertOk()));
    }
}
