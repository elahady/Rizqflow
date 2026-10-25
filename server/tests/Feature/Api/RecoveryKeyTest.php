<?php

namespace Tests\Feature\Api;

use App\Models\RecoveryKey;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Log\Events\MessageLogged;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Event;
use Illuminate\Support\Facades\Log;
use Illuminate\Support\Facades\Schema;
use Illuminate\Testing\TestResponse;
use PHPUnit\Framework\Attributes\DataProvider;
use Tests\Support\UsesApiTokens;
use Tests\TestCase;

/**
 * Salinan kunci akun terbungkus kode pemulihan (Tahap 13): PUT/GET/DELETE /api/account/recovery-key.
 * Server hanya menyimpan blob buram dan mengembalikannya persis; hanya PUT yang butuh langganan Sync.
 * Hari "sekarang" dipatok supaya perubahan updated_at bisa diuji.
 */
class RecoveryKeyTest extends TestCase
{
    use RefreshDatabase, UsesApiTokens;

    private const URL = '/api/account/recovery-key';

    /** Penanda ASCII di awal blob supaya kebocoran ke log/pesan galat (mentah atau base64) terdeteksi. */
    private const MARKER = 'RAHASIA-BLOB-JANGAN-BOCOR';

    protected function setUp(): void
    {
        parent::setUp();
        $this->travelTo('2026-10-28 08:00:00');
    }

    // --- pembantu ---------------------------------------------------------------------------------

    /** Blob buram acak (bukan UTF-8 sah) sepanjang $length byte, diawali penanda bila diminta. */
    private function blob(int $length = 80, bool $marked = false): string
    {
        $prefix = $marked ? self::MARKER : '';

        return $prefix.random_bytes($length - strlen($prefix));
    }

    private function putKey(string $token, string $bytes, mixed $version = 1): TestResponse
    {
        return $this->api('PUT', self::URL, $token, ['wrapped_key' => base64_encode($bytes), 'version' => $version]);
    }

    private function storedBytes(User $user): ?string
    {
        // Baca langsung dari tabel (tanpa model) supaya yang diperiksa memang isi kolomnya.
        $value = DB::table('recovery_keys')->where('user_id', $user->id)->value('wrapped_key');

        return $value === null ? null : (string) $value;
    }

    // --- PUT: membuat dan mengganti ---------------------------------------------------------------

    public function test_put_creates_the_copy_and_answers_version_and_timestamp_only(): void
    {
        $user = $this->syncUser();
        $bytes = $this->blob();

        $response = $this->putKey($this->tokenFor($user), $bytes)->assertOk();

        $this->assertSame(['updated_at', 'version'], collect(array_keys($response->json()))->sort()->values()->all());
        $response->assertJsonPath('version', 1)->assertJsonPath('updated_at', '2026-10-28T08:00:00Z');
        $this->assertSame(1, RecoveryKey::count());
        $this->assertSame($bytes, $this->storedBytes($user));
        $this->assertStringNotContainsString(base64_encode($bytes), $response->getContent());
    }

    public function test_put_replaces_the_existing_copy_keeping_one_row_per_account(): void
    {
        $user = $this->syncUser();
        $token = $this->tokenFor($user);
        $first = $this->blob(60);
        $second = $this->blob(90);

        $this->putKey($token, $first)->assertOk()->assertJsonPath('updated_at', '2026-10-28T08:00:00Z');
        $id = RecoveryKey::sole()->id;
        $this->travelTo('2026-10-28 09:30:15');
        $this->putKey($token, $second)->assertOk()->assertJsonPath('updated_at', '2026-10-28T09:30:15Z');

        $this->assertSame(1, RecoveryKey::count());
        $this->assertSame($id, RecoveryKey::sole()->id);
        $this->assertSame($second, $this->storedBytes($user));
    }

