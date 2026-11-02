<?php

namespace Tests\Feature\Api;

use App\Models\RoomMember;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\DB;
use Tests\TestCase;

/** Keanggotaan ruang yang dibagi (Tahap 9, roadmap.md "Ruang keluarga bersama"). */
class RoomMembershipControllerTest extends TestCase
{
    use RefreshDatabase;

    private function syncUser(): User
    {
        return User::factory()->create(['plans' => ['sync']]);
    }

    private function authHeader(User $user): array
    {
        return ['Authorization' => 'Bearer '.$user->createToken('test')->plainTextToken];
    }

    public function test_joining_a_room_creates_a_membership_row(): void
    {
        $user = $this->syncUser();

        $this->withHeaders($this->authHeader($user))->postJson('/api/rooms/ruang-keluarga-1/join')
            ->assertNoContent();

        $this->assertSame(1, RoomMember::where('user_id', $user->id)->where('room_id', 'ruang-keluarga-1')->count());
    }

    public function test_joining_the_same_room_twice_is_idempotent(): void
    {
        $user = $this->syncUser();
        $headers = $this->authHeader($user);

        $this->withHeaders($headers)->postJson('/api/rooms/ruang-keluarga-1/join')->assertNoContent();
        $this->withHeaders($headers)->postJson('/api/rooms/ruang-keluarga-1/join')->assertNoContent();

        $this->assertSame(1, RoomMember::where('user_id', $user->id)->where('room_id', 'ruang-keluarga-1')->count());
    }

    public function test_members_lists_everyone_in_the_room_and_marks_you(): void
    {
        $owner = $this->syncUser();
        $partner = $this->syncUser();
        $this->withHeaders($this->authHeader($owner))->postJson('/api/rooms/ruang-keluarga-1/join')->assertNoContent();

        $this->app['auth']->forgetGuards();
        $this->withHeaders($this->authHeader($partner))->postJson('/api/rooms/ruang-keluarga-1/join')->assertNoContent();

        $this->app['auth']->forgetGuards();
        $response = $this->withHeaders($this->authHeader($owner))->getJson('/api/rooms/ruang-keluarga-1/members');

        $response->assertOk()->assertJsonCount(2, 'members');
        $this->assertTrue(collect($response->json('members'))->firstWhere('email', $owner->email)['you']);
        $this->assertFalse(collect($response->json('members'))->firstWhere('email', $partner->email)['you']);
    }

    public function test_members_rejects_a_non_member(): void
    {
        $owner = $this->syncUser();
        $stranger = $this->syncUser();
        $this->withHeaders($this->authHeader($owner))->postJson('/api/rooms/ruang-keluarga-1/join')->assertNoContent();

        $this->app['auth']->forgetGuards();
        $this->withHeaders($this->authHeader($stranger))->getJson('/api/rooms/ruang-keluarga-1/members')
            ->assertForbidden();
    }

    public function test_join_and_members_require_sync_plan(): void
    {
        $user = User::factory()->create(['plans' => []]);
        $headers = $this->authHeader($user);

        $this->withHeaders($headers)->postJson('/api/rooms/ruang-keluarga-1/join')->assertForbidden();
        $this->withHeaders($headers)->getJson('/api/rooms/ruang-keluarga-1/members')->assertForbidden();
    }

    public function test_join_requires_authentication(): void
    {
        $this->postJson('/api/rooms/ruang-keluarga-1/join')->assertUnauthorized();
    }

    public function test_a_sixth_distinct_joiner_is_rejected_as_room_full(): void
    {
        $owner = $this->syncUser();
        $this->withHeaders($this->authHeader($owner))->postJson('/api/rooms/ruang-keluarga-1/join')->assertNoContent();
        for ($i = 0; $i < 3; $i++) {
            $this->app['auth']->forgetGuards();
            $this->withHeaders($this->authHeader($this->syncUser()))->postJson('/api/rooms/ruang-keluarga-1/join')->assertNoContent();
        }
        // Lima anggota (owner + 4) sudah di ruang itu; monetisasi.md: maksimal 5 per ruang.
        $this->app['auth']->forgetGuards();
        $this->withHeaders($this->authHeader($this->syncUser()))->postJson('/api/rooms/ruang-keluarga-1/join')->assertNoContent();
        $this->assertSame(5, RoomMember::where('room_id', 'ruang-keluarga-1')->count());

        $this->app['auth']->forgetGuards();
        $this->withHeaders($this->authHeader($this->syncUser()))->postJson('/api/rooms/ruang-keluarga-1/join')
            ->assertStatus(422)->assertJsonPath('code', 'room_full');
        $this->assertSame(5, RoomMember::where('room_id', 'ruang-keluarga-1')->count());
    }

