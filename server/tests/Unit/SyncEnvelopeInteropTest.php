<?php

namespace Tests\Unit;

use InvalidArgumentException;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;
use Tests\Support\SyncEnvelopeReference;

/**
 * Interoperabilitas amplop sinkron (Tahap 9, sync-desain.md bagian C dan K): membuktikan bahwa
 * implementasi PHP murni (openssl, AES-256-GCM) membaca dan menulis byte yang sama dengan vektor emas
 * yang dibuat di sisi Android (`domain/src/test/resources/sync/golden/`). Dasar kelayakan klien web
 * (Tahap 14) yang mendekripsi di peramban.
 *
 * BATAS YANG HARUS DIPAHAMI: vektor `envelope.v1.json` dibuat dengan AES/GCM/NoPadding bawaan JDK,
 * BUKAN lewat Tink. Tes ini membuktikan AAD kanonis, wireId, bingkai berpengisi 256 byte, dan AES-GCM
 * murni (IV 12 byte terpisah, `ciphertext || tag`). Ia TIDAK membuktikan format kabel Tink yang
 * dipakai aplikasi sungguhan: Tink menambahkan awalan kunci 5 byte dan menaruh IV di depan
 * ciphertext, dan kunci aplikasi berupa keyset Tink, bukan 32 byte mentah. Sebelum klien web membaca
 * data nyata dari Android, format kabel Tink itu (atau sebuah AEAD murni di sisi Android) harus
 * dibuktikan terpisah. Vektor `shared_expense.v1.json` hanya memuat plaintext dan AAD, tanpa
 * ciphertext; tes di sini mengenkripsi-dekripsi bolak-balik dengan kunci tetap, bukan membandingkan
 * dengan ciphertext dari Android.
 *
 * Kelas acuan ada di `tests/Support` (bukan `app/`) karena server tidak boleh bisa mendekripsi.
 */
class SyncEnvelopeInteropTest extends TestCase
{
    /** @var array<string, mixed>|null */
    private static ?array $envelope = null;

    /** @var array<string, mixed>|null */
    private static ?array $sharedExpense = null;

    /** @return array<string, mixed> */
    private static function golden(string $name): array
    {
        // server/tests/Unit -> akar repo. Folder domain/ tidak ada bila server/ diuji sendirian (mis. di
        // dalam image Docker): tes dilewati, bukan gagal.
        $path = dirname(__DIR__, 3).'/domain/src/test/resources/sync/golden/'.$name;
        if (! is_file($path)) {
            self::markTestSkipped("Vektor emas tidak ditemukan ($path); jalankan dari checkout repo penuh.");
        }

        $decoded = json_decode((string) file_get_contents($path), true, 512, JSON_THROW_ON_ERROR);
        self::assertIsArray($decoded);

        return $decoded;
    }

    /** @return array<string, mixed> */
    private static function envelope(): array
    {
        return self::$envelope ??= self::golden('envelope.v1.json');
    }

    /** @return array<string, mixed> */
    private static function sharedExpense(): array
    {
        return self::$sharedExpense ??= self::golden('shared_expense.v1.json');
    }

    // ---- wireId dan AAD

    public function test_wire_id_vectors_match_the_android_vectors_including_unicode_ids(): void
    {
        foreach (self::envelope()['wireIdVectors'] as $vector) {
            $this->assertSame($vector['wireId'], SyncEnvelopeReference::wireId($vector['type'], $vector['id']), $vector['type'].'|'.$vector['id']);
            $this->assertSame(SyncEnvelopeReference::WIRE_ID_LENGTH, strlen($vector['wireId']));
        }
    }

    public function test_aad_vectors_match_byte_for_byte_including_the_int64_maximum_timestamp(): void
    {
        foreach (self::envelope()['aadVectors'] as $vector) {
            $wireId = SyncEnvelopeReference::wireId($vector['type'], $vector['id']);
            $aad = SyncEnvelopeReference::aad($vector['scope'], $wireId, $vector['changedAtMs'], $vector['deleted'], $vector['deviceId']);

            $this->assertSame($vector['aad'], $aad);
        }
        // Cap waktu Long.MAX_VALUE harus tetap bilangan bulat penuh di PHP 64-bit, bukan float.
        $this->assertSame(PHP_INT_MAX, self::envelope()['aadVectors'][4]['changedAtMs']);
    }