    public function test_put_without_a_sync_subscription_is_403_and_leaves_the_stored_copy_alone(): void
    {
        $user = $this->syncUser();
        $kept = $this->blob(70);
        $this->putKey($this->tokenFor($user), $kept)->assertOk();

        $user->forceFill(['plans' => ['pro']])->save(); // Pro bukan Sync
        $this->putKey($this->tokenFor($user), $this->blob(70))
            ->assertForbidden()
            ->assertExactJson(['message' => 'Butuh langganan Sync.']);

        $this->assertSame($kept, $this->storedBytes($user));
        $nothing = User::factory()->create(['plans' => null]);
        $this->putKey($this->tokenFor($nothing), $this->blob())->assertForbidden();
        $this->assertSame(0, RecoveryKey::where('user_id', $nothing->id)->count());
    }

    // --- GET: kembali persis ------------------------------------------------------------------------

    public function test_get_returns_exactly_the_bytes_that_were_put(): void
    {
        $user = $this->syncUser();
        $token = $this->tokenFor($user);
        // Semua nilai byte 0..255 (termasuk NUL dan urutan yang bukan UTF-8 sah), maju lalu mundur.
        $bytes = implode('', array_map('chr', range(0, 255))).implode('', array_map('chr', range(255, 0, -1)));
        $this->putKey($token, $bytes)->assertOk();

        $response = $this->api('GET', self::URL, $token)->assertOk();

        $this->assertSame(['updated_at', 'version', 'wrapped_key'], collect(array_keys($response->json()))->sort()->values()->all());
        $this->assertSame(base64_encode($bytes), $response->json('wrapped_key'));
        $this->assertSame($bytes, base64_decode($response->json('wrapped_key'), true));
        $response->assertJsonPath('version', 1)->assertJsonPath('updated_at', '2026-10-28T08:00:00Z');
        $this->assertStringContainsString('no-store', $response->headers->get('Cache-Control'));
    }

    public function test_get_is_404_when_there_is_no_copy(): void
    {
        $this->api('GET', self::URL, $this->tokenFor($this->syncUser()))
            ->assertNotFound()
            ->assertExactJson(['message' => 'Belum ada kode pemulihan', 'code' => 'no_recovery_key']);
    }

    public function test_404_of_an_unknown_route_does_not_carry_the_recovery_key_code(): void
    {
        $token = $this->tokenFor($this->syncUser());

        $response = $this->api('GET', self::URL.'x', $token)->assertNotFound();

        $this->assertNull($response->json('code'));
        $this->assertStringNotContainsString('no_recovery_key', $response->getContent());
    }

    public function test_get_and_delete_still_work_after_the_subscription_ended(): void
    {
        $user = $this->syncUser();
        $bytes = $this->blob();
        $token = $this->tokenFor($user);
        $this->putKey($token, $bytes)->assertOk();

        $user->forceFill(['plans' => []])->save(); // langganan berakhir

        $this->putKey($token, $this->blob())->assertForbidden();
        $this->assertSame(base64_encode($bytes), $this->api('GET', self::URL, $token)->assertOk()->json('wrapped_key'));
        $this->api('DELETE', self::URL, $token)->assertNoContent();
        $this->api('GET', self::URL, $token)->assertNotFound();
    }

    // --- DELETE --------------------------------------------------------------------------------------

    public function test_delete_removes_the_copy_and_is_idempotent(): void
    {
        $user = $this->syncUser();
        $token = $this->tokenFor($user);
        $this->putKey($token, $this->blob())->assertOk();

        $this->api('DELETE', self::URL, $token)->assertNoContent();
        $this->assertSame(0, RecoveryKey::count());
        $this->api('DELETE', self::URL, $token)->assertNoContent(); // sudah tak ada: tetap 204
        $this->api('DELETE', self::URL, $this->tokenFor($this->syncUser()))->assertNoContent(); // tak pernah punya
    }

    // --- dua akun ------------------------------------------------------------------------------------

