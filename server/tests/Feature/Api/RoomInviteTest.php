<?php

namespace Tests\Feature\Api;

use App\Models\RoomInvite;
use App\Models\RoomMember;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Tests\TestCase;

/**
 * Undangan terdaftar untuk ruang yang dibagi (key_grant langkah 1, sync-desain.md bagian F; keputusan
 * pemilik 2026-11-02 dan 2026-11-03): sekali pakai, kedaluwarsa 7 hari, tanpa masa tenggang, server hanya
 * menyimpan hash.
 */
class RoomInviteTest extends TestCase
{
    use RefreshDatabase;

    private const ROOM = 'ruang-keluarga-1';

    private function syncUser(): User
    {
        return User::factory()->create(['plans' => ['sync']]);
    }

    /** @return array<string, string> */
    private function authHeader(User $user): array
    {
        // Token baru per pemanggilan + guard dilupakan, supaya pengguna silih berganti dalam satu tes.
        $this->app['auth']->forgetGuards();

        return ['Authorization' => 'Bearer '.$user->createToken('test')->plainTextToken];
    }

    private function hash(): string
    {
        return bin2hex(random_bytes(32));
    }

    private function buatRuang(User $pemilik, string $roomId = self::ROOM): void
    {
        $this->withHeaders($this->authHeader($pemilik))->postJson("/api/rooms/$roomId/join")->assertNoContent();
    }

    private function terbitkan(User $pemilik, string $hash, string $roomId = self::ROOM): \Illuminate\Testing\TestResponse
    {
        return $this->withHeaders($this->authHeader($pemilik))->postJson("/api/rooms/$roomId/invites", ['invite_hash' => $hash]);
    }

    private function gabung(User $tamu, ?string $hash, string $roomId = self::ROOM): \Illuminate\Testing\TestResponse
    {
        return $this->withHeaders($this->authHeader($tamu))
            ->postJson("/api/rooms/$roomId/join", $hash === null ? [] : ['invite_hash' => $hash]);
    }

    // ---- penerbitan

    public function test_the_owner_registers_an_invite_that_expires_in_seven_days(): void
    {
        $pemilik = $this->syncUser();
        $this->buatRuang($pemilik);
        $hash = $this->hash();

        $respons = $this->terbitkan($pemilik, $hash)->assertCreated();

        $undangan = RoomInvite::where('room_id', self::ROOM)->where('invite_hash', $hash)->firstOrFail();
        $this->assertSame($pemilik->id, $undangan->issued_by);
        $this->assertNull($undangan->used_by_user_id);
        $this->assertEqualsWithDelta(now()->addDays(7)->timestamp, $undangan->expires_at->timestamp, 5);
        $this->assertSame($undangan->expires_at->toIso8601String(), $respons->json('expires_at'));
    }

    public function test_only_the_room_owner_can_register_invites(): void
    {
        $pemilik = $this->syncUser();
        $anggota = $this->syncUser();
        $this->buatRuang($pemilik);
        $this->gabung($anggota, $this->undanganSah($pemilik))->assertNoContent();

        $this->terbitkan($anggota, $this->hash())->assertForbidden()->assertJsonPath('code', 'not_room_owner');
        $this->terbitkan($this->syncUser(), $this->hash())->assertForbidden()->assertJsonPath('code', 'not_room_owner');
        $this->assertSame(1, RoomInvite::count()); // hanya undangan milik anggota tadi
    }

    public function test_registering_an_invite_requires_the_owners_sync(): void
    {
        $pemilik = $this->syncUser();
        $this->buatRuang($pemilik);
        $pemilik->update(['plans' => []]);

        $this->terbitkan($pemilik, $this->hash())->assertForbidden()->assertJsonPath('code', 'sync_required');
        $this->assertSame(0, RoomInvite::count());
    }

    public function test_registering_the_same_unused_invite_again_refreshes_its_expiry_without_a_duplicate(): void
    {
        $pemilik = $this->syncUser();
        $this->buatRuang($pemilik);
        $hash = $this->hash();
        $this->terbitkan($pemilik, $hash)->assertCreated();
        RoomInvite::query()->update(['expires_at' => now()->addHour()]);

        $this->terbitkan($pemilik, $hash)->assertCreated();

        $this->assertSame(1, RoomInvite::count());
        $this->assertEqualsWithDelta(now()->addDays(7)->timestamp, RoomInvite::first()->expires_at->timestamp, 5);
    }