    #[DataProvider('invalidAadFields')]
    public function test_aad_fields_containing_the_separator_or_empty_are_rejected(string $scope, string $wireId, string $deviceId): void
    {
        $this->expectException(InvalidArgumentException::class);

        SyncEnvelopeReference::aad($scope, $wireId, 1, false, $deviceId);
    }

    /** @return array<string, array{string, string, string}> */
    public static function invalidAadFields(): array
    {
        return [
            'scope kosong' => ['', 'w', 'd'],
            'scope memuat pemisah' => ['r:a|b', 'w', 'd'],
            'wireId memuat pemisah' => ['a', 'w|x', 'd'],
            'deviceId kosong' => ['a', 'w', ''],
        ];
    }

    // ---- enkripsi AES-256-GCM murni

    public function test_the_framed_plaintext_is_256_bytes_with_a_big_endian_length_and_zero_padding(): void
    {
        $v = self::envelope()['encryptionVector'];
        $framed = SyncEnvelopeReference::frame($v['plaintextJson']);

        $this->assertSame($v['framedLength'], strlen($framed));
        $this->assertSame(strlen($v['plaintextJson']), unpack('n', substr($framed, 0, 2))[1]);
        $this->assertSame($v['plaintextJson'], SyncEnvelopeReference::unframe($framed));
        $this->assertSame(0, strlen(rtrim(substr($framed, 2 + strlen($v['plaintextJson'])), "\0")));
    }

    public function test_the_android_ciphertext_decrypts_to_the_framed_plaintext(): void
    {
        $v = self::envelope()['encryptionVector'];

        $opened = SyncEnvelopeReference::open(
            hex2bin($v['keyHex']), hex2bin($v['ivHex']), hex2bin($v['ciphertextHex']),
            $v['scope'], $v['wireId'], $v['changedAtMs'], $v['deleted'], $v['deviceId'],
        );

        $this->assertSame(1, $opened['v']);
        $this->assertSame('transaction', $opened['type']);
        $this->assertSame('tx-1', $opened['id']);
        $this->assertSame(['amount' => 25000, 'note' => 'kopi'], $opened['d']);
    }

    public function test_php_produces_the_identical_ciphertext_as_the_android_vector(): void
    {
        $v = self::envelope()['encryptionVector'];

        $sealed = SyncEnvelopeReference::seal(
            hex2bin($v['keyHex']), hex2bin($v['ivHex']), $v['scope'], 'transaction', 'tx-1',
            $v['plaintextJson'], $v['changedAtMs'], $v['deleted'], $v['deviceId'],
        );

        $this->assertSame($v['ciphertextHex'], bin2hex($sealed));
        $this->assertSame($v['framedLength'] + 16, strlen($sealed), 'ciphertext = bingkai + tag 16 byte');
    }

    /** @return array<string, array{array<string, mixed>}> */
    public static function tamperedFields(): array
    {
        return [
            'scope lain' => [['scope' => 'r:room-lain']],
            'cap waktu digeser 1 ms' => [['changedAtMs' => 1767225600001]],
            'tombstone dipalsukan' => [['deleted' => true]],
            'perangkat lain' => [['deviceId' => 'device-0002']],
            'wireId lain' => [['wireId' => 'sZzeDWm8p-37TOSy4lkuVR']],
        ];
    }

    /** @param array<string, mixed> $override */
    #[DataProvider('tamperedFields')]
    public function test_changing_any_outer_field_makes_decryption_fail(array $override): void
    {
        $v = array_replace(self::envelope()['encryptionVector'], $override);

        $this->expectException(InvalidArgumentException::class);
        $this->expectExceptionMessage('Dekripsi gagal');

        SyncEnvelopeReference::open(
            hex2bin($v['keyHex']), hex2bin($v['ivHex']), hex2bin($v['ciphertextHex']),
            $v['scope'], $v['wireId'], $v['changedAtMs'], $v['deleted'], $v['deviceId'],
        );
    }