    public function test_one_account_can_neither_read_overwrite_nor_delete_anothers_copy(): void
    {
        $alice = $this->syncUser();
        $bob = $this->syncUser();
        $aliceToken = $this->tokenFor($alice);
        $bobToken = $this->tokenFor($bob);
        $aliceBytes = $this->blob(64);
        $this->putKey($aliceToken, $aliceBytes)->assertOk();

        $this->api('GET', self::URL, $bobToken)->assertNotFound(); // Bob tak melihat milik Alice

        // Bob menyelipkan user_id/id Alice di isi permintaan: diabaikan, hanya baris Bob yang tersentuh.
        $bobBytes = $this->blob(65);
        $this->api('PUT', self::URL, $bobToken, [
            'wrapped_key' => base64_encode($bobBytes), 'version' => 1, 'user_id' => $alice->id, 'id' => RecoveryKey::sole()->id,
        ])->assertOk();
        $this->assertSame($aliceBytes, $this->storedBytes($alice));
        $this->assertSame($bobBytes, $this->storedBytes($bob));
        $this->assertSame(2, RecoveryKey::count());

        $this->api('DELETE', self::URL, $bobToken)->assertNoContent();
        $this->assertSame($aliceBytes, $this->storedBytes($alice));
        $this->assertSame(base64_encode($aliceBytes), $this->api('GET', self::URL, $aliceToken)->json('wrapped_key'));
    }

    // --- validasi -------------------------------------------------------------------------------------

    /** @return array<string, array{0: array<string, mixed>, 1: string}> */
    public static function invalidBodies(): array
    {
        $ok = base64_encode('0123456789abcdef');

        return [
            'wrapped_key hilang' => [['version' => 1], 'wrapped_key'],
            'wrapped_key kosong' => [['wrapped_key' => '', 'version' => 1], 'wrapped_key'],
            'wrapped_key null' => [['wrapped_key' => null, 'version' => 1], 'wrapped_key'],
            'wrapped_key array' => [['wrapped_key' => [$ok], 'version' => 1], 'wrapped_key'],
            'wrapped_key angka' => [['wrapped_key' => 12345678, 'version' => 1], 'wrapped_key'],
            'bukan base64' => [['wrapped_key' => '!!!bukan base64!!!', 'version' => 1], 'wrapped_key'],
            'base64 url-safe' => [['wrapped_key' => strtr(base64_encode("\xfb\xff\xfe\xfb\xff"), '+/', '-_'), 'version' => 1], 'wrapped_key'],
            'base64 dengan baris baru di tengah' => [['wrapped_key' => substr($ok, 0, 8)."\n".substr($ok, 8), 'version' => 1], 'wrapped_key'],
            'base64 dengan spasi di tengah' => [['wrapped_key' => substr($ok, 0, 8).' '.substr($ok, 8), 'version' => 1], 'wrapped_key'],
            'base64 tanpa padding' => [['wrapped_key' => rtrim(base64_encode('abcd'), '='), 'version' => 1], 'wrapped_key'],
            'base64 dengan padding di tengah' => [['wrapped_key' => 'YQ==YQ==', 'version' => 1], 'wrapped_key'],
            'version hilang' => [['wrapped_key' => $ok], 'version'],
            'version null' => [['wrapped_key' => $ok, 'version' => null], 'version'],
            'version string' => [['wrapped_key' => $ok, 'version' => '1'], 'version'],
            'version desimal' => [['wrapped_key' => $ok, 'version' => 1.5], 'version'],
            'version boolean' => [['wrapped_key' => $ok, 'version' => true], 'version'],
            'version array' => [['wrapped_key' => $ok, 'version' => [1]], 'version'],
            'version tak dikenal 2' => [['wrapped_key' => $ok, 'version' => 2], 'version'],
            'version nol' => [['wrapped_key' => $ok, 'version' => 0], 'version'],
            'version negatif' => [['wrapped_key' => $ok, 'version' => -1], 'version'],
        ];
    }