    public function test_an_already_used_invite_cannot_be_registered_again(): void
    {
        $pemilik = $this->syncUser();
        $tamu = $this->syncUser();
        $this->buatRuang($pemilik);
        $hash = $this->hash();
        $this->terbitkan($pemilik, $hash)->assertCreated();
        $this->gabung($tamu, $hash)->assertNoContent();

        $this->terbitkan($pemilik, $hash)->assertStatus(409)->assertJsonPath('code', 'invite_used');
    }

    public function test_an_invite_hash_must_be_64_hex_characters(): void
    {
        $pemilik = $this->syncUser();
        $this->buatRuang($pemilik);

        foreach (['', 'abc', str_repeat('z', 64), str_repeat('a', 63), str_repeat('a', 65)] as $buruk) {
            $this->terbitkan($pemilik, $buruk)->assertStatus(422);
        }
        $this->withHeaders($this->authHeader($pemilik))->postJson('/api/rooms/'.self::ROOM.'/invites')->assertStatus(422);
        $this->assertSame(0, RoomInvite::count());
    }

    public function test_beyond_the_pending_cap_the_oldest_unused_invite_is_dropped_instead_of_locking_the_owner_out(): void
    {
        $pemilik = $this->syncUser();
        $this->buatRuang($pemilik);
        $hashes = [];
        for ($i = 0; $i < RoomInvite::MAX_PENDING; $i++) {
            $this->terbitkan($pemilik, $hashes[] = $this->hash())->assertCreated();
        }
        $baru = $this->hash();

        $this->terbitkan($pemilik, $baru)->assertCreated();

        $this->assertSame(RoomInvite::MAX_PENDING, RoomInvite::count());
        $this->assertSame(0, RoomInvite::where('invite_hash', $hashes[0])->count(), 'yang paling lama disingkirkan');
        $this->assertSame(1, RoomInvite::where('invite_hash', $baru)->count());
        $this->assertSame(1, RoomInvite::where('invite_hash', $hashes[1])->count());
    }

    public function test_an_invite_that_was_used_is_never_dropped_by_the_cap(): void
    {
        $pemilik = $this->syncUser();
        $tamu = $this->syncUser();
        $this->buatRuang($pemilik);
        $dipakai = $this->hash();
        $this->terbitkan($pemilik, $dipakai)->assertCreated();
        $this->gabung($tamu, $dipakai)->assertNoContent();
        for ($i = 0; $i < RoomInvite::MAX_PENDING + 3; $i++) {
            $this->terbitkan($pemilik, $this->hash())->assertCreated();
        }

        // Yang sudah dipakai tetap ada: daftar anggota masih menunjukkan hash-nya (dasar key_grant).
        $this->assertSame($tamu->id, RoomInvite::where('invite_hash', $dipakai)->firstOrFail()->used_by_user_id);
        $this->assertSame(RoomInvite::MAX_PENDING, RoomInvite::whereNull('used_by_user_id')->count());
    }

    public function test_expired_unused_invites_are_swept_when_a_new_one_is_issued(): void
    {
        $pemilik = $this->syncUser();
        $this->buatRuang($pemilik);
        $this->terbitkan($pemilik, $kadaluwarsa = $this->hash())->assertCreated();
        RoomInvite::query()->update(['expires_at' => now()->subMinute()]);

        $this->terbitkan($pemilik, $this->hash())->assertCreated();

        $this->assertSame(0, RoomInvite::where('invite_hash', $kadaluwarsa)->count());
        $this->assertSame(1, RoomInvite::count());
    }

    public function test_the_hash_is_stored_in_lower_case(): void
    {
        $pemilik = $this->syncUser();
        $tamu = $this->syncUser();
        $this->buatRuang($pemilik);
        $hash = $this->hash();

        $this->terbitkan($pemilik, strtoupper($hash))->assertCreated();
        $this->assertSame($hash, RoomInvite::first()->invite_hash);

        $this->gabung($tamu, strtoupper($hash))->assertNoContent();
    }

    // ---- bergabung

    public function test_a_stranger_cannot_join_an_occupied_room_without_an_invite(): void
    {
        $pemilik = $this->syncUser();
        $asing = $this->syncUser();
        $this->buatRuang($pemilik);

        $this->gabung($asing, null)->assertForbidden()->assertJsonPath('code', 'invite_required');
        $this->assertSame(0, RoomMember::where('user_id', $asing->id)->count());
    }

