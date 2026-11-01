<?php

namespace Tests\Feature\Api;

use App\Models\PushToken;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use PHPUnit\Framework\Attributes\DataProvider;
use Tests\Support\UsesApiTokens;
use Tests\TestCase;

/** Daftar dan cabut token FCM perangkat (Tahap 9/13): `PUT /api/push-token`, `DELETE /api/push-token/{deviceId}`. */
class PushTokenTest extends TestCase
{
    use RefreshDatabase;
    use UsesApiTokens;

    private function daftar(string $apiToken, string $deviceId, string $fcmToken)
    {
        return $this->api('PUT', '/api/push-token', $apiToken, ['device_id' => $deviceId, 'token' => $fcmToken]);
    }

    public function test_mendaftarkan_token_menyimpannya_untuk_akun_dan_perangkat_ini(): void
    {
        $user = User::factory()->create();
        $api = $this->tokenFor($user);

        $response = $this->daftar($api, 'hp-1', 'fcm-token-rahasia-1');

        $response->assertOk()->assertJsonPath('device_id', 'hp-1');
        $this->assertStringNotContainsString('fcm-token-rahasia-1', $response->getContent());
        $this->assertDatabaseHas('push_tokens', ['user_id' => $user->id, 'device_id' => 'hp-1', 'token' => 'fcm-token-rahasia-1']);
    }

    public function test_daftar_ulang_perangkat_yang_sama_mengganti_tokennya(): void
    {
        $user = User::factory()->create();
        $api = $this->tokenFor($user);

        $this->daftar($api, 'hp-1', 'lama')->assertOk();
        $this->daftar($api, 'hp-1', 'baru')->assertOk();

        $this->assertSame(['baru'], PushToken::where('user_id', $user->id)->pluck('token')->all());
    }

    public function test_token_yang_sama_dari_akun_lain_berpindah_ke_akun_itu(): void
    {
        $lama = User::factory()->create();
        $baru = User::factory()->create();

        $this->daftar($this->tokenFor($lama), 'hp-1', 'fcm-ponsel-yang-sama')->assertOk();
        $this->daftar($this->tokenFor($baru), 'hp-1', 'fcm-ponsel-yang-sama')->assertOk();

        $this->assertDatabaseMissing('push_tokens', ['user_id' => $lama->id]);
        $this->assertDatabaseHas('push_tokens', ['user_id' => $baru->id, 'device_id' => 'hp-1']);
    }

    public function test_plafon_per_akun_membuang_yang_paling_lama(): void
    {
        $user = User::factory()->create();
        $api = $this->tokenFor($user);

        for ($i = 0; $i <= PushToken::MAX_PER_USER; $i++) {
            $this->travel(1)->minutes();
            $this->daftar($api, "hp-{$i}", "fcm-{$i}")->assertOk();
        }

        $this->assertSame(PushToken::MAX_PER_USER, PushToken::where('user_id', $user->id)->count());
        $this->assertDatabaseMissing('push_tokens', ['device_id' => 'hp-0']);
    }

    public static function bodyTidakSah(): array
    {
        return [
            'tanpa device_id' => [['token' => 'abc']],
            'tanpa token' => [['device_id' => 'hp-1']],
            'device_id berspasi' => [['device_id' => 'hp 1', 'token' => 'abc']],
            'device_id terlalu panjang' => [['device_id' => str_repeat('a', 101), 'token' => 'abc']],
            'token berspasi' => [['device_id' => 'hp-1', 'token' => 'ab c']],
            'token terlalu panjang' => [['device_id' => 'hp-1', 'token' => str_repeat('a', 4097)]],
        ];
    }

    #[DataProvider('bodyTidakSah')]
    public function test_body_tidak_sah_ditolak_422(array $body): void
    {
        $user = User::factory()->create();

        $this->api('PUT', '/api/push-token', $this->tokenFor($user), $body)->assertStatus(422);
        $this->assertDatabaseCount('push_tokens', 0);
    }

    public function test_cabut_hanya_menghapus_perangkat_itu_milik_akun_ini(): void
    {
        $user = User::factory()->create();
        $lain = User::factory()->create();
        $api = $this->tokenFor($user);
        $this->daftar($api, 'hp-1', 'fcm-1')->assertOk();
        $this->daftar($api, 'hp-2', 'fcm-2')->assertOk();
        $this->daftar($this->tokenFor($lain), 'hp-1', 'fcm-lain')->assertOk();

        $this->api('DELETE', '/api/push-token/hp-1', $api)->assertNoContent();

        $this->assertDatabaseMissing('push_tokens', ['user_id' => $user->id, 'device_id' => 'hp-1']);
        $this->assertDatabaseHas('push_tokens', ['user_id' => $user->id, 'device_id' => 'hp-2']);
        // Akun lain dengan device_id sama tidak tersentuh (isolasi akses).
        $this->assertDatabaseHas('push_tokens', ['user_id' => $lain->id, 'device_id' => 'hp-1']);
    }

    public function test_cabut_idempoten(): void
    {
        $user = User::factory()->create();

        $this->api('DELETE', '/api/push-token/hp-belum-pernah', $this->tokenFor($user))->assertNoContent();
    }

    public function test_tanpa_token_api_ditolak_401(): void
    {
        $this->api('PUT', '/api/push-token', null, ['device_id' => 'hp-1', 'token' => 'abc'])->assertUnauthorized();
        $this->api('DELETE', '/api/push-token/hp-1')->assertUnauthorized();
        $this->assertDatabaseCount('push_tokens', 0);
    }

    public function test_keluar_mencabut_token_push_perangkat_itu_saja(): void
    {
        $user = User::factory()->create();
        $hp1 = $this->tokenFor($user, 'android:hp-1');
        $hp2 = $this->tokenFor($user, 'android:hp-2');
        $this->daftar($hp1, 'hp-1', 'fcm-1')->assertOk();
        $this->daftar($hp2, 'hp-2', 'fcm-2')->assertOk();

        $this->api('POST', '/api/auth/logout', $hp1)->assertNoContent();

        $this->assertDatabaseMissing('push_tokens', ['device_id' => 'hp-1']);
        $this->assertDatabaseHas('push_tokens', ['device_id' => 'hp-2']);
    }

    public function test_keluar_dari_semua_perangkat_mencabut_semua_token_push(): void
    {
        $user = User::factory()->create();
        $lain = User::factory()->create();
        $hp1 = $this->tokenFor($user, 'android:hp-1');
        $this->daftar($hp1, 'hp-1', 'fcm-1')->assertOk();
        $this->daftar($hp1, 'hp-2', 'fcm-2')->assertOk();
        $this->daftar($this->tokenFor($lain), 'hp-9', 'fcm-lain')->assertOk();

        $this->api('DELETE', '/api/auth/tokens', $hp1)->assertNoContent();

        $this->assertSame(0, PushToken::where('user_id', $user->id)->count());
        $this->assertSame(1, PushToken::where('user_id', $lain->id)->count());
    }

    public function test_hapus_akun_ikut_menghapus_token_push(): void
    {
        $user = User::factory()->create();
        $api = $this->tokenFor($user);
        $this->daftar($api, 'hp-1', 'fcm-1')->assertOk();

        $this->api('DELETE', '/api/account', $api)->assertNoContent();

        $this->assertDatabaseCount('push_tokens', 0);
    }

    public function test_token_tidak_ikut_serialisasi_model(): void
    {
        $user = User::factory()->create();
        $baris = PushToken::daftarkan($user->id, 'hp-1', 'fcm-rahasia');

        $this->assertStringNotContainsString('fcm-rahasia', $baris->toJson());
    }
}
