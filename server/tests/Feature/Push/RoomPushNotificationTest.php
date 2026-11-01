<?php

namespace Tests\Feature\Push;

use App\Models\PushToken;
use App\Models\RoomMember;
use App\Models\User;
use App\Providers\AppServiceProvider;
use App\Push\FcmSender;
use App\Push\FcmSendResult;
use App\Push\NullFcmSender;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Tests\Support\FakeFcmSender;
use Tests\TestCase;

/**
 * Pemicu kabar "ada catatan baru" setelah push sync ke ruang bersama (Tahap 9/13): hanya anggota LAIN,
 * dan data push hanya roomId + kind, tanpa nominal, nama, atau catatan.
 */
class RoomPushNotificationTest extends TestCase
{
    use RefreshDatabase;

    private FakeFcmSender $fcm;

    protected function setUp(): void
    {
        parent::setUp();
        $this->fcm = new FakeFcmSender;
        $this->app->instance(FcmSender::class, $this->fcm);
    }

    private function syncUser(array $atribut = []): User
    {
        return User::factory()->create(array_merge(['plans' => ['sync']], $atribut));
    }

    private function push(User $user, array $entries)
    {
        $this->app['auth']->forgetGuards();

        return $this->withHeaders(['Authorization' => 'Bearer '.$user->createToken('test')->plainTextToken])
            ->postJson('/api/sync/push', ['entries' => $entries]);
    }

    private function entry(?string $roomId, string $recordId, array $override = []): array
    {
        return array_merge([
            'room_id' => $roomId,
            'record_type' => 'r',
            'record_id' => $recordId,
            'device_id' => 'pixel-pencatat',
            // Payload terenkripsi di aplikasi; di tes diisi teks penanda supaya kebocoran mudah terlihat.
            'payload' => base64_encode('NOMINAL-150000|catatan: beli susu anak|akun: BCA'),
            'changed_at' => '2026-10-19T10:00:00Z',
            'changed_at_ms' => 1792404000000,
        ], $override);
    }

    /** Pencatat, pasangan (anggota lain), dan orang luar; masing-masing punya token push. */
    private function ruangBersama(): array
    {
        $pencatat = $this->syncUser(['name' => 'Nama Pencatat Rahasia', 'email' => 'pencatat@contoh.test']);
        $pasangan = $this->syncUser();
        $luar = $this->syncUser();
        RoomMember::tambahAnggota($pencatat->id, 'ruang-keluarga-1');
        RoomMember::tambahAnggota($pasangan->id, 'ruang-keluarga-1');
        PushToken::daftarkan($pencatat->id, 'hp-pencatat', 'fcm-pencatat');
        PushToken::daftarkan($pencatat->id, 'tablet-pencatat', 'fcm-pencatat-tablet');
        PushToken::daftarkan($pasangan->id, 'hp-pasangan', 'fcm-pasangan');
        PushToken::daftarkan($pasangan->id, 'tablet-pasangan', 'fcm-pasangan-tablet');
        PushToken::daftarkan($luar->id, 'hp-luar', 'fcm-luar');

        return [$pencatat, $pasangan, $luar];
    }

    public function test_push_ke_ruang_bersama_memberi_tahu_semua_perangkat_anggota_lain_saja(): void
    {
        [$pencatat] = $this->ruangBersama();

        $this->push($pencatat, [$this->entry('ruang-keluarga-1', 'tx-1')])->assertOk();

        $tokens = $this->fcm->tokens();
        sort($tokens);
        $this->assertSame(['fcm-pasangan', 'fcm-pasangan-tablet'], $tokens);
    }

    public function test_data_push_hanya_room_id_dan_kind_tanpa_nominal_nama_atau_catatan(): void
    {
        [$pencatat] = $this->ruangBersama();

        $this->push($pencatat, [$this->entry('ruang-keluarga-1', 'tx-1')])->assertOk();

        $this->assertNotEmpty($this->fcm->sent);
        foreach ($this->fcm->sent as $kiriman) {
            $this->assertSame(['roomId' => 'ruang-keluarga-1', 'kind' => 'NEW_RECORD'], $kiriman['data']);

            $serial = json_encode($kiriman['data']);
            foreach (['150000', 'NOMINAL', 'susu', 'BCA', 'Nama Pencatat Rahasia', 'pencatat@contoh.test', 'tx-1', 'pixel-pencatat', 'user', 'author'] as $rahasia) {
                $this->assertStringNotContainsString($rahasia, $serial);
            }
        }
    }

