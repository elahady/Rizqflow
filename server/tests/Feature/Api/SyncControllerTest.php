<?php

namespace Tests\Feature\Api;

use App\Models\RoomMember;
use App\Models\SyncLogEntry;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use PHPUnit\Framework\Attributes\DataProvider;
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

        $response = $this->withHeaders($headers)->getJson("/api/sync/pull?device_id=pixel-uji&after={$firstId}");

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

        $response = $this->withHeaders($headers)->getJson('/api/sync/pull?device_id=pixel-uji');

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

        $firstPage = $this->withHeaders($headers)->getJson('/api/sync/pull?device_id=pixel-uji&limit=2');
        $firstPage->assertOk();
        $this->assertCount(2, $firstPage->json('entries'));
        $this->assertTrue($firstPage->json('has_more'));

        $secondPage = $this->withHeaders($headers)->getJson('/api/sync/pull?device_id=pixel-uji&after='.$firstPage->json('next_after').'&limit=2');
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

        $response = $this->withHeaders($this->authHeader($other))->getJson('/api/sync/pull?device_id=pixel-uji');

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

    // ---- gerbang langganan keluarga (keputusan pemilik I-8): Sync PEMILIK ruang, bukan pengirim/penarik sendiri

    public function test_push_to_an_existing_shared_room_is_allowed_without_the_senders_own_sync_if_the_owner_has_sync(): void
    {
        $owner = $this->syncUser();
        $this->withHeaders($this->authHeader($owner))->postJson('/api/sync/push', [
            'entries' => [['room_id' => 'ruang-keluarga-1', 'record_type' => 'transaction', 'record_id' => 'tx-owner', 'device_id' => 'pixel-1', 'payload' => 'a', 'changed_at' => '2026-10-19T10:00:00Z']],
        ])->assertOk();

        $this->app['auth']->forgetGuards();
        $noSync = User::factory()->create(['plans' => []]);
        $this->withHeaders($this->authHeader($noSync))->postJson('/api/rooms/ruang-keluarga-1/join', $this->undangan('ruang-keluarga-1'))->assertNoContent();

        $this->app['auth']->forgetGuards();
        $this->withHeaders($this->authHeader($noSync))->postJson('/api/sync/push', [
            'entries' => [['room_id' => 'ruang-keluarga-1', 'record_type' => 'transaction', 'record_id' => 'tx-nosync', 'device_id' => 'pixel-2', 'payload' => 'b', 'changed_at' => '2026-10-19T11:00:00Z']],
        ])->assertOk();

        $this->assertSame(1, RoomMember::where('user_id', $noSync->id)->where('room_id', 'ruang-keluarga-1')->count());
    }

    public function test_push_to_a_room_that_already_has_members_does_not_register_a_non_member(): void
    {
        $owner = $this->syncUser();
        $this->withHeaders($this->authHeader($owner))->postJson('/api/sync/push', [
            'entries' => [['room_id' => 'ruang-keluarga-1', 'record_type' => 'transaction', 'record_id' => 'tx-owner', 'device_id' => 'pixel-1', 'payload' => 'a', 'changed_at' => '2026-10-19T10:00:00Z']],
        ])->assertOk();

        // Pengirim ber-Sync sendiri, tetapi tidak pernah diundang: push tidak lagi mendaftarkannya (bekas
        // anggota yang dikeluarkan tidak boleh tercatat lagi lewat jalur ini).
        $this->app['auth']->forgetGuards();
        $asing = $this->syncUser();
        $this->withHeaders($this->authHeader($asing))->postJson('/api/sync/push', [
            'entries' => [['room_id' => 'ruang-keluarga-1', 'record_type' => 'transaction', 'record_id' => 'tx-asing', 'device_id' => 'pixel-9', 'payload' => 'x', 'changed_at' => '2026-10-19T11:00:00Z']],
        ])->assertForbidden()->assertJsonPath('code', 'not_room_member');

        $this->assertSame(0, RoomMember::where('user_id', $asing->id)->count());
        $this->assertSame(0, SyncLogEntry::where('record_id', 'tx-asing')->count());
    }

    public function test_push_to_a_brand_new_room_still_requires_the_senders_own_sync(): void
    {
        $noSync = User::factory()->create(['plans' => []]);

        $this->withHeaders($this->authHeader($noSync))->postJson('/api/sync/push', [
            'entries' => [['room_id' => 'ruang-benar-benar-baru', 'record_type' => 'transaction', 'record_id' => 'tx-1', 'device_id' => 'pixel-1', 'payload' => 'a', 'changed_at' => '2026-10-19T10:00:00Z']],
        ])->assertForbidden()->assertJsonPath('code', 'sync_required');

        $this->assertSame(0, RoomMember::where('room_id', 'ruang-benar-benar-baru')->count());
    }

    public function test_pull_scoped_to_a_shared_room_is_allowed_without_the_callers_own_sync_if_the_owner_has_sync(): void
    {
        $owner = $this->syncUser();
        $this->withHeaders($this->authHeader($owner))->postJson('/api/sync/push', [
            'entries' => [['room_id' => 'ruang-keluarga-1', 'record_type' => 'transaction', 'record_id' => 'tx-owner', 'device_id' => 'pixel-1', 'payload' => 'dari-owner', 'changed_at' => '2026-10-19T10:00:00Z']],
        ])->assertOk();

        $this->app['auth']->forgetGuards();
        $noSync = User::factory()->create(['plans' => []]);
        $this->withHeaders($this->authHeader($noSync))->postJson('/api/rooms/ruang-keluarga-1/join', $this->undangan('ruang-keluarga-1'))->assertNoContent();

        $this->app['auth']->forgetGuards();
        $response = $this->withHeaders($this->authHeader($noSync))->getJson('/api/sync/pull?scope=r&room_id=ruang-keluarga-1&device_id=pixel-2');

        $response->assertOk()->assertJsonCount(1, 'entries');
        $this->assertSame('dari-owner', $response->json('entries.0.payload'));
    }

    public function test_pull_scope_a_still_requires_the_callers_own_sync_even_as_a_member_of_a_shared_room(): void
    {
        $owner = $this->syncUser();
        $this->withHeaders($this->authHeader($owner))->postJson('/api/sync/push', [
            'entries' => [['room_id' => 'ruang-keluarga-1', 'record_type' => 'transaction', 'record_id' => 'tx-owner', 'device_id' => 'pixel-1', 'payload' => 'a', 'changed_at' => '2026-10-19T10:00:00Z']],
        ])->assertOk();

        $this->app['auth']->forgetGuards();
        $noSync = User::factory()->create(['plans' => []]);
        $this->withHeaders($this->authHeader($noSync))->postJson('/api/rooms/ruang-keluarga-1/join', $this->undangan('ruang-keluarga-1'))->assertNoContent();

        $this->app['auth']->forgetGuards();
        $this->withHeaders($this->authHeader($noSync))->getJson('/api/sync/pull?scope=a&device_id=pixel-2')
            ->assertForbidden()->assertJsonPath('code', 'sync_required');
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
        $this->withHeaders($this->authHeader($partner))->postJson('/api/rooms/ruang-keluarga-1/join', $this->undangan('ruang-keluarga-1'))->assertNoContent();

        $this->app['auth']->forgetGuards();
        $response = $this->withHeaders($this->authHeader($partner))->getJson('/api/sync/pull?device_id=pixel-uji');

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
        $this->withHeaders($this->authHeader($partner))->postJson('/api/rooms/ruang-keluarga-1/join', $this->undangan('ruang-keluarga-1'))->assertNoContent();

        $this->app['auth']->forgetGuards();
        $response = $this->withHeaders($this->authHeader($partner))->getJson('/api/sync/pull?device_id=pixel-uji');

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

        $response = $this->withHeaders($this->authHeader($user))->getJson('/api/sync/pull?device_id=pixel-uji');

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
        $this->withHeaders($this->authHeader($partner))->postJson('/api/rooms/ruang-keluarga-1/join', $this->undangan('ruang-keluarga-1'))->assertNoContent();

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
        $this->withHeaders($this->authHeader($partner))->postJson('/api/rooms/ruang-keluarga-1/join', $this->undangan('ruang-keluarga-1'))->assertNoContent();
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

    /**
     * device_id wajib (Tahap 9): perangkat yang tidak tercatat di sync_devices tidak ikut menahan kompaksi
     * tombstone, jadi pull tanpanya ditolak 422 dengan `code` yang jelas, tanpa mengirim baris apa pun.
     */
    #[DataProvider('deviceIdKosong')]
    public function test_pull_without_device_id_is_rejected_with_clear_code(string $query): void
    {
        $user = $this->syncUser();
        $this->withHeaders($this->authHeader($user))->postJson('/api/sync/push', [
            'entries' => [['record_type' => 'transaction', 'record_id' => 'tx-1', 'device_id' => 'pixel-1', 'payload' => 'a', 'changed_at' => '2026-10-19T10:00:00Z']],
        ])->assertOk();

        $response = $this->withHeaders($this->authHeader($user))->getJson('/api/sync/pull'.$query);

        $response->assertStatus(422)
            ->assertJsonPath('code', 'device_id_required')
            ->assertJsonValidationErrors(['device_id'])
            ->assertJsonMissingPath('entries');
        $this->assertDatabaseCount('sync_devices', 0);
    }

    public static function deviceIdKosong(): array
    {
        return [
            'tanpa parameter' => [''],
            'kosong' => ['?device_id='],
            'spasi' => ['?device_id=%20%20'],
            'array' => ['?device_id[]=pixel-1'],
        ];
    }

    public function test_pull_without_device_id_and_without_sync_plan_still_answers_403_first(): void
    {
        $user = User::factory()->create(['plans' => []]);

        $this->withHeaders($this->authHeader($user))->getJson('/api/sync/pull')->assertForbidden();
    }

    public function test_pull_rejects_overlong_device_id(): void
    {
        $user = $this->syncUser();

        $this->withHeaders($this->authHeader($user))
            ->getJson('/api/sync/pull?device_id='.str_repeat('a', 101))
            ->assertStatus(422)
            ->assertJsonValidationErrors(['device_id']);
    }
}