    public function test_rejoining_a_full_room_as_an_existing_member_still_works(): void
    {
        $owner = $this->syncUser();
        $members = [];
        $this->withHeaders($this->authHeader($owner))->postJson('/api/rooms/ruang-keluarga-1/join')->assertNoContent();
        for ($i = 0; $i < 4; $i++) {
            $members[] = $member = $this->syncUser();
            $this->app['auth']->forgetGuards();
            $this->withHeaders($this->authHeader($member))->postJson('/api/rooms/ruang-keluarga-1/join')->assertNoContent();
        }

        $this->app['auth']->forgetGuards();
        $this->withHeaders($this->authHeader($members[0]))->postJson('/api/rooms/ruang-keluarga-1/join')->assertNoContent();
        $this->assertSame(5, RoomMember::where('room_id', 'ruang-keluarga-1')->count());
    }

    public function test_the_room_full_cap_does_not_affect_other_rooms(): void
    {
        $owner = $this->syncUser();
        $this->withHeaders($this->authHeader($owner))->postJson('/api/rooms/ruang-penuh/join')->assertNoContent();
        for ($i = 0; $i < 4; $i++) {
            $this->app['auth']->forgetGuards();
            $this->withHeaders($this->authHeader($this->syncUser()))->postJson('/api/rooms/ruang-penuh/join')->assertNoContent();
        }

        $this->app['auth']->forgetGuards();
        $this->withHeaders($this->authHeader($this->syncUser()))->postJson('/api/rooms/ruang-lain/join')->assertNoContent();
    }

    public function test_first_joiner_becomes_owner_and_later_joiners_do_not(): void
    {
        $owner = $this->syncUser();
        $partner = $this->syncUser();

        $this->withHeaders($this->authHeader($owner))->postJson('/api/rooms/ruang-keluarga-1/join')->assertNoContent();
        $this->app['auth']->forgetGuards();
        $this->withHeaders($this->authHeader($partner))->postJson('/api/rooms/ruang-keluarga-1/join')->assertNoContent();

        $this->assertTrue(RoomMember::where('user_id', $owner->id)->where('room_id', 'ruang-keluarga-1')->value('is_owner'));
        $this->assertFalse(RoomMember::where('user_id', $partner->id)->where('room_id', 'ruang-keluarga-1')->value('is_owner'));
    }

    public function test_rejoining_does_not_change_ownership(): void
    {
        $owner = $this->syncUser();
        $partner = $this->syncUser();
        $this->withHeaders($this->authHeader($owner))->postJson('/api/rooms/ruang-keluarga-1/join')->assertNoContent();
        $this->app['auth']->forgetGuards();
        $this->withHeaders($this->authHeader($partner))->postJson('/api/rooms/ruang-keluarga-1/join')->assertNoContent();
        $this->app['auth']->forgetGuards();
        $this->withHeaders($this->authHeader($owner))->postJson('/api/rooms/ruang-keluarga-1/join')->assertNoContent();

        $this->assertSame(1, RoomMember::where('room_id', 'ruang-keluarga-1')->where('is_owner', true)->count());
        $this->assertTrue(RoomMember::where('user_id', $owner->id)->where('room_id', 'ruang-keluarga-1')->value('is_owner'));
    }

    public function test_members_marks_the_owner(): void
    {
        $owner = $this->syncUser();
        $partner = $this->syncUser();
        $this->withHeaders($this->authHeader($owner))->postJson('/api/rooms/ruang-keluarga-1/join')->assertNoContent();
        $this->app['auth']->forgetGuards();
        $this->withHeaders($this->authHeader($partner))->postJson('/api/rooms/ruang-keluarga-1/join')->assertNoContent();

        $this->app['auth']->forgetGuards();
        $response = $this->withHeaders($this->authHeader($partner))->getJson('/api/rooms/ruang-keluarga-1/members');

        $response->assertOk();
        $this->assertTrue(collect($response->json('members'))->firstWhere('email', $owner->email)['owner']);
        $this->assertFalse(collect($response->json('members'))->firstWhere('email', $partner->email)['owner']);
    }

    public function test_members_includes_member_id_matching_the_sync_author_and_a_role(): void
    {
        [$owner, $partner] = $this->roomWithOwnerAndMember();
        $this->pushRow($partner, 'tx-anggota');
        $pulled = $this->withHeaders($this->authHeader($owner))->getJson('/api/sync/pull?device_id=dev-pull')->assertOk();
        $this->app['auth']->forgetGuards();
        $author = collect($pulled->json('entries'))->firstWhere('record_id', 'tx-anggota')['author'];

        $members = collect($this->withHeaders($this->authHeader($owner))
            ->getJson('/api/rooms/'.self::ROOM.'/members')->assertOk()->json('members'));
        $this->assertSame($author, $members->firstWhere('email', $partner->email)['member_id']);
        $this->assertSame('owner', $members->firstWhere('email', $owner->email)['role']);
        $this->assertSame('member', $members->firstWhere('email', $partner->email)['role']);
    }