    public function test_satu_kabar_per_ruang_per_push_walau_banyak_baris(): void
    {
        [$pencatat] = $this->ruangBersama();

        $this->push($pencatat, [
            $this->entry('ruang-keluarga-1', 'tx-1'),
            $this->entry('ruang-keluarga-1', 'tx-2'),
            $this->entry('ruang-keluarga-1', 'tx-3'),
        ])->assertOk();

        $this->assertCount(2, $this->fcm->sent); // dua perangkat pasangan, sekali masing-masing
    }

    public function test_push_data_pribadi_tidak_memicu_kabar(): void
    {
        [$pencatat] = $this->ruangBersama();

        $this->push($pencatat, [$this->entry(null, 'acc-1')])->assertOk();

        $this->assertSame([], $this->fcm->sent);
    }

    public function test_push_ulang_idempoten_tidak_memicu_kabar_lagi(): void
    {
        [$pencatat] = $this->ruangBersama();
        $this->push($pencatat, [$this->entry('ruang-keluarga-1', 'tx-1')])->assertOk();
        $this->fcm->sent = [];

        $this->push($pencatat, [$this->entry('ruang-keluarga-1', 'tx-1')])->assertOk();

        $this->assertSame([], $this->fcm->sent);
    }

    public function test_baris_yang_ditolak_penjaga_penulis_pertama_tidak_memicu_kabar(): void
    {
        [$pencatat, $pasangan] = $this->ruangBersama();
        $this->push($pencatat, [$this->entry('ruang-keluarga-1', 'tx-1')])->assertOk();
        $this->fcm->sent = [];

        $this->push($pasangan, [$this->entry('ruang-keluarga-1', 'tx-1', ['device_id' => 'hp-pasangan', 'changed_at_ms' => 1792404999999])])
            ->assertOk()->assertJsonPath('ids', []);

        $this->assertSame([], $this->fcm->sent);
    }

    public function test_push_yang_ditolak_kuota_tidak_memicu_kabar(): void
    {
        [$pencatat] = $this->ruangBersama();
        config(['sync.quota.max_rows' => 0]);

        $this->push($pencatat, [$this->entry('ruang-keluarga-1', 'tx-1')])->assertStatus(422);

        $this->assertSame([], $this->fcm->sent);
    }

    public function test_token_yang_tidak_berlaku_dibuang_yang_lain_tetap(): void
    {
        [$pencatat] = $this->ruangBersama();
        $this->fcm->answer('fcm-pasangan', FcmSendResult::InvalidToken)
            ->answer('fcm-pasangan-tablet', FcmSendResult::Failed);

        $this->push($pencatat, [$this->entry('ruang-keluarga-1', 'tx-1')])->assertOk();

        $this->assertDatabaseMissing('push_tokens', ['token_hash' => PushToken::hashOf('fcm-pasangan')]);
        $this->assertDatabaseHas('push_tokens', ['token_hash' => PushToken::hashOf('fcm-pasangan-tablet')]);
    }

    public function test_anggota_yang_dikeluarkan_tidak_lagi_diberi_kabar(): void
    {
        [$pencatat, $pasangan] = $this->ruangBersama();
        RoomMember::where('user_id', $pasangan->id)->delete();

        $this->push($pencatat, [$this->entry('ruang-keluarga-1', 'tx-1')])->assertOk();

        $this->assertSame([], $this->fcm->sent);
    }

    public function test_tanpa_konfigurasi_fcm_pengirim_mati_dan_sync_tetap_jalan(): void
    {
        config(['services.fcm.project_id' => null, 'services.fcm.service_account_json' => null]);
        $this->app->forgetInstance(FcmSender::class);
        $this->app->offsetUnset(FcmSender::class);
        (new AppServiceProvider($this->app))->register();
        $this->assertInstanceOf(NullFcmSender::class, $this->app->make(FcmSender::class));

        [$pencatat] = $this->ruangBersama();
        $this->push($pencatat, [$this->entry('ruang-keluarga-1', 'tx-1')])->assertOk();

        $this->assertSame(5, PushToken::count()); // NotConfigured tidak membuang token apa pun
    }
}
