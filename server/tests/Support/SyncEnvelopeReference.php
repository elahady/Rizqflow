<?php

namespace Tests\Support;

use InvalidArgumentException;

/**
 * Implementasi acuan PHP murni (openssl) dari amplop sinkron v1 (sync-desain.md bagian C, spesifikasi
 * persisnya di `SyncEnvelope.kt` di :domain). HANYA UNTUK TES: dipakai membuktikan bahwa klien selain
 * Android (web, Tahap 14) bisa membaca dan menulis format yang sama dari vektor emas.
 *
 * Sengaja berada di `tests/Support`, bukan di `app/`: server TIDAK boleh bisa mendekripsi data
 * keuangan (kunci hanya di perangkat). Kelas ini tidak boleh dipindah ke kode aplikasi.
 *
 * Yang dibuktikan: wireId, AAD kanonis, bingkai berpengisi 256 byte, dan AES-256-GCM murni
 * (IV 12 byte terpisah, ciphertext || tag 16 byte). Yang TIDAK dibuktikan: format kabel Tink. Android
 * mengenkripsi lewat Tink AEAD, yang membungkus keluaran dengan awalan kunci 5 byte (versi + id kunci)
 * dan IV acak 12 byte di depan ciphertext, jadi blob nyata dari aplikasi tidak sama dengan bentuk
 * `ciphertext || tag` di vektor ini.
 */
final class SyncEnvelopeReference
{
    public const AAD_PREFIX = 'rizqflow-sync/1';

    public const WIRE_ID_LENGTH = 22;

    public const PADDING_BLOCK = 256;

    public const MAX_PAYLOAD_BYTES = 65536;

    private const AEAD_RESERVE_BYTES = 64;

    private const WIRE_PREFIX = 'rizqflow-wire|';

    private const TAG_BYTES = 16;

    /** base64url tanpa pengisi dari SHA-256 "rizqflow-wire|type|id", 22 karakter pertama. */
    public static function wireId(string $type, string $id): string
    {
        self::requireField('type', $type);
        if ($id === '') {
            throw new InvalidArgumentException('id kosong');
        }
        $digest = hash('sha256', self::WIRE_PREFIX.$type.'|'.$id, true);

        return substr(rtrim(strtr(base64_encode($digest), '+/', '-_'), '='), 0, self::WIRE_ID_LENGTH);
    }

    /** AAD kanonis: `rizqflow-sync/1|<scope>|<wireId>|<changedAtMs>|<deleted 0/1>|<deviceId>`. */
    public static function aad(string $scope, string $wireId, int $changedAtMs, bool $deleted, string $deviceId): string
    {
        self::requireField('scope', $scope);
        self::requireField('wireId', $wireId);
        self::requireField('deviceId', $deviceId);

        return self::AAD_PREFIX.'|'.$scope.'|'.$wireId.'|'.$changedAtMs.'|'.($deleted ? '1' : '0').'|'.$deviceId;
    }

    /** `[2 byte panjang big-endian][JSON][nol pengisi sampai kelipatan 256 byte]`. */
    public static function frame(string $json): string
    {
        $length = strlen($json);
        if ($length === 0) {
            throw new InvalidArgumentException('JSON kosong');
        }
        $padded = intdiv(2 + $length + self::PADDING_BLOCK - 1, self::PADDING_BLOCK) * self::PADDING_BLOCK;
        if ($length > 0xFFFF || $padded > self::MAX_PAYLOAD_BYTES - self::AEAD_RESERVE_BYTES) {
            throw new InvalidArgumentException("Payload $length byte melebihi batas");
        }

        return str_pad(pack('n', $length).$json, $padded, "\0");
    }

