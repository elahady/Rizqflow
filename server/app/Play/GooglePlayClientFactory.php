<?php

namespace App\Play;

use Google\Client as GoogleClient;
use Google\Service\AndroidPublisher;
use GuzzleHttp\Client as GuzzleClient;

/**
 * Membangun klien Google Play Developer API dari konfigurasi PLAY_SERVICE_ACCOUNT_JSON. Isi
 * berkas kunci akun layanan tidak pernah dicatat atau disertakan dalam pesan galat.
 */
final class GooglePlayClientFactory
{
    /** Batas waktu panggilan keluar (detik): permintaan klaim tidak boleh menggantung lama. */
    private const TIMEOUT = 10;

    private const CONNECT_TIMEOUT = 5;

    /**
     * @param  string|null  $configured  jalur berkas JSON akun layanan, isi JSON mentah, atau isi JSON dalam base64
     *
     * @throws PlayNotConfigured bila kosong atau tidak terbaca sebagai kunci akun layanan
     */
    public static function make(?string $configured): AndroidPublisher
    {
        $credentials = self::credentials($configured);

        $client = new GoogleClient;
        $client->setApplicationName('Rizqflow Server');
        $client->setAuthConfig($credentials + ['client_id' => '']);
        $client->addScope(AndroidPublisher::ANDROIDPUBLISHER);
        // Sama dengan klien bawaan Google (http_errors=false: jawaban galat ditangani REST::execute),
        // ditambah batas waktu yang tidak ada di bawaannya.
        $client->setHttpClient(new GuzzleClient([
            'http_errors' => false,
            'timeout' => self::TIMEOUT,
            'connect_timeout' => self::CONNECT_TIMEOUT,
        ]));

        return new AndroidPublisher($client);
    }

    /**
     * @return array<string, mixed>
     *
     * @throws PlayNotConfigured
     */
    public static function credentials(?string $configured): array
    {
        $value = trim((string) $configured);
        if ($value === '') {
            throw new PlayNotConfigured;
        }

        $json = self::readJson($value);
        $decoded = $json === null ? null : json_decode($json, true);

        if (! is_array($decoded)
            || ! is_string($decoded['client_email'] ?? null) || $decoded['client_email'] === ''
            || ! is_string($decoded['private_key'] ?? null) || $decoded['private_key'] === '') {
            throw new PlayNotConfigured('Kunci akun layanan Google Play tidak dapat dibaca. Periksa PLAY_SERVICE_ACCOUNT_JSON.');
        }

        // Selalu diperlakukan sebagai akun layanan, apa pun isi field "type".
        $decoded['type'] = 'service_account';

        return $decoded;
    }

    private static function readJson(string $value): ?string
    {
        if (str_starts_with($value, '{')) {
            return $value;
        }

        // Nilai base64 bisa sangat panjang; hanya nilai pendek yang mungkin jalur berkas.
        if (strlen($value) < 1024) {
            foreach ([$value, base_path($value)] as $path) {
                if (is_file($path)) {
                    $contents = file_get_contents($path);

                    return $contents === false ? null : $contents;
                }
            }
        }

        $decoded = base64_decode($value, true);

        return $decoded === false ? null : $decoded;
    }
}
