<?php

namespace Tests\Feature\Api;

use App\Models\RoomMember;
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

    public function test_pushing_to_a_room_registers_membership_automatically(): void
    {
        $user = $this->syncUser();

        $this->withHeaders($this->authHeader($user))->postJson('/api/sync/push', [
            'entries' => [['room_id' => 'ruang-keluarga-1', 'record_type' => 'transaction', 'record_id' => 'tx-1', 'device_id' => 'pixel-1', 'payload' => 'a', 'changed_at' => '2026-10-19T10:00:00Z']],
        ])->assertOk();

        $this->assertSame(1, RoomMember::where('user_id', $user->id)->where('room_id', 'ruang-keluarga-1')->count());
    }

    public function test_pull_includes_entries_from_a_shared_room_pushed_by_another_member(): void
    {
        $owner = $this->syncUser();
        $partner = $this->syncUser();

        $this->withHeaders($this->authHeader($owner))->postJson('/api/sync/push', [
            'entries' => [['room_id' => 'ruang-keluarga-1', 'record_type' => 'transaction', 'record_id' => 'tx-1', 'device_id' => 'pixel-1', 'payload' => 'dari-owner', 'changed_at' => '2026-10-19T10:00:00Z']],
        ])->assertOk();

        $this->app['auth']->forgetGuards();
        $this->withHeaders($this->authHeader($partner))->postJson('/api/rooms/ruang-keluarga-1/join')->assertNoContent();

        $this->app['auth']->forgetGuards();
        $response = $this->withHeaders($this->authHeader($partner))->getJson('/api/sync/pull');

        $response->assertOk()->assertJsonCount(1, 'entries');
        $this->assertSame('dari-owner', $response->json('entries.0.payload'));
    }

    public function test_pull_does_not_leak_a_members_private_room_to_other_members(): void
    {
        $owner = $this->syncUser();
        $partner = $this->syncUser();

        // Ruang pribadi (room_id null) milik owner — tidak ada hubungannya dengan ruang keluarga.
        $this->withHeaders($this->authHeader($owner))->postJson('/api/sync/push', [
            'entries' => [
                ['room_id' => null, 'record_type' => 'account', 'record_id' => 'acc-private', 'device_id' => 'pixel-1', 'payload' => 'rahasia', 'changed_at' => '2026-10-19T10:00:00Z'],
                ['room_id' => 'ruang-keluarga-1', 'record_type' => 'transaction', 'record_id' => 'tx-1', 'device_id' => 'pixel-1', 'payload' => 'dari-owner', 'changed_at' => '2026-10-19T10:00:01Z'],
            ],
        ])->assertOk();

        $this->app['auth']->forgetGuards();
        $this->withHeaders($this->authHeader($partner))->postJson('/api/rooms/ruang-keluarga-1/join')->assertNoContent();

        $this->app['auth']->forgetGuards();
        $response = $this->withHeaders($this->authHeader($partner))->getJson('/api/sync/pull');

        $response->assertOk()->assertJsonCount(1, 'entries');
        $this->assertSame('tx-1', $response->json('entries.0.record_id'));
    }

    // ------------------------------------------------------------------ Tahap 2 sync-desain.md

    public function test_push_is_idempotent_when_the_same_entry_is_resent_after_a_lost_ack(): void
    {
        $user = $this->syncUser();
        $headers = $this->authHeader($user);
        $entry = ['record_type' => 'transaction', 'record_id' => 'tx-1', 'device_id' => 'pixel-1', 'payload' => 'a', 'deleted' => false, 'changed_at' => '2026-10-19T10:00:00Z', 'changed_at_ms' => 1792404000000];

        $first = $this->withHeaders($headers)->postJson('/api/sync/push', ['entries' => [$entry]]);
        $second = $this->withHeaders($headers)->postJson('/api/sync/push', ['entries' => [$entry]]);

        $first->assertOk();
        $second->assertOk();
        $this->assertSame($first->json('ids.0'), $second->json('ids.0'), 'baris yang sama, bukan duplikat baru');
        $this->assertSame(1, SyncLogEntry::count());
    }

    public function test_push_stores_changed_at_ms_and_derives_it_when_the_client_omits_it(): void
    {
        $user = $this->syncUser();

        $this->withHeaders($this->authHeader($user))->postJson('/api/sync/push', [
            'entries' => [['record_type' => 'transaction', 'record_id' => 'tx-1', 'device_id' => 'pixel-1', 'payload' => 'a', 'changed_at' => '2026-10-19T10:00:00Z']],
        ])->assertOk();

        $this->assertSame(1792404000000, SyncLogEntry::sole()->changed_at_ms);
    }

    public function test_push_response_includes_server_time_ms(): void
    {
        $user = $this->syncUser();

        $response = $this->withHeaders($this->authHeader($user))->postJson('/api/sync/push', [
            'entries' => [['record_type' => 'transaction', 'record_id' => 'tx-1', 'device_id' => 'pixel-1', 'payload' => 'a', 'changed_at' => '2026-10-19T10:00:00Z']],
        ]);

        $response->assertOk();
        $this->assertIsInt($response->json('server_time_ms'));
        $this->assertGreaterThan(0, $response->json('server_time_ms'));
    }

    public function test_pull_response_includes_server_time_ms_changed_at_ms_and_an_opaque_author(): void
    {
        $user = $this->syncUser();
        $this->withHeaders($this->authHeader($user))->postJson('/api/sync/push', [
            'entries' => [['record_type' => 'transaction', 'record_id' => 'tx-1', 'device_id' => 'pixel-1', 'payload' => 'a', 'changed_at' => '2026-10-19T10:00:00Z']],
        ])->assertOk();

        $response = $this->withHeaders($this->authHeader($user))->getJson('/api/sync/pull');

        $response->assertOk();
        $this->assertIsInt($response->json('server_time_ms'));
        $this->assertSame(1792404000000, $response->json('entries.0.changed_at_ms'));
        $author = $response->json('entries.0.author');
        $this->assertIsString($author);
        $this->assertNotSame((string) $user->id, $author, 'author tidak boleh membocorkan user_id mentah');
    }

    public function test_a_member_other_than_the_original_author_cannot_overwrite_a_shared_room_row(): void
    {
        $owner = $this->syncUser();
        $partner = $this->syncUser();

        $this->withHeaders($this->authHeader($owner))->postJson('/api/sync/push', [
            'entries' => [['room_id' => 'ruang-keluarga-1', 'record_type' => 'transaction', 'record_id' => 'tx-1', 'device_id' => 'pixel-owner', 'payload' => 'dari-owner', 'changed_at' => '2026-10-19T10:00:00Z']],
        ])->assertOk();

        $this->app['auth']->forgetGuards();
        $this->withHeaders($this->authHeader($partner))->postJson('/api/rooms/ruang-keluarga-1/join')->assertNoContent();

        // Partner mencoba "menang" lewat id yang sama dengan cap waktu lebih baru — server menolak
        // baris ini walau isi payload dan device_id-nya bisa saja dipalsukan (kunci ruang sama untuk semua
        // anggota), karena hanya PENULIS PERTAMA baris ini yang boleh mengubahnya (bagian B R3).
        $this->app['auth']->forgetGuards();
        $response = $this->withHeaders($this->authHeader($partner))->postJson('/api/sync/push', [
            'entries' => [['room_id' => 'ruang-keluarga-1', 'record_type' => 'transaction', 'record_id' => 'tx-1', 'device_id' => 'pixel-partner', 'payload' => 'palsu-dari-partner', 'changed_at' => '2026-10-19T11:00:00Z']],
        ]);

        $response->assertOk();
        $this->assertSame([], $response->json('ids'), 'baris ditolak, bukan ditulis sebagai versi baru');
        $this->assertSame(1, SyncLogEntry::where('record_id', 'tx-1')->count());
        $this->assertSame('dari-owner', SyncLogEntry::where('record_id', 'tx-1')->sole()->payload);
    }

    public function test_push_rejecting_every_entry_still_returns_a_sensible_cursor(): void
    {
        $owner = $this->syncUser();
        $partner = $this->syncUser();
        $this->withHeaders($this->authHeader($owner))->postJson('/api/sync/push', [
            'entries' => [['room_id' => 'ruang-keluarga-1', 'record_type' => 'transaction', 'record_id' => 'tx-1', 'device_id' => 'pixel-owner', 'payload' => 'dari-owner', 'changed_at' => '2026-10-19T10:00:00Z']],
        ])->assertOk();
        $this->app['auth']->forgetGuards();
        $this->withHeaders($this->authHeader($partner))->postJson('/api/rooms/ruang-keluarga-1/join')->assertNoContent();
        $this->app['auth']->forgetGuards();
        $this->withHeaders($this->authHeader($partner))->postJson('/api/sync/push', [
            'entries' => [['room_id' => 'ruang-keluarga-1', 'record_type' => 'transaction', 'record_id' => 'tx-own', 'device_id' => 'pixel-partner', 'payload' => 'punya partner', 'changed_at' => '2026-10-19T10:00:01Z']],
        ])->assertOk();

        $this->app['auth']->forgetGuards();
        $response = $this->withHeaders($this->authHeader($partner))->postJson('/api/sync/push', [
            'entries' => [['room_id' => 'ruang-keluarga-1', 'record_type' => 'transaction', 'record_id' => 'tx-1', 'device_id' => 'pixel-partner', 'payload' => 'palsu', 'changed_at' => '2026-10-19T12:00:00Z']],
        ]);

        $response->assertOk();
        $this->assertSame([], $response->json('ids'));
        $this->assertIsInt($response->json('cursor'));
    }

    public function test_pull_with_device_id_records_its_cursor_in_sync_devices(): void
    {
        $user = $this->syncUser();
        $this->withHeaders($this->authHeader($user))->postJson('/api/sync/push', [
            'entries' => [['record_type' => 'transaction', 'record_id' => 'tx-1', 'device_id' => 'pixel-1', 'payload' => 'a', 'changed_at' => '2026-10-19T10:00:00Z']],
        ])->assertOk();

        $response = $this->withHeaders($this->authHeader($user))->getJson('/api/sync/pull?device_id=pixel-1');

        $response->assertOk();
        $this->assertDatabaseHas('sync_devices', [
            'user_id' => $user->id,
            'device_id' => 'pixel-1',
            'after_id' => $response->json('next_after'),
        ]);
    }

    public function test_pull_without_device_id_does_not_write_to_sync_devices(): void
    {
        $user = $this->syncUser();

        $this->withHeaders($this->authHeader($user))->getJson('/api/sync/pull')->assertOk();

        $this->assertDatabaseCount('sync_devices', 0);
    }
}