    public function test_a_flipped_ciphertext_byte_a_wrong_key_a_wrong_iv_and_a_cut_tag_all_fail_authentication(): void
    {
        $v = self::envelope()['encryptionVector'];
        $key = hex2bin($v['keyHex']);
        $iv = hex2bin($v['ivHex']);
        $ciphertext = hex2bin($v['ciphertextHex']);
        $aad = $v['aad'];

        $this->assertNotNull(SyncEnvelopeReference::decrypt($key, $iv, $ciphertext, $aad), 'kontrol: vektor asli terbuka');

        $flipped = $ciphertext;
        $flipped[10] = chr(ord($flipped[10]) ^ 0x01);
        $this->assertNull(SyncEnvelopeReference::decrypt($key, $iv, $flipped, $aad));

        $this->assertNull(SyncEnvelopeReference::decrypt(str_repeat("\x01", 32), $iv, $ciphertext, $aad));
        $this->assertNull(SyncEnvelopeReference::decrypt($key, str_repeat("\x02", 12), $ciphertext, $aad));
        $this->assertNull(SyncEnvelopeReference::decrypt($key, $iv, substr($ciphertext, 0, -1), $aad));
        $this->assertNull(SyncEnvelopeReference::decrypt($key, $iv, 'pendek', $aad));
    }

    public function test_a_valid_ciphertext_whose_json_names_another_record_is_refused_by_the_wire_id_check(): void
    {
        $key = str_repeat("\x07", 32);
        $iv = str_repeat("\x08", 12);
        $json = '{"v":1,"type":"transaction","id":"tx-ASLI","d":{}}';
        // Penyerang (server) menyalin ciphertext sah tx-ASLI ke kolom luar yang menamai rekaman lain, dengan AAD
        // yang ia susun sendiri: dekripsi lolos, tetapi wireId tidak cocok dengan type+id di dalam amplop.
        $wireLain = SyncEnvelopeReference::wireId('transaction', 'tx-LAIN');
        $aad = SyncEnvelopeReference::aad('a', $wireLain, 5, false, 'dev');
        $ciphertext = SyncEnvelopeReference::encrypt($key, $iv, SyncEnvelopeReference::frame($json), $aad);

        $this->expectException(InvalidArgumentException::class);
        $this->expectExceptionMessage('wireId tidak cocok');

        SyncEnvelopeReference::open($key, $iv, $ciphertext, 'a', $wireLain, 5, false, 'dev');
    }

    // ---- bingkai berpengisi

    public function test_frame_pads_to_the_next_multiple_of_256_and_roundtrips_at_the_boundaries(): void
    {
        foreach ([1 => 256, 254 => 256, 255 => 512, 510 => 512, 511 => 768] as $jsonLength => $expectedFramed) {
            $json = str_repeat('x', $jsonLength);
            $framed = SyncEnvelopeReference::frame($json);

            $this->assertSame($expectedFramed, strlen($framed), "json $jsonLength byte");
            $this->assertSame($json, SyncEnvelopeReference::unframe($framed));
        }
    }

    public function test_a_json_that_exceeds_the_64_kb_budget_is_refused(): void
    {
        $this->expectException(InvalidArgumentException::class);

        SyncEnvelopeReference::frame(str_repeat('x', 65536 - 64));
    }

    /** @return array<string, array{string, string}> */
    public static function malformedFrames(): array
    {
        $ok = SyncEnvelopeReference::frame('{"a":1}');

        return [
            'kosong' => ['', 'Bingkai tanpa panjang'],
            'panjang nol' => [str_pad("\0\0", 256, "\0"), 'Panjang JSON nol'],
            'terpotong' => [substr($ok, 0, 5), 'Bingkai terpotong'],
            'pengisi tidak lengkap' => [substr($ok, 0, 200), 'Pengisi tidak lengkap'],
            'pengisi bukan nol' => [substr($ok, 0, 100)."\x01".substr($ok, 101), 'Pengisi bukan nol'],
        ];
    }

    #[DataProvider('malformedFrames')]
    public function test_malformed_frames_are_rejected(string $framed, string $message): void
    {
        $this->expectException(InvalidArgumentException::class);
        $this->expectExceptionMessage($message);

        SyncEnvelopeReference::unframe($framed);
    }

