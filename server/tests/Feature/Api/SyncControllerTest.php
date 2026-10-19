<?php

namespace Tests\Feature\Api;

use App\Models\SyncLogEntry;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Tests\TestCase;

/** Log sinkronisasi append-only (Tahap 9, server-sync.md "Model konflik sinkronisasi"). */
class SyncControllerTest extends TestCase
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

    public function test_pushing_an_entry_stores_it_and_returns_its_id(): void
    {
        $user = $this->syncUser();

        $response = $this->withHeaders($this->authHeader($user))->postJson('/api/sync/push', [
            'entries' => [[
                'room_id' => 'ruang-keluarga-1',
                'record_type' => 'transaction',
                'record_id' => 'tx-1',
                'device_id' => 'pixel-1',
                'payload' => base64_encode('ciphertext-buram'),
                'deleted' => false,
                'changed_at' => '2026-10-19T10:00:00Z',
            ]],
        ]);

        $response->assertOk();
        $id = $response->json('ids.0');
        $this->assertSame($id, $response->json('cursor'));

        $entry = SyncLogEntry::find($id);
        $this->assertSame($user->id, $entry->user_id);
        $this->assertSame('ruang-keluarga-1', $entry->room_id);
        $this->assertSame(base64_encode('ciphertext-buram'), $entry->payload);
        $this->assertFalse($entry->deleted);
    }

    public function test_pushing_several_entries_in_one_batch(): void
    {
        $user = $this->syncUser();

        $response = $this->withHeaders($this->authHeader($user))->postJson('/api/sync/push', [
            'entries' => [
                ['record_type' => 'transaction', 'record_id' => 'tx-1', 'device_id' => 'pixel-1', 'payload' => 'a', 'changed_at' => '2026-10-19T10:00:00Z'],
                ['record_type' => 'transaction', 'record_id' => 'tx-2', 'device_id' => 'pixel-1', 'payload' => 'b', 'changed_at' => '2026-10-19T10:00:01Z'],
            ],
        ]);

        $response->assertOk();
        $this->assertCount(2, $response->json('ids'));
        $this->assertSame(2, SyncLogEntry::count());
    }

    public function test_deletion_is_a_new_tombstone_row_not_a_removal(): void
    {
        $user = $this->syncUser();
        $headers = $this->authHeader($user);

        $this->withHeaders($headers)->postJson('/api/sync/push', [
            'entries' => [['record_type' => 'transaction', 'record_id' => 'tx-1', 'device_id' => 'pixel-1', 'payload' => 'a', 'changed_at' => '2026-10-19T10:00:00Z']],
        ]);
        $this->withHeaders($headers)->postJson('/api/sync/push', [
            'entries' => [['record_type' => 'transaction', 'record_id' => 'tx-1', 'device_id' => 'pixel-1', 'payload' => 'a', 'deleted' => true, 'changed_at' => '2026-10-19T10:05:00Z']],
        ]);

        $this->assertSame(2, SyncLogEntry::count());
        $this->assertSame(1, SyncLogEntry::where('deleted', true)->count());
    }

    public function test_pulling_returns_entries_after_the_given_cursor_in_order(): void
    {
        $user = $this->syncUser();
        $headers = $this->authHeader($user);
        $firstId = $this->withHeaders($headers)->postJson('/api/sync/push', [
            'entries' => [['record_type' => 'transaction', 'record_id' => 'tx-1', 'device_id' => 'pixel-1', 'payload' => 'a', 'changed_at' => '2026-10-19T10:00:00Z']],
        ])->json('ids.0');
        $secondId = $this->withHeaders($headers)->postJson('/api/sync/push', [
            'entries' => [['record_type' => 'transaction', 'record_id' => 'tx-2', 'device_id' => 'pixel-2', 'payload' => 'b', 'changed_at' => '2026-10-19T10:01:00Z']],
        ])->json('ids.0');

        $response = $this->withHeaders($headers)->getJson("/api/sync/pull?after={$firstId}");

        $response->assertOk();
        $this->assertCount(1, $response->json('entries'));
        $this->assertSame($secondId, $response->json('entries.0.id'));
        $this->assertSame($secondId, $response->json('next_after'));
        $this->assertFalse($response->json('has_more'));
    }

    public function test_pulling_from_zero_on_a_new_device_returns_everything(): void
    {
        $user = $this->syncUser();
        $headers = $this->authHeader($user);
        $this->withHeaders($headers)->postJson('/api/sync/push', [
            'entries' => [['record_type' => 'transaction', 'record_id' => 'tx-1', 'device_id' => 'pixel-1', 'payload' => 'a', 'changed_at' => '2026-10-19T10:00:00Z']],
        ]);
        $this->withHeaders($headers)->postJson('/api/sync/push', [
            'entries' => [['record_type' => 'transaction', 'record_id' => 'tx-2', 'device_id' => 'pixel-1', 'payload' => 'b', 'changed_at' => '2026-10-19T10:01:00Z']],
        ]);

        $response = $this->withHeaders($headers)->getJson('/api/sync/pull');

        $response->assertOk();
        $this->assertCount(2, $response->json('entries'));
    }

    public function test_pull_pagination_reports_has_more_until_exhausted(): void
    {
        $user = $this->syncUser();
        $headers = $this->authHeader($user);
        foreach (range(1, 3) as $i) {
            $this->withHeaders($headers)->postJson('/api/sync/push', [
                'entries' => [['record_type' => 'transaction', 'record_id' => "tx-{$i}", 'device_id' => 'pixel-1', 'payload' => 'a', 'changed_at' => '2026-10-19T10:00:00Z']],
            ]);
        }

        $firstPage = $this->withHeaders($headers)->getJson('/api/sync/pull?limit=2');
        $firstPage->assertOk();
        $this->assertCount(2, $firstPage->json('entries'));
        $this->assertTrue($firstPage->json('has_more'));

        $secondPage = $this->withHeaders($headers)->getJson('/api/sync/pull?after='.$firstPage->json('next_after').'&limit=2');
        $this->assertCount(1, $secondPage->json('entries'));
        $this->assertFalse($secondPage->json('has_more'));
    }

    public function test_pull_never_returns_another_users_entries(): void
    {
        $owner = $this->syncUser();
        $other = $this->syncUser();
        $this->withHeaders($this->authHeader($owner))->postJson('/api/sync/push', [
            'entries' => [['record_type' => 'transaction', 'record_id' => 'tx-1', 'device_id' => 'pixel-1', 'payload' => 'rahasia', 'changed_at' => '2026-10-19T10:00:00Z']],
        ]);

        // Guard 'sanctum' menyimpan user hasil resolusi request pertama (lihat RequestGuard::user()
        // di Laravel); tanpa ini, request simulasi kedua dalam satu tes bisa salah memakai user
        // yang sama dengan request pertama walau Bearer token-nya beda. Tidak relevan di produksi
        // karena tiap request HTTP sungguhan membuat AuthManager baru.
        $this->app['auth']->forgetGuards();

        $response = $this->withHeaders($this->authHeader($other))->getJson('/api/sync/pull');

        $response->assertOk()->assertJsonCount(0, 'entries');
    }

    public function test_user_without_sync_plan_is_rejected_from_push_and_pull(): void
    {
        $user = User::factory()->create(['plans' => []]);
        $headers = $this->authHeader($user);

        $this->withHeaders($headers)->postJson('/api/sync/push', [
            'entries' => [['record_type' => 'transaction', 'record_id' => 'tx-1', 'device_id' => 'pixel-1', 'payload' => 'a', 'changed_at' => '2026-10-19T10:00:00Z']],
        ])->assertForbidden();

        $this->withHeaders($headers)->getJson('/api/sync/pull')->assertForbidden();
    }

    public function test_push_requires_authentication(): void
    {
        $this->postJson('/api/sync/push', ['entries' => []])->assertUnauthorized();
    }

    public function test_push_rejects_empty_batch_and_missing_fields(): void
    {
        $user = $this->syncUser();
        $headers = $this->authHeader($user);

        $this->withHeaders($headers)->postJson('/api/sync/push', ['entries' => []])
            ->assertUnprocessable()->assertJsonValidationErrors('entries');

        $this->withHeaders($headers)->postJson('/api/sync/push', ['entries' => [['record_type' => 'transaction']]])
            ->assertUnprocessable()
            ->assertJsonValidationErrors(['entries.0.record_id', 'entries.0.device_id', 'entries.0.payload', 'entries.0.changed_at']);
    }

    public function test_room_id_is_optional_for_records_not_tied_to_a_room(): void
    {
        $user = $this->syncUser();

        $this->withHeaders($this->authHeader($user))->postJson('/api/sync/push', [
            'entries' => [['record_type' => 'account', 'record_id' => 'acc-1', 'device_id' => 'pixel-1', 'payload' => 'a', 'changed_at' => '2026-10-19T10:00:00Z']],
        ])->assertOk();

        $this->assertNull(SyncLogEntry::sole()->room_id);
    }
}