    public function test_members_includes_the_user_id_that_the_remove_endpoint_expects(): void
    {
        $owner = $this->syncUser();
        $partner = $this->syncUser();
        $this->withHeaders($this->authHeader($owner))->postJson('/api/rooms/ruang-keluarga-1/join')->assertNoContent();
        $this->app['auth']->forgetGuards();
        $this->withHeaders($this->authHeader($partner))->postJson('/api/rooms/ruang-keluarga-1/join')->assertNoContent();

        $this->app['auth']->forgetGuards();
        $members = collect($this->withHeaders($this->authHeader($owner))
            ->getJson('/api/rooms/ruang-keluarga-1/members')
            ->assertOk()
            ->json('members'));
        $partnerId = $members->firstWhere('email', $partner->email)['user_id'];
        $this->assertSame($partner->id, $partnerId);
        $this->assertSame($owner->id, $members->firstWhere('email', $owner->email)['user_id']);

        // Id yang dibaca dari daftar langsung bisa dipakai mengeluarkan anggota itu.
        $this->app['auth']->forgetGuards();
        $this->withHeaders($this->authHeader($owner))
            ->deleteJson("/api/rooms/ruang-keluarga-1/members/{$partnerId}")
            ->assertNoContent();
        $this->assertDatabaseMissing('room_members', ['user_id' => $partner->id, 'room_id' => 'ruang-keluarga-1']);
    }

    public function test_owner_can_remove_a_member(): void
    {
        $owner = $this->syncUser();
        $partner = $this->syncUser();
        $this->withHeaders($this->authHeader($owner))->postJson('/api/rooms/ruang-keluarga-1/join')->assertNoContent();
        $this->app['auth']->forgetGuards();
        $this->withHeaders($this->authHeader($partner))->postJson('/api/rooms/ruang-keluarga-1/join')->assertNoContent();

        $this->app['auth']->forgetGuards();
        $this->withHeaders($this->authHeader($owner))
            ->deleteJson("/api/rooms/ruang-keluarga-1/members/{$partner->id}")
            ->assertNoContent();

        $this->assertDatabaseMissing('room_members', ['user_id' => $partner->id, 'room_id' => 'ruang-keluarga-1']);
        $this->assertDatabaseHas('room_members', ['user_id' => $owner->id, 'room_id' => 'ruang-keluarga-1', 'is_owner' => true]);
    }

    public function test_non_owner_cannot_remove_a_member(): void
    {
        $owner = $this->syncUser();
        $partner = $this->syncUser();
        $third = $this->syncUser();
        $this->withHeaders($this->authHeader($owner))->postJson('/api/rooms/ruang-keluarga-1/join')->assertNoContent();
        $this->app['auth']->forgetGuards();
        $this->withHeaders($this->authHeader($partner))->postJson('/api/rooms/ruang-keluarga-1/join')->assertNoContent();
        $this->app['auth']->forgetGuards();
        $this->withHeaders($this->authHeader($third))->postJson('/api/rooms/ruang-keluarga-1/join')->assertNoContent();

        $this->app['auth']->forgetGuards();
        $this->withHeaders($this->authHeader($partner))
            ->deleteJson("/api/rooms/ruang-keluarga-1/members/{$third->id}")
            ->assertForbidden()
            ->assertJsonPath('code', 'not_room_owner');

        $this->assertDatabaseHas('room_members', ['user_id' => $third->id, 'room_id' => 'ruang-keluarga-1']);
    }

    public function test_non_member_cannot_remove_a_member(): void
    {
        $owner = $this->syncUser();
        $partner = $this->syncUser();
        $stranger = $this->syncUser();
        $this->withHeaders($this->authHeader($owner))->postJson('/api/rooms/ruang-keluarga-1/join')->assertNoContent();
        $this->app['auth']->forgetGuards();
        $this->withHeaders($this->authHeader($partner))->postJson('/api/rooms/ruang-keluarga-1/join')->assertNoContent();

        $this->app['auth']->forgetGuards();
        $this->withHeaders($this->authHeader($stranger))
            ->deleteJson("/api/rooms/ruang-keluarga-1/members/{$partner->id}")
            ->assertForbidden();

        $this->assertDatabaseHas('room_members', ['user_id' => $partner->id, 'room_id' => 'ruang-keluarga-1']);
    }

    public function test_owner_cannot_remove_themself(): void
    {
        $owner = $this->syncUser();
        $this->withHeaders($this->authHeader($owner))->postJson('/api/rooms/ruang-keluarga-1/join')->assertNoContent();

        $this->app['auth']->forgetGuards();
        $this->withHeaders($this->authHeader($owner))
            ->deleteJson("/api/rooms/ruang-keluarga-1/members/{$owner->id}")
            ->assertUnprocessable();

        $this->assertDatabaseHas('room_members', ['user_id' => $owner->id, 'room_id' => 'ruang-keluarga-1', 'is_owner' => true]);
    }

    public function test_removing_a_user_who_is_not_a_member_returns_not_found(): void
    {
        $owner = $this->syncUser();
        $stranger = $this->syncUser();
        $this->withHeaders($this->authHeader($owner))->postJson('/api/rooms/ruang-keluarga-1/join')->assertNoContent();

        $this->app['auth']->forgetGuards();
        $this->withHeaders($this->authHeader($owner))
            ->deleteJson("/api/rooms/ruang-keluarga-1/members/{$stranger->id}")
            ->assertNotFound();
    }