    /** @param  array<string, mixed>  $body */
    #[DataProvider('invalidBodies')]
    public function test_invalid_bodies_are_422_and_change_nothing(array $body, string $field): void
    {
        $user = $this->syncUser();
        $token = $this->tokenFor($user);
        $kept = $this->blob(50);
        $this->putKey($token, $kept)->assertOk();

        $this->api('PUT', self::URL, $token, $body)->assertStatus(422)->assertJsonValidationErrors($field);

        $this->assertSame($kept, $this->storedBytes($user)); // salinan lama tidak tergeser
    }

    public function test_an_empty_body_is_422_on_both_fields(): void
    {
        $this->api('PUT', self::URL, $this->tokenFor($this->syncUser()))
            ->assertStatus(422)->assertJsonValidationErrors(['wrapped_key', 'version']);
    }

    public function test_a_huge_string_is_rejected_by_the_length_guard_before_it_is_decoded(): void
    {
        $user = $this->syncUser();
        $huge = str_repeat('A', 2_000_000); // base64 sah, 1,5 MB terdekode

        $response = $this->api('PUT', self::URL, $this->tokenFor($user), ['wrapped_key' => $huge, 'version' => 1])
            ->assertStatus(422);

        // Pesan penjaga panjang (bail), bukan pesan pemeriksaan ukuran terdekode: string tidak didekode.
        $this->assertSame(['wrapped_key terlalu besar (maksimum 8192 byte).'], $response->json('errors.wrapped_key'));
        $this->assertSame(0, RecoveryKey::count());
    }

    public function test_exactly_8192_bytes_is_accepted_and_8193_is_rejected(): void
    {
        $user = $this->syncUser();
        $token = $this->tokenFor($user);

        $limit = $this->blob(8192);
        $this->putKey($token, $limit)->assertOk();
        $this->assertSame($limit, $this->storedBytes($user));
        $this->assertSame(base64_encode($limit), $this->api('GET', self::URL, $token)->json('wrapped_key'));

        // 8193 byte punya panjang base64 yang sama (10924) dengan 8192: harus ditangkap pemeriksaan terdekode.
        $over = $this->blob(8193);
        $this->assertSame(strlen(base64_encode($limit)), strlen(base64_encode($over)));
        $this->putKey($token, $over)->assertStatus(422)->assertJsonValidationErrors('wrapped_key');
        $this->assertSame($limit, $this->storedBytes($user));

        $this->putKey($token, 'x')->assertOk(); // 1 byte, batas bawah
        $this->assertSame('x', $this->storedBytes($user));
    }

    public function test_the_encoded_length_guard_matches_the_byte_limit(): void
    {
        $this->assertSame(4 * (int) ceil(RecoveryKey::MAX_BYTES / 3), RecoveryKey::MAX_ENCODED_LENGTH);
        $this->assertSame(RecoveryKey::MAX_ENCODED_LENGTH, strlen(base64_encode(str_repeat('a', RecoveryKey::MAX_BYTES))));
    }

    // --- autentikasi ----------------------------------------------------------------------------------

    /** @return array<string, array{0: string}> */
    public static function methods(): array
    {
        return ['PUT' => ['PUT'], 'GET' => ['GET'], 'DELETE' => ['DELETE']];
    }

    #[DataProvider('methods')]
    public function test_every_route_is_401_without_a_token_as_json(string $method): void
    {
        $this->api($method, self::URL, null, ['wrapped_key' => base64_encode('abc'), 'version' => 1])
            ->assertUnauthorized()->assertJsonStructure(['message']);
        $this->assertSame(0, RecoveryKey::count());
    }

    #[DataProvider('methods')]
    public function test_every_route_is_401_with_an_expired_token(string $method): void
    {
        $user = $this->syncUser();
        $expired = $this->tokenFor($user, 'android:hp', now()->subMinute());

        $this->api($method, self::URL, $expired, ['wrapped_key' => base64_encode('abc'), 'version' => 1])
            ->assertUnauthorized()->assertJsonStructure(['message']);
        $this->assertSame(0, RecoveryKey::count());
    }

    // --- batas laju -----------------------------------------------------------------------------------