    // ---- shared_expense (ruang bersama)

    public function test_shared_expense_wire_id_and_aad_match_and_differ_from_the_personal_wire_id(): void
    {
        $v = self::sharedExpense();

        $this->assertSame($v['wireId'], SyncEnvelopeReference::wireId($v['type'], $v['id']));
        $this->assertSame($v['aadLive'], SyncEnvelopeReference::aad($v['scope'], $v['wireId'], $v['changedAtMs'], false, $v['deviceId']));
        $this->assertSame($v['aadTombstone'], SyncEnvelopeReference::aad($v['scope'], $v['wireId'], $v['changedAtMs'], true, $v['deviceId']));
        // Tipe berbeda untuk id transaksi yang sama menghasilkan wireId berbeda (kunci idempoten push tidak bentrok).
        $this->assertSame($v['sharedWireIdOfTx1'], SyncEnvelopeReference::wireId('shared_expense', 'tx-1'));
        $this->assertSame($v['personalWireIdOfSameTransaction'], SyncEnvelopeReference::wireId('transaction', 'tx-1'));
        $this->assertNotSame($v['personalWireIdOfSameTransaction'], $v['sharedWireIdOfTx1']);
    }

    public function test_shared_expense_json_built_in_php_equals_the_android_plaintext_exactly(): void
    {
        $v = self::sharedExpense();
        $in = $v['input'];

        $full = ['amount_minor' => $in['amountMinor'], 'currency' => $in['currency'], 'account_name' => $in['accountName'],
            'category_name' => $in['categoryName'], 'occurred_on' => $in['occurredOnEpochDay'], 'note' => $in['note']];
        $minimal = ['amount_minor' => $in['amountMinor'], 'currency' => $in['currency'], 'account_name' => $in['accountName'],
            'occurred_on' => $in['occurredOnEpochDay']];

        // Padanan JSON Android: ringkas, urutan kunci tetap, tanpa escape unicode/garis miring. Tombstone memakai
        // objek KOSONG ("{}"): di PHP array kosong menjadi "[]", jadi wajib stdClass.
        $this->assertSame($v['plaintextJson'], self::envelopeJson($v, $full));
        $this->assertSame($v['plaintextJsonWithoutOptionals'], self::envelopeJson($v, $minimal));
        $this->assertSame($v['plaintextJsonTombstone'], self::envelopeJson($v, new \stdClass));
        $this->assertSame('[]', json_encode([]), 'pengingat: array kosong PHP bukan objek JSON kosong');
    }

    public function test_shared_expense_roundtrips_live_and_tombstone_with_distinct_aad(): void
    {
        $v = self::sharedExpense();
        $key = str_repeat("\x11", 32);
        $iv = str_repeat("\x22", 12);

        foreach ([['plaintextJson', false], ['plaintextJsonWithoutOptionals', false], ['plaintextJsonTombstone', true]] as [$field, $deleted]) {
            $sealed = SyncEnvelopeReference::seal($key, $iv, $v['scope'], $v['type'], $v['id'], $v[$field], $v['changedAtMs'], $deleted, $v['deviceId']);
            $opened = SyncEnvelopeReference::open($key, $iv, $sealed, $v['scope'], $v['wireId'], $v['changedAtMs'], $deleted, $v['deviceId']);

            $this->assertSame('shared_expense', $opened['type'], $field);
            $this->assertSame($v['id'], $opened['id'], $field);
            $this->assertSame($deleted, $opened['d'] === [], $field);

            // Tombstone dipalsukan (atau dibalikkan) oleh server: AAD berbeda, otentikasi gagal.
            $this->assertNull(SyncEnvelopeReference::decrypt($key, $iv, $sealed, SyncEnvelopeReference::aad($v['scope'], $v['wireId'], $v['changedAtMs'], ! $deleted, $v['deviceId'])), $field);
        }
    }

    /** @param array<string, mixed>|\stdClass $data */
    private static function envelopeJson(array $vector, array|\stdClass $data): string
    {
        return json_encode(
            ['v' => $vector['version'], 'type' => $vector['type'], 'id' => $vector['id'], 'd' => $data],
            JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_THROW_ON_ERROR,
        );
    }
}