    /** Kebalikan {@see frame()}; menolak bingkai terpotong, rusak, atau pengisinya bukan nol. */
    public static function unframe(string $framed): string
    {
        $size = strlen($framed);
        if ($size > self::MAX_PAYLOAD_BYTES) {
            throw new InvalidArgumentException('Bingkai melebihi batas');
        }
        if ($size < 2) {
            throw new InvalidArgumentException('Bingkai tanpa panjang');
        }
        $declared = unpack('n', substr($framed, 0, 2))[1];
        if ($declared === 0) {
            throw new InvalidArgumentException('Panjang JSON nol');
        }
        if (2 + $declared > $size) {
            throw new InvalidArgumentException('Bingkai terpotong');
        }
        if ($size % self::PADDING_BLOCK !== 0) {
            throw new InvalidArgumentException('Pengisi tidak lengkap');
        }
        if (strspn(substr($framed, 2 + $declared), "\0") !== $size - 2 - $declared) {
            throw new InvalidArgumentException('Pengisi bukan nol');
        }

        return substr($framed, 2, $declared);
    }

    /** AES-256-GCM murni: mengembalikan `ciphertext || tag(16)`. IV 12 byte dipasok pemanggil (tes memakai IV tetap). */
    public static function encrypt(string $key, string $iv, string $plaintext, string $aad): string
    {
        $ciphertext = openssl_encrypt($plaintext, 'aes-256-gcm', $key, OPENSSL_RAW_DATA, $iv, $tag, $aad, self::TAG_BYTES);
        if ($ciphertext === false) {
            throw new InvalidArgumentException('Enkripsi gagal');
        }

        return $ciphertext.$tag;
    }

    /** Mengembalikan plaintext, atau null bila otentikasi GCM gagal (kunci salah, ciphertext atau AAD berubah). */
    public static function decrypt(string $key, string $iv, string $ciphertextAndTag, string $aad): ?string
    {
        if (strlen($ciphertextAndTag) < self::TAG_BYTES) {
            return null;
        }
        $tag = substr($ciphertextAndTag, -self::TAG_BYTES);
        $ciphertext = substr($ciphertextAndTag, 0, -self::TAG_BYTES);
        $plaintext = openssl_decrypt($ciphertext, 'aes-256-gcm', $key, OPENSSL_RAW_DATA, $iv, $tag, $aad);

        return $plaintext === false ? null : $plaintext;
    }

    /** Membungkus lalu mengenkripsi JSON amplop dengan AAD kanonis. */
    public static function seal(string $key, string $iv, string $scope, string $type, string $id, string $json, int $changedAtMs, bool $deleted, string $deviceId): string
    {
        $aad = self::aad($scope, self::wireId($type, $id), $changedAtMs, $deleted, $deviceId);

        return self::encrypt($key, $iv, self::frame($json), $aad);
    }

    /**
     * Membuka satu amplop: dekripsi, buang pengisi, urai JSON, dan buktikan wireId di kolom luar sama
     * dengan hash type+id di dalam JSON. Mengembalikan JSON terurai (v, type, id, d), atau melempar.
     *
     * @return array{v: int, type: string, id: string, d: array<string, mixed>}
     */
    public static function open(string $key, string $iv, string $ciphertextAndTag, string $scope, string $wireId, int $changedAtMs, bool $deleted, string $deviceId): array
    {
        $plaintext = self::decrypt($key, $iv, $ciphertextAndTag, self::aad($scope, $wireId, $changedAtMs, $deleted, $deviceId));
        if ($plaintext === null) {
            throw new InvalidArgumentException('Dekripsi gagal');
        }
        $decoded = json_decode(self::unframe($plaintext), true, 512, JSON_THROW_ON_ERROR);
        if (! is_array($decoded) || ! is_int($decoded['v'] ?? null) || $decoded['v'] < 1
            || ! is_string($decoded['type'] ?? null) || ! is_string($decoded['id'] ?? null)
            || ! is_array($decoded['d'] ?? null)) {
            throw new InvalidArgumentException('Bidang wajib amplop tidak lengkap');
        }
        if (self::wireId($decoded['type'], $decoded['id']) !== $wireId) {
            throw new InvalidArgumentException('wireId tidak cocok dengan type+id di dalam amplop');
        }

        return $decoded;
    }

    private static function requireField(string $name, string $value): void
    {
        if ($value === '' || str_contains($value, '|')) {
            throw new InvalidArgumentException("Bidang AAD $name tidak sah");
        }
    }
}