    public function test_an_unknown_invite_is_rejected_and_does_not_reveal_whether_it_ever_existed(): void
    {
        $pemilik = $this->syncUser();
        $asing = $this->syncUser();
        $this->buatRuang($pemilik);

        $this->gabung($asing, $this->hash())->assertForbidden()->assertJsonPath('code', 'invite_invalid');
        $this->assertSame(0, RoomMember::where('user_id', $asing->id)->count());
    }

    public function test_a_valid_invite_adds_the_member_and_is_consumed(): void
    {
        $pemilik = $this->syncUser();
        $tamu = $this->syncUser();
        $this->buatRuang($pemilik);
        $hash = $this->hash();
        $this->terbitkan($pemilik, $hash)->assertCreated();

        $this->gabung($tamu, $hash)->assertNoContent();

        $anggota = RoomMember::where('room_id', self::ROOM)->where('user_id', $tamu->id)->firstOrFail();
        $this->assertFalse($anggota->is_owner);
        $this->assertSame($tamu->id, RoomInvite::first()->used_by_user_id);
    }

    public function test_an_invite_is_single_use(): void
    {
        $pemilik = $this->syncUser();
        $pertama = $this->syncUser();
        $kedua = $this->syncUser();
        $this->buatRuang($pemilik);
        $hash = $this->hash();
        $this->terbitkan($pemilik, $hash)->assertCreated();
        $this->gabung($pertama, $hash)->assertNoContent();

        $this->gabung($kedua, $hash)->assertForbidden()->assertJsonPath('code', 'invite_invalid');
        $this->assertSame(0, RoomMember::where('user_id', $kedua->id)->count());
    }

    public function test_an_expired_invite_is_rejected_with_its_own_code(): void
    {
        $pemilik = $this->syncUser();
        $tamu = $this->syncUser();
        $this->buatRuang($pemilik);
        $hash = $this->hash();
        $this->terbitkan($pemilik, $hash)->assertCreated();
        RoomInvite::query()->update(['expires_at' => now()->subSecond()]);

        $this->gabung($tamu, $hash)->assertForbidden()->assertJsonPath('code', 'invite_expired');
        $this->assertSame(0, RoomMember::where('user_id', $tamu->id)->count());
        $this->assertNull(RoomInvite::first()->used_by_user_id);
    }

    public function test_an_invite_for_another_room_does_not_work_here(): void
    {
        $pemilikA = $this->syncUser();
        $pemilikB = $this->syncUser();
        $tamu = $this->syncUser();
        $this->buatRuang($pemilikA, 'ruang-a');
        $this->buatRuang($pemilikB, 'ruang-b');
        $hash = $this->hash();
        $this->terbitkan($pemilikA, $hash, 'ruang-a')->assertCreated();

        $this->gabung($tamu, $hash, 'ruang-b')->assertForbidden()->assertJsonPath('code', 'invite_invalid');
    }

    public function test_a_full_room_rejects_even_a_valid_invite_and_keeps_it_unused(): void
    {
        $pemilik = $this->syncUser();
        $this->buatRuang($pemilik);
        for ($i = 0; $i < 4; $i++) {
            $this->gabung($this->syncUser(), $this->undanganSah($pemilik))->assertNoContent();
        }
        $hash = $this->hash();
        $this->terbitkan($pemilik, $hash)->assertCreated();
        $tamu = $this->syncUser();

        $this->gabung($tamu, $hash)->assertStatus(422)->assertJsonPath('code', 'room_full');
        $this->assertNull(RoomInvite::where('invite_hash', $hash)->first()->used_by_user_id);
    }

    public function test_an_existing_member_rejoining_is_idempotent_and_does_not_need_or_consume_an_invite(): void
    {
        $pemilik = $this->syncUser();
        $tamu = $this->syncUser();
        $this->buatRuang($pemilik);
        $this->gabung($tamu, $this->undanganSah($pemilik))->assertNoContent();
        $sisa = $this->hash();
        $this->terbitkan($pemilik, $sisa)->assertCreated();

        $this->gabung($tamu, null)->assertNoContent();
        $this->gabung($tamu, $sisa)->assertNoContent();

        $this->assertNull(RoomInvite::where('invite_hash', $sisa)->first()->used_by_user_id);
        $this->assertSame(2, RoomMember::where('room_id', self::ROOM)->count());
    }