    public function test_remove_member_requires_sync_plan(): void
    {
        $user = User::factory()->create(['plans' => []]);

        $this->withHeaders($this->authHeader($user))
            ->deleteJson("/api/rooms/ruang-keluarga-1/members/{$user->id}")
            ->assertForbidden();
    }

    // ---- keluar sendiri (leave) dan kode galat

    private const ROOM = 'ruang-keluarga-1';

    /** Pemilik dan satu anggota biasa sudah bergabung ke [self::ROOM]; mengembalikan [pemilik, anggota]. */
    private function roomWithOwnerAndMember(): array
    {
        $owner = $this->syncUser();
        $member = $this->syncUser();
        $this->withHeaders($this->authHeader($owner))->postJson('/api/rooms/'.self::ROOM.'/join')->assertNoContent();
        $this->app['auth']->forgetGuards();
        $this->withHeaders($this->authHeader($member))->postJson('/api/rooms/'.self::ROOM.'/join')->assertNoContent();
        $this->app['auth']->forgetGuards();

        return [$owner, $member];
    }

    private function pushRow(User $user, string $recordId): void
    {
        $this->withHeaders($this->authHeader($user))->postJson('/api/sync/push', ['entries' => [[
            'room_id' => self::ROOM, 'record_type' => 'r', 'record_id' => $recordId, 'device_id' => 'dev-'.$user->id,
            'payload' => 'cipher-'.$recordId, 'changed_at' => '2026-11-01T10:00:00Z', 'changed_at_ms' => 1793527200000,
        ]]])->assertOk();
        $this->app['auth']->forgetGuards();
    }

    /** @return list<string> record_id yang diterima $user dari pull. */
    private function pulledRecordIds(User $user): array
    {
        $response = $this->withHeaders($this->authHeader($user))->getJson('/api/sync/pull?device_id=dev-pull')->assertOk();
        $this->app['auth']->forgetGuards();

        return array_column($response->json('entries'), 'record_id');
    }

    public function test_a_member_can_leave_and_stops_receiving_the_room_while_the_rows_stay_for_the_others(): void
    {
        [$owner, $member] = $this->roomWithOwnerAndMember();
        $this->pushRow($owner, 'tx-pemilik');
        $this->assertSame(['tx-pemilik'], $this->pulledRecordIds($member), 'kontrol: anggota menerima baris ruang');

        $this->withHeaders($this->authHeader($member))->postJson('/api/rooms/'.self::ROOM.'/leave')->assertNoContent();
        $this->app['auth']->forgetGuards();

        $this->assertDatabaseMissing('room_members', ['user_id' => $member->id, 'room_id' => self::ROOM]);
        $this->assertDatabaseHas('room_members', ['user_id' => $owner->id, 'room_id' => self::ROOM, 'is_owner' => true]);
        $this->assertSame([], $this->pulledRecordIds($member), 'bekas anggota tidak lagi menerima baris ruang');
        $this->assertSame(['tx-pemilik'], $this->pulledRecordIds($owner), 'baris tetap ada untuk anggota lain');
    }

    public function test_rows_written_by_the_member_who_left_stay_on_the_server(): void
    {
        [$owner, $member] = $this->roomWithOwnerAndMember();
        $this->pushRow($member, 'tx-anggota');

        $this->withHeaders($this->authHeader($member))->postJson('/api/rooms/'.self::ROOM.'/leave')->assertNoContent();
        $this->app['auth']->forgetGuards();

        $this->assertSame(['tx-anggota'], $this->pulledRecordIds($owner));
    }

    public function test_the_owner_cannot_leave(): void
    {
        [$owner] = $this->roomWithOwnerAndMember();

        $this->withHeaders($this->authHeader($owner))->postJson('/api/rooms/'.self::ROOM.'/leave')
            ->assertStatus(422)
            ->assertJsonPath('code', 'owner_cannot_leave')
            ->assertJsonStructure(['message', 'code']);

        $this->assertDatabaseHas('room_members', ['user_id' => $owner->id, 'room_id' => self::ROOM, 'is_owner' => true]);
    }

    public function test_the_sole_owner_cannot_leave_either(): void
    {
        $owner = $this->syncUser();
        $this->withHeaders($this->authHeader($owner))->postJson('/api/rooms/'.self::ROOM.'/join')->assertNoContent();
        $this->app['auth']->forgetGuards();

        $this->withHeaders($this->authHeader($owner))->postJson('/api/rooms/'.self::ROOM.'/leave')
            ->assertStatus(422)->assertJsonPath('code', 'owner_cannot_leave');
    }

    public function test_leaving_a_room_you_are_not_in_is_404_with_a_code_and_leaving_twice_too(): void
    {
        [, $member] = $this->roomWithOwnerAndMember();
        $stranger = $this->syncUser();

        $this->withHeaders($this->authHeader($stranger))->postJson('/api/rooms/'.self::ROOM.'/leave')
            ->assertNotFound()->assertJsonPath('code', 'not_room_member');
        $this->app['auth']->forgetGuards();

        $this->withHeaders($this->authHeader($member))->postJson('/api/rooms/'.self::ROOM.'/leave')->assertNoContent();
        $this->app['auth']->forgetGuards();
        $this->withHeaders($this->authHeader($member))->postJson('/api/rooms/'.self::ROOM.'/leave')
            ->assertNotFound()->assertJsonPath('code', 'not_room_member');

        $this->assertDatabaseHas('room_members', ['room_id' => self::ROOM, 'is_owner' => true]);
    }