    public function test_the_three_routes_share_a_limit_of_20_per_minute_per_account(): void
    {
        $user = $this->syncUser();
        $token = $this->tokenFor($user);
        $other = $this->tokenFor($this->syncUser());
        $bytes = $this->blob();

        for ($i = 0; $i < 7; $i++) {
            $this->putKey($token, $bytes)->assertOk();
        }
        for ($i = 0; $i < 7; $i++) {
            $this->api('GET', self::URL, $token)->assertOk();
        }
        for ($i = 0; $i < 6; $i++) {
            $this->api('DELETE', self::URL, $token)->assertNoContent();
        }

        $blocked = $this->api('GET', self::URL, $token)->assertStatus(429);
        $this->assertNotNull($blocked->headers->get('Retry-After'));
        $this->putKey($token, $bytes)->assertStatus(429);
        $this->api('DELETE', self::URL, $token)->assertStatus(429);
        $this->assertSame(0, RecoveryKey::count()); // PUT yang diblok tidak menulis

        // Tidak menghalangi rute lain milik akun yang sama, maupun akun lain.
        $this->api('GET', '/api/sync/pull', $token)->assertOk();
        $this->api('GET', self::URL, $other)->assertNotFound();
        $this->api('POST', '/api/auth/logout', $token)->assertNoContent();

        // Jatah pulih setelah semenit.
        $this->travel(61)->seconds();
        $this->api('GET', self::URL, $this->tokenFor($user))->assertNotFound();
    }

    public function test_unauthenticated_requests_do_not_spend_any_allowance(): void
    {
        $user = $this->syncUser();
        $token = $this->tokenFor($user);

        for ($i = 0; $i < 30; $i++) {
            $this->api('GET', self::URL)->assertUnauthorized();
        }

        for ($i = 0; $i < 20; $i++) {
            $this->api('GET', self::URL, $token)->assertNotFound();
        }
        $this->api('GET', self::URL, $token)->assertStatus(429);
    }

    // --- hapus akun -----------------------------------------------------------------------------------

    public function test_deleting_the_account_deletes_the_copy(): void
    {
        $user = $this->syncUser();
        $bystander = $this->syncUser();
        $token = $this->tokenFor($user);
        $this->putKey($token, $this->blob())->assertOk();
        $this->putKey($this->tokenFor($bystander), $this->blob())->assertOk();

        $this->api('DELETE', '/api/account', $token)->assertNoContent();

        $this->assertSame(0, RecoveryKey::where('user_id', $user->id)->count());
        $this->assertSame(1, RecoveryKey::where('user_id', $bystander->id)->count());
    }

    // --- tidak bocor ----------------------------------------------------------------------------------

    /** @param  list<string>  $logged */
    private function assertNoBlobIn(array $logged, string $bytes): void
    {
        $this->assertNotEmpty($logged, 'penangkap log tidak menangkap apa pun, tesnya tak berarti');
        foreach ($logged as $line) {
            $this->assertStringNotContainsString(self::MARKER, $line);
            $this->assertStringNotContainsString(base64_encode(self::MARKER), $line);
            $this->assertStringNotContainsString(substr(base64_encode($bytes), 0, 16), $line);
        }
    }

    /** @return list<string> baris log (pesan + konteks) yang tertangkap selama $run. */
    private function captureLog(callable $run): array
    {
        $logged = [];
        Event::listen(MessageLogged::class, function (MessageLogged $event) use (&$logged) {
            $logged[] = $event->message.' '.json_encode($event->context, JSON_PARTIAL_OUTPUT_ON_ERROR | JSON_INVALID_UTF8_SUBSTITUTE);
        });
        $run();

        return $logged;
    }