    public function test_the_first_joiner_creates_the_room_as_owner_without_an_invite(): void
    {
        $pembuat = $this->syncUser();

        $this->gabung($pembuat, null)->assertNoContent();

        $this->assertTrue(RoomMember::where('user_id', $pembuat->id)->firstOrFail()->is_owner);
    }

    // ---- bekas anggota

    public function test_a_removed_member_cannot_come_back_by_joining_again_but_can_with_a_new_invite(): void
    {
        $pemilik = $this->syncUser();
        $anggota = $this->syncUser();
        $this->buatRuang($pemilik);
        $lama = $this->hash();
        $this->terbitkan($pemilik, $lama)->assertCreated();
        $this->gabung($anggota, $lama)->assertNoContent();

        $this->withHeaders($this->authHeader($pemilik))->deleteJson('/api/rooms/'.self::ROOM.'/members/'.$anggota->id)->assertNoContent();

        // Aplikasi bekas anggota memanggil `join` lagi dengan QR lamanya: undangan sekali pakai itu sudah habis.
        $this->gabung($anggota, $lama)->assertForbidden()->assertJsonPath('code', 'invite_invalid');
        $this->gabung($anggota, null)->assertForbidden()->assertJsonPath('code', 'invite_required');
        $this->assertSame(0, RoomMember::where('user_id', $anggota->id)->count());

        // QR baru dari pemilik cukup (keputusan pemilik 2026-11-02, butir b).
        $baru = $this->hash();
        $this->terbitkan($pemilik, $baru)->assertCreated();
        $this->gabung($anggota, $baru)->assertNoContent();
        $this->assertFalse(RoomMember::where('user_id', $anggota->id)->firstOrFail()->is_owner);
    }

    public function test_a_removed_member_cannot_push_back_into_the_room(): void
    {
        $pemilik = $this->syncUser();
        $anggota = $this->syncUser();
        $this->buatRuang($pemilik);
        $this->gabung($anggota, $this->undanganSah($pemilik))->assertNoContent();
        $this->withHeaders($this->authHeader($pemilik))->deleteJson('/api/rooms/'.self::ROOM.'/members/'.$anggota->id)->assertNoContent();

        $this->withHeaders($this->authHeader($anggota))->postJson('/api/sync/push', [
            'entries' => [['room_id' => self::ROOM, 'record_type' => 'r', 'record_id' => 'x', 'device_id' => 'hp-1', 'payload' => 'a', 'changed_at' => '2026-11-03T10:00:00Z']],
        ])->assertForbidden()->assertJsonPath('code', 'not_room_member');

        $this->assertSame(0, RoomMember::where('user_id', $anggota->id)->count());
    }

    // ---- daftar anggota

    public function test_members_report_which_invite_each_member_used(): void
    {
        $pemilik = $this->syncUser();
        $tamu = $this->syncUser();
        $this->buatRuang($pemilik);
        $hash = $this->hash();
        $this->terbitkan($pemilik, $hash)->assertCreated();
        $this->gabung($tamu, $hash)->assertNoContent();

        $daftar = collect($this->withHeaders($this->authHeader($pemilik))->getJson('/api/rooms/'.self::ROOM.'/members')
            ->assertOk()->json('members'));

        $this->assertNull($daftar->firstWhere('user_id', $pemilik->id)['invite_hash']);
        $this->assertSame($hash, $daftar->firstWhere('user_id', $tamu->id)['invite_hash']);
    }

    public function test_deleting_the_account_of_an_invite_user_does_not_revive_the_invite(): void
    {
        $pemilik = $this->syncUser();
        $tamu = $this->syncUser();
        $this->buatRuang($pemilik);
        $hash = $this->hash();
        $this->terbitkan($pemilik, $hash)->assertCreated();
        $this->gabung($tamu, $hash)->assertNoContent();

        $tamu->delete();

        // Baris undangan ikut hilang (cascade), jadi QR itu tidak sah lagi, bukan "belum terpakai".
        $this->assertSame(0, RoomInvite::where('invite_hash', $hash)->count());
        $this->gabung($this->syncUser(), $hash)->assertForbidden()->assertJsonPath('code', 'invite_invalid');
    }

    /** Daftarkan satu undangan baru atas nama pemilik dan kembalikan hash-nya. */
    private function undanganSah(User $pemilik): string
    {
        $hash = $this->hash();
        $this->terbitkan($pemilik, $hash)->assertCreated();

        return $hash;
    }
}