    public function test_leaving_does_not_touch_other_rooms_or_other_members(): void
    {
        [$owner, $member] = $this->roomWithOwnerAndMember();
        $third = $this->syncUser();
        $this->withHeaders($this->authHeader($third))->postJson('/api/rooms/'.self::ROOM.'/join')->assertNoContent();
        $this->app['auth']->forgetGuards();
        $this->withHeaders($this->authHeader($member))->postJson('/api/rooms/ruang-lain/join')->assertNoContent();
        $this->app['auth']->forgetGuards();

        $this->withHeaders($this->authHeader($member))->postJson('/api/rooms/'.self::ROOM.'/leave')->assertNoContent();

        $this->assertDatabaseHas('room_members', ['user_id' => $member->id, 'room_id' => 'ruang-lain']);
        $this->assertDatabaseHas('room_members', ['user_id' => $third->id, 'room_id' => self::ROOM]);
        $this->assertDatabaseHas('room_members', ['user_id' => $owner->id, 'room_id' => self::ROOM]);
    }

    public function test_leaving_does_not_require_an_active_sync_subscription(): void
    {
        [, $member] = $this->roomWithOwnerAndMember();
        $member->forceFill(['plans' => []])->save();

        $this->withHeaders($this->authHeader($member))->postJson('/api/rooms/'.self::ROOM.'/leave')->assertNoContent();

        $this->assertDatabaseMissing('room_members', ['user_id' => $member->id, 'room_id' => self::ROOM]);
    }

    public function test_leave_requires_authentication(): void
    {
        $this->postJson('/api/rooms/'.self::ROOM.'/leave')->assertUnauthorized()->assertJsonPath('code', 'unauthenticated');
    }

    public function test_a_member_who_left_can_join_again_with_a_new_invitation_but_never_as_owner(): void
    {
        [$owner, $member] = $this->roomWithOwnerAndMember();
        $this->withHeaders($this->authHeader($member))->postJson('/api/rooms/'.self::ROOM.'/leave')->assertNoContent();
        $this->app['auth']->forgetGuards();

        $this->withHeaders($this->authHeader($member))->postJson('/api/rooms/'.self::ROOM.'/join')->assertNoContent();

        $this->assertFalse(RoomMember::where('user_id', $member->id)->where('room_id', self::ROOM)->value('is_owner'));
        $this->assertTrue(RoomMember::where('user_id', $owner->id)->where('room_id', self::ROOM)->value('is_owner'));
    }

    // ---- gerbang langganan keluarga (keputusan pemilik I-8, peran-anggota.md): Sync PEMILIK ruang, bukan peminta sendiri

    public function test_a_member_without_their_own_sync_can_join_an_existing_room_whose_owner_has_sync(): void
    {
        $owner = $this->syncUser();
        $this->withHeaders($this->authHeader($owner))->postJson('/api/rooms/'.self::ROOM.'/join')->assertNoContent();

        $this->app['auth']->forgetGuards();
        $noSync = User::factory()->create(['plans' => []]);
        $this->withHeaders($this->authHeader($noSync))->postJson('/api/rooms/'.self::ROOM.'/join')->assertNoContent();

        $this->assertDatabaseHas('room_members', ['user_id' => $noSync->id, 'room_id' => self::ROOM, 'is_owner' => false]);
    }

    public function test_a_member_without_their_own_sync_can_list_members_of_a_room_whose_owner_has_sync(): void
    {
        $owner = $this->syncUser();
        $noSync = User::factory()->create(['plans' => []]);
        $this->withHeaders($this->authHeader($owner))->postJson('/api/rooms/'.self::ROOM.'/join')->assertNoContent();
        $this->app['auth']->forgetGuards();
        // join sendiri bukti baris sebelumnya; di sini pemilik mendaftarkan noSync lewat join juga (dua baris independen).
        $this->withHeaders($this->authHeader($noSync))->postJson('/api/rooms/'.self::ROOM.'/join')->assertNoContent();

        $this->app['auth']->forgetGuards();
        $this->withHeaders($this->authHeader($noSync))->getJson('/api/rooms/'.self::ROOM.'/members')
            ->assertOk()->assertJsonCount(2, 'members');
    }

    public function test_joining_a_brand_new_room_still_requires_the_joiners_own_sync(): void
    {
        // Ruang belum punya anggota sama sekali: belum ada "pemilik" untuk dibaca Sync-nya, peminta akan jadi
        // pemilik begitu baris ini tercatat, jadi Sync-nya sendiri yang dicek (Entitlements::allowsSharedRoom).
        $noSync = User::factory()->create(['plans' => []]);

        $this->withHeaders($this->authHeader($noSync))->postJson('/api/rooms/ruang-benar-benar-baru/join')
            ->assertForbidden()->assertJsonPath('code', 'sync_required');
        $this->assertDatabaseMissing('room_members', ['user_id' => $noSync->id, 'room_id' => 'ruang-benar-benar-baru']);
    }

