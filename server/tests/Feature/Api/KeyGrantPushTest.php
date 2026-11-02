<?php

namespace Tests\Feature\Api;

use App\Models\RoomMember;
use App\Models\SyncLogEntry;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Tests\TestCase;

/**
 * Rekaman key_grant (record_type luar "g", key_grant langkah 4, sync-desain.md bagian F): hanya pemilik ruang yang
 * boleh mendorongnya. Isinya buram bagi server (disegel per anggota di klien); server hanya menegakkan siapa penerbitnya.
 */
class KeyGrantPushTest extends TestCase
{
    use RefreshDatabase;

    private const ROOM = 'ruang-keluarga-1';

    private function syncUser(): User
    {
        return User::factory()->create(['plans' => ['sync']]);
    }

    private function authHeader(User $user): array
    {
        $this->app['auth']->forgetGuards();

        return ['Authorization' => 'Bearer '.$user->createToken('test')->plainTextToken];
    }

    private function grant(string $recordId = 'tag00000000000000000000000000000001deadbeef'): array
    {
        return ['room_id' => self::ROOM, 'record_type' => 'g', 'record_id' => $recordId, 'device_id' => 'hp-pemilik',
            'payload' => base64_encode('keyset-tersegel'), 'changed_at' => '2026-11-03T10:00:00Z'];
    }

    private function push(User $user, array $entries)
    {
        return $this->withHeaders($this->authHeader($user))->postJson('/api/sync/push', ['entries' => $entries]);
    }

    private function ruangDenganPemilikDanAnggota(): array
    {
        $pemilik = $this->syncUser();
        $anggota = $this->syncUser();
        RoomMember::tambahAnggota($pemilik->id, self::ROOM);
        RoomMember::tambahAnggota($anggota->id, self::ROOM);

        return [$pemilik, $anggota];
    }

    public function test_the_owner_can_push_a_key_grant_and_members_can_pull_it_untouched(): void
    {
        [$pemilik, $anggota] = $this->ruangDenganPemilikDanAnggota();

        $this->push($pemilik, [$this->grant()])->assertOk();

        $respons = $this->withHeaders($this->authHeader($anggota))
            ->getJson('/api/sync/pull?scope=r&room_id='.self::ROOM.'&device_id=hp-anggota')->assertOk();
        $this->assertSame('g', $respons->json('entries.0.record_type'));
        $this->assertSame(base64_encode('keyset-tersegel'), $respons->json('entries.0.payload'));
    }

    public function test_a_plain_member_cannot_push_a_key_grant(): void
    {
        [, $anggota] = $this->ruangDenganPemilikDanAnggota();

        $this->push($anggota, [$this->grant()])->assertForbidden()->assertJsonPath('code', 'not_room_owner');

        $this->assertSame(0, SyncLogEntry::where('record_type', 'g')->count());
    }

    public function test_a_member_cannot_smuggle_a_key_grant_into_a_batch_of_ordinary_rows(): void
    {
        [, $anggota] = $this->ruangDenganPemilikDanAnggota();
        $biasa = ['room_id' => self::ROOM, 'record_type' => 'r', 'record_id' => 'x1', 'device_id' => 'hp-a',
            'payload' => 'cGF5bG9hZA==', 'changed_at' => '2026-11-03T10:00:00Z'];

        $this->push($anggota, [$biasa, $this->grant()])->assertForbidden()->assertJsonPath('code', 'not_room_owner');

        $this->assertSame(0, SyncLogEntry::where('room_id', self::ROOM)->count(), 'seluruh batch ditolak');
    }

    public function test_a_key_grant_without_a_room_is_rejected(): void
    {
        $pemilik = $this->syncUser();
        $tanpaRuang = $this->grant();
        unset($tanpaRuang['room_id']);

        $this->push($pemilik, [$tanpaRuang])->assertForbidden()->assertJsonPath('code', 'not_room_owner');
    }

    public function test_ordinary_rows_are_unaffected_for_plain_members(): void
    {
        [, $anggota] = $this->ruangDenganPemilikDanAnggota();
        $biasa = ['room_id' => self::ROOM, 'record_type' => 'r', 'record_id' => 'x1', 'device_id' => 'hp-a',
            'payload' => 'cGF5bG9hZA==', 'changed_at' => '2026-11-03T10:00:00Z'];

        $this->push($anggota, [$biasa])->assertOk();
    }

    public function test_the_new_owner_after_a_transfer_can_push_grants_and_the_old_owner_cannot(): void
    {
        [$lama, $baru] = $this->ruangDenganPemilikDanAnggota();
        $this->withHeaders($this->authHeader($lama))->postJson('/api/rooms/'.self::ROOM.'/members/'.$baru->id.'/make-owner')->assertNoContent();

        $this->push($baru, [$this->grant()])->assertOk();
        $this->push($lama, [$this->grant('tag00000000000000000000000000000002deadbeef')])->assertForbidden();
    }
}
