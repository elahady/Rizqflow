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
            ->assertForbidden();

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
