<?php

namespace Tests\Feature\Api;

use App\Models\RoomMember;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
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
}