    public function test_access_is_denied_again_once_the_owners_sync_lapses(): void
    {
        $owner = $this->syncUser();
        $this->withHeaders($this->authHeader($owner))->postJson('/api/rooms/'.self::ROOM.'/join')->assertNoContent();
        // Paket pemilik turun SETELAH ruang dibuat: anggota baru yang belum pernah punya Sync sendiri kini ditolak.
        $owner->forceFill(['plans' => []])->save();

        $this->app['auth']->forgetGuards();
        $noSync = User::factory()->create(['plans' => []]);
        $this->withHeaders($this->authHeader($noSync))->postJson('/api/rooms/'.self::ROOM.'/join')
            ->assertForbidden()->assertJsonPath('code', 'sync_required');
        $this->assertDatabaseMissing('room_members', ['user_id' => $noSync->id, 'room_id' => self::ROOM]);
    }

    public function test_error_responses_carry_stable_codes(): void
    {
        [$owner, $member] = $this->roomWithOwnerAndMember();
        $stranger = $this->syncUser();
        $noSync = User::factory()->create(['plans' => []]);

        // Keputusan pemilik I-8: akses dibuka oleh Sync PEMILIK ruang, bukan Sync peminta sendiri. self::ROOM sudah
        // punya pemilik ber-Sync (roomWithOwnerAndMember), jadi $noSync BOLEH join ruang itu (lihat butir tersendiri
        // di bawah); sync_required di sini harus diuji di ruang BARU yang belum punya pemilik sama sekali.
        $this->withHeaders($this->authHeader($noSync))->postJson('/api/rooms/ruang-baru-tanpa-pemilik/join')
            ->assertForbidden()->assertJsonPath('code', 'sync_required');
        $this->app['auth']->forgetGuards();
        $this->withHeaders($this->authHeader($stranger))->getJson('/api/rooms/'.self::ROOM.'/members')
            ->assertForbidden()->assertJsonPath('code', 'not_room_member');
        $this->app['auth']->forgetGuards();
        $this->withHeaders($this->authHeader($owner))->deleteJson('/api/rooms/'.self::ROOM.'/members/'.$stranger->id)
            ->assertNotFound()->assertJsonPath('code', 'member_not_found');
        $this->app['auth']->forgetGuards();
        $this->withHeaders($this->authHeader($owner))->deleteJson('/api/rooms/'.self::ROOM.'/members/'.$owner->id)
            ->assertStatus(422)->assertJsonPath('code', 'owner_cannot_be_removed');
        $this->app['auth']->forgetGuards();
        $this->withHeaders($this->authHeader($member))->deleteJson('/api/rooms/'.self::ROOM.'/members/'.$owner->id)
            ->assertForbidden()->assertJsonPath('code', 'not_room_owner');
    }

    public function test_a_room_id_longer_than_the_sync_limit_is_rejected_before_touching_the_database(): void
    {
        $user = $this->syncUser();
        $headers = $this->authHeader($user);
        $tooLong = str_repeat('r', 101);

        foreach (["/api/rooms/$tooLong/join", "/api/rooms/$tooLong/leave"] as $uri) {
            $this->app['auth']->forgetGuards();
            $this->withHeaders($headers)->postJson($uri)->assertStatus(422)->assertJsonPath('code', 'invalid_room_id');
        }
        $this->app['auth']->forgetGuards();
        $this->withHeaders($headers)->getJson("/api/rooms/$tooLong/members")->assertStatus(422)->assertJsonPath('code', 'invalid_room_id');
        $this->app['auth']->forgetGuards();
        $this->withHeaders($headers)->deleteJson("/api/rooms/$tooLong/members/{$user->id}")->assertStatus(422)->assertJsonPath('code', 'invalid_room_id');

        $this->assertSame(0, RoomMember::count());

        // Tepat 100 karakter (batas push) tetap diterima.
        $this->app['auth']->forgetGuards();
        $this->withHeaders($headers)->postJson('/api/rooms/'.str_repeat('r', 100).'/join')->assertNoContent();
    }

    public function test_a_non_numeric_member_id_in_the_remove_route_is_a_plain_404(): void
    {
        [$owner] = $this->roomWithOwnerAndMember();

        $this->withHeaders($this->authHeader($owner))->deleteJson('/api/rooms/'.self::ROOM.'/members/bukan-angka')
            ->assertNotFound()->assertJsonPath('code', 'not_found');
    }

    // ---- pindah kepemilikan (make-owner)

    private function makeOwnerUri(int $userId, string $roomId = self::ROOM): string
    {
        return "/api/rooms/{$roomId}/members/{$userId}/make-owner";
    }