    public function test_the_blob_is_never_written_to_the_log_on_any_path(): void
    {
        $user = $this->syncUser();
        $token = $this->tokenFor($user);
        $bytes = $this->blob(120, marked: true);
        $b64 = base64_encode($bytes);

        $logged = $this->captureLog(function () use ($token, $bytes, $b64, $user) {
            Log::info('awal tes'); // satu entri sengaja agar tes tidak lolos hanya karena log kosong
            $this->putKey($token, $bytes)->assertOk();
            $this->api('GET', self::URL, $token)->assertOk();
            $this->putKey($token, $bytes, 7)->assertStatus(422); // version tak dikenal
            $this->api('PUT', self::URL, $token, ['wrapped_key' => $b64.'!', 'version' => 1])->assertStatus(422);
            $this->api('PUT', self::URL, $token, ['wrapped_key' => $b64.str_repeat('A', 11000), 'version' => 1])->assertStatus(422);
            $user->forceFill(['plans' => []])->save();
            $this->putKey($token, $bytes)->assertForbidden();
            $this->api('DELETE', self::URL, $token)->assertNoContent();
        });

        $this->assertNoBlobIn($logged, $bytes);
    }

    public function test_a_database_failure_while_saving_logs_no_blob_and_answers_a_generic_500(): void
    {
        $user = $this->syncUser();
        $token = $this->tokenFor($user);
        $bytes = $this->blob(120, marked: true);
        Schema::drop('recovery_keys'); // paksa QueryException; pesannya memuat SQL beserta nilai binding

        $response = null;
        $logged = $this->captureLog(function () use ($token, $bytes, &$response) {
            $response = $this->putKey($token, $bytes);
        });

        $response->assertStatus(500)->assertExactJson(['message' => 'Gagal menyimpan kode pemulihan, coba lagi.']);
        $this->assertStringNotContainsString(self::MARKER, $response->getContent());
        $this->assertStringNotContainsString(base64_encode($bytes), $response->getContent());
        $this->assertNoBlobIn($logged, $bytes);
        $this->assertStringContainsString('Gagal menyimpan salinan kunci pemulihan', implode("\n", $logged));
    }

    public function test_serializing_the_user_never_includes_the_recovery_key(): void
    {
        $user = $this->syncUser();
        $bytes = $this->blob(100, marked: true);
        $this->putKey($this->tokenFor($user), $bytes)->assertOk();

        $loaded = User::with('recoveryKey')->find($user->id);
        $this->assertNotNull($loaded->recoveryKey); // relasi memang termuat

        foreach ([json_encode($loaded->toArray(), JSON_INVALID_UTF8_SUBSTITUTE), $loaded->toJson(JSON_INVALID_UTF8_SUBSTITUTE)] as $json) {
            $this->assertStringNotContainsString('recoveryKey', $json);
            $this->assertStringNotContainsString('recovery_key', $json);
            $this->assertStringNotContainsString('wrapped_key', $json);
            $this->assertStringNotContainsString(self::MARKER, $json);
            $this->assertStringNotContainsString(base64_encode($bytes), $json);
        }
        $this->assertArrayNotHasKey('recoveryKey', $loaded->toArray());
    }

    public function test_serializing_the_model_itself_hides_the_blob(): void
    {
        $user = $this->syncUser();
        $this->putKey($this->tokenFor($user), $this->blob(100, marked: true))->assertOk();

        $row = RecoveryKey::sole();
        $this->assertSame(self::MARKER, substr($row->getRawOriginal('wrapped_key'), 0, strlen(self::MARKER)));
        $this->assertArrayNotHasKey('wrapped_key', $row->toArray());
        $this->assertStringNotContainsString('wrapped_key', $row->toJson());
        $this->assertStringNotContainsString(self::MARKER, json_encode($row->toArray(), JSON_INVALID_UTF8_SUBSTITUTE));
    }

    public function test_the_column_is_not_encrypted_or_cast_so_the_server_stores_the_clients_bytes_as_is(): void
    {
        $user = $this->syncUser();
        $bytes = $this->blob(100);
        $this->putKey($this->tokenFor($user), $bytes)->assertOk();

        $this->assertArrayNotHasKey('wrapped_key', (new RecoveryKey)->getCasts());
        $this->assertSame($bytes, $this->storedBytes($user)); // bukan Crypt::encrypt dan bukan base64
    }
}