    public function test_owner_can_transfer_ownership_to_a_member_and_both_stay_members(): void
    {
        [$owner, $member] = $this->roomWithOwnerAndMember();
        $this->pushRow($owner, 'tx-pemilik');
        $syncRowsBefore = DB::table('sync_log_entries')->count();

        $this->withHeaders($this->authHeader($owner))->postJson($this->makeOwnerUri($member->id))
            ->assertNoContent();
        $this->app['auth']->forgetGuards();

        $this->assertDatabaseHas('room_members', ['user_id' => $member->id, 'room_id' => self::ROOM, 'is_owner' => true]);
        $this->assertDatabaseHas('room_members', ['user_id' => $owner->id, 'room_id' => self::ROOM, 'is_owner' => false]);
        $this->assertSame(1, RoomMember::where('room_id', self::ROOM)->where('is_owner', true)->count());
        $this->assertSame(2, RoomMember::where('room_id', self::ROOM)->count(), 'tidak ada yang dikeluarkan');
        $this->assertSame($syncRowsBefore, DB::table('sync_log_entries')->count(), 'baris sync tidak disentuh');
        $this->assertSame(['tx-pemilik'], $this->pulledRecordIds($member), 'pemilik baru tetap menerima baris ruang');
        $this->assertSame(['tx-pemilik'], $this->pulledRecordIds($owner), 'pemilik lama tetap menerima baris ruang');
    }

    public function test_the_new_owner_is_reported_as_owner_in_members(): void
    {
        [$owner, $member] = $this->roomWithOwnerAndMember();
        $this->withHeaders($this->authHeader($owner))->postJson($this->makeOwnerUri($member->id))->assertNoContent();
        $this->app['auth']->forgetGuards();

        $members = collect($this->withHeaders($this->authHeader($owner))
            ->getJson('/api/rooms/'.self::ROOM.'/members')->assertOk()->json('members'));

        $this->assertTrue($members->firstWhere('user_id', $member->id)['owner']);
        $this->assertFalse($members->firstWhere('user_id', $owner->id)['owner']);
    }

    public function test_the_old_owner_can_leave_after_transferring_ownership(): void
    {
        [$owner, $member] = $this->roomWithOwnerAndMember();
        $this->withHeaders($this->authHeader($owner))->postJson('/api/rooms/'.self::ROOM.'/leave')
            ->assertStatus(422)->assertJsonPath('code', 'owner_cannot_leave');
        $this->app['auth']->forgetGuards();

        $this->withHeaders($this->authHeader($owner))->postJson($this->makeOwnerUri($member->id))->assertNoContent();
        $this->app['auth']->forgetGuards();

        $this->withHeaders($this->authHeader($owner))->postJson('/api/rooms/'.self::ROOM.'/leave')->assertNoContent();
        $this->app['auth']->forgetGuards();

        $this->assertDatabaseMissing('room_members', ['user_id' => $owner->id, 'room_id' => self::ROOM]);
        $this->assertDatabaseHas('room_members', ['user_id' => $member->id, 'room_id' => self::ROOM, 'is_owner' => true]);
        $this->assertSame([], $this->pulledRecordIds($owner), 'bekas pemilik tidak lagi menerima baris ruang');
    }

    public function test_the_new_owner_can_remove_members_after_the_transfer(): void
    {
        [$owner, $member] = $this->roomWithOwnerAndMember();
        $third = $this->syncUser();
        $this->withHeaders($this->authHeader($third))->postJson('/api/rooms/'.self::ROOM.'/join')->assertNoContent();
        $this->app['auth']->forgetGuards();
        $this->withHeaders($this->authHeader($owner))->postJson($this->makeOwnerUri($member->id))->assertNoContent();
        $this->app['auth']->forgetGuards();

        $this->withHeaders($this->authHeader($member))->deleteJson('/api/rooms/'.self::ROOM.'/members/'.$third->id)
            ->assertNoContent();
        $this->app['auth']->forgetGuards();

        $this->assertDatabaseMissing('room_members', ['user_id' => $third->id, 'room_id' => self::ROOM]);
    }

    public function test_a_non_owner_cannot_transfer_ownership(): void
    {
        [$owner, $member] = $this->roomWithOwnerAndMember();
        $third = $this->syncUser();
        $this->withHeaders($this->authHeader($third))->postJson('/api/rooms/'.self::ROOM.'/join')->assertNoContent();
        $this->app['auth']->forgetGuards();

        $this->withHeaders($this->authHeader($member))->postJson($this->makeOwnerUri($third->id))
            ->assertForbidden()->assertJsonPath('code', 'not_room_owner');
        $this->app['auth']->forgetGuards();

        $this->assertDatabaseHas('room_members', ['user_id' => $owner->id, 'room_id' => self::ROOM, 'is_owner' => true]);
        $this->assertDatabaseHas('room_members', ['user_id' => $member->id, 'room_id' => self::ROOM, 'is_owner' => false]);
    }

    public function test_a_non_member_cannot_transfer_ownership(): void
    {
        [$owner] = $this->roomWithOwnerAndMember();
        $stranger = $this->syncUser();

        $this->withHeaders($this->authHeader($stranger))->postJson($this->makeOwnerUri($owner->id))
            ->assertForbidden()->assertJsonPath('code', 'not_room_owner');
        $this->app['auth']->forgetGuards();

        $this->assertDatabaseHas('room_members', ['user_id' => $owner->id, 'room_id' => self::ROOM, 'is_owner' => true]);
    }

    public function test_transferring_to_someone_who_is_not_a_member_is_not_found(): void
    {
        [$owner] = $this->roomWithOwnerAndMember();
        $stranger = $this->syncUser();

        $this->withHeaders($this->authHeader($owner))->postJson($this->makeOwnerUri($stranger->id))
            ->assertNotFound()->assertJsonPath('code', 'member_not_found');
        $this->app['auth']->forgetGuards();

        $this->assertDatabaseHas('room_members', ['user_id' => $owner->id, 'room_id' => self::ROOM, 'is_owner' => true]);
        $this->assertDatabaseMissing('room_members', ['user_id' => $stranger->id, 'room_id' => self::ROOM]);
    }

    public function test_transferring_to_the_current_owner_is_rejected_as_already_owner(): void
    {
        [$owner, $member] = $this->roomWithOwnerAndMember();

        $this->withHeaders($this->authHeader($owner))->postJson($this->makeOwnerUri($owner->id))
            ->assertStatus(422)->assertJsonPath('code', 'already_owner');
        $this->app['auth']->forgetGuards();

        $this->assertDatabaseHas('room_members', ['user_id' => $owner->id, 'room_id' => self::ROOM, 'is_owner' => true]);
        $this->assertDatabaseHas('room_members', ['user_id' => $member->id, 'room_id' => self::ROOM, 'is_owner' => false]);
    }

    public function test_transfer_does_not_touch_other_rooms(): void
    {
        [$owner, $member] = $this->roomWithOwnerAndMember();
        $this->withHeaders($this->authHeader($owner))->postJson('/api/rooms/ruang-lain/join')->assertNoContent();
        $this->app['auth']->forgetGuards();
        $this->withHeaders($this->authHeader($member))->postJson('/api/rooms/ruang-lain/join')->assertNoContent();
        $this->app['auth']->forgetGuards();

        $this->withHeaders($this->authHeader($owner))->postJson($this->makeOwnerUri($member->id))->assertNoContent();

        $this->assertDatabaseHas('room_members', ['user_id' => $owner->id, 'room_id' => 'ruang-lain', 'is_owner' => true]);
        $this->assertDatabaseHas('room_members', ['user_id' => $member->id, 'room_id' => 'ruang-lain', 'is_owner' => false]);
    }

    public function test_transfer_requires_an_active_sync_subscription(): void
    {
        [$owner, $member] = $this->roomWithOwnerAndMember();
        $owner->forceFill(['plans' => []])->save();

        $this->withHeaders($this->authHeader($owner))->postJson($this->makeOwnerUri($member->id))
            ->assertForbidden()->assertJsonPath('code', 'sync_required');
        $this->app['auth']->forgetGuards();

        $this->assertDatabaseHas('room_members', ['user_id' => $owner->id, 'room_id' => self::ROOM, 'is_owner' => true]);
    }

    public function test_transfer_requires_authentication(): void
    {
        $target = $this->syncUser();

        $this->postJson($this->makeOwnerUri($target->id))->assertUnauthorized();
    }

    public function test_a_non_numeric_target_in_the_make_owner_route_is_a_plain_404(): void
    {
        [$owner] = $this->roomWithOwnerAndMember();

        $this->withHeaders($this->authHeader($owner))->postJson('/api/rooms/'.self::ROOM.'/members/bukan-angka/make-owner')
            ->assertNotFound()->assertJsonPath('code', 'not_found');
    }

    public function test_backfill_marks_the_earliest_member_of_each_room_as_owner(): void
    {
        $first = $this->syncUser();
        $second = $this->syncUser();
        $otherRoomOnly = $this->syncUser();

        // Simulasikan data sebelum kolom is_owner ada: lepas kolom, isi baris lama, lalu jalankan
        // ulang migrasi (up = tambah kolom + backfill).
        $migration = require database_path('migrations/2026_10_31_000001_add_is_owner_to_room_members_table.php');
        $migration->down();

        DB::table('room_members')->insert([
            ['user_id' => $first->id, 'room_id' => 'ruang-a', 'created_at' => now()],
            ['user_id' => $second->id, 'room_id' => 'ruang-a', 'created_at' => now()],
            ['user_id' => $otherRoomOnly->id, 'room_id' => 'ruang-b', 'created_at' => now()],
        ]);

        $migration->up();

        $this->assertTrue(RoomMember::where('user_id', $first->id)->where('room_id', 'ruang-a')->value('is_owner'));
        $this->assertFalse(RoomMember::where('user_id', $second->id)->where('room_id', 'ruang-a')->value('is_owner'));
        $this->assertTrue(RoomMember::where('user_id', $otherRoomOnly->id)->where('room_id', 'ruang-b')->value('is_owner'));
    }
}
