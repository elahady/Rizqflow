<?php

namespace App\Play;

use Closure;
use Google\Client as GoogleClient;
use GuzzleHttp\Client as GuzzleClient;
use Illuminate\Support\Facades\Log;
use Throwable;

/**
 * Memverifikasi header `Authorization: Bearer <JWT>` dari push Pub/Sub dengan autentikasi: JWT
 * OIDC yang ditandatangani Google. Tanda tangan, penerbit (accounts.google.com), dan masa
 * berlaku diperiksa `Google\AccessToken\Verify` (kunci publik dari JWKS Google); audience
 * diperiksa lagi di sini secara ketat. Bila PLAY_RTDN_SERVICE_ACCOUNT_EMAIL diisi, klaim `email`
 * harus sama dan `email_verified` harus true (membedakan akun layanan Pub/Sub kita dari identitas
 * Google lain yang bisa membuat token dengan audience yang sama).
 *
 * Alasan penolakan tidak pernah dikembalikan ke pemanggil (bool saja), dan log hanya memuat kelas
 * galat, tidak pernah tokennya.
 */
class GoogleOidcNotificationAuthenticator implements PlayNotificationAuthenticator
{
    /** JWT Google sekitar 1 KB; token yang jauh lebih panjang ditolak tanpa diurai. */
    private const MAX_TOKEN_LENGTH = 8192;

    /** @param null|Closure(string): GoogleClient $clientFactory hanya untuk tes (klien dengan HTTP/cache palsu) */
    public function __construct(
        private readonly string $audience,
        private readonly ?string $serviceAccountEmail = null,
        private readonly ?Closure $clientFactory = null,
    ) {}

    public function isConfigured(): bool
    {
        return $this->audience !== '';
    }

    public function isAuthentic(string $bearerToken): bool
    {
        if (! $this->isConfigured() || $bearerToken === '' || strlen($bearerToken) > self::MAX_TOKEN_LENGTH) {
            return false;
        }

        try {
            $claims = $this->client()->verifyIdToken($bearerToken);
        } catch (Throwable $e) {
            // Token rusak, atau JWKS Google tak terjangkau: sama-sama ditolak (Pub/Sub mengulang).
            Log::warning('Token OIDC notifikasi Play ditolak.', ['exception' => $e::class]);

            return false;
        }

        if (! is_array($claims)) {
            return false;
        }

        // Verify melewati pemeriksaan audience bila token tak punya klaim `aud`; di sini wajib ada
        // dan persis sama (token Google selalu memakai `aud` berupa string).
        if (($claims['aud'] ?? null) !== $this->audience) {
            return false;
        }

        if ($this->serviceAccountEmail !== null && $this->serviceAccountEmail !== '') {
            $email = $claims['email'] ?? null;
            if (! is_string($email) || strcasecmp($email, $this->serviceAccountEmail) !== 0
                || ($claims['email_verified'] ?? false) !== true) {
                return false;
            }
        }

        return true;
    }

    private function client(): GoogleClient
    {
        if ($this->clientFactory !== null) {
            return ($this->clientFactory)($this->audience);
        }

        $client = new GoogleClient(['client_id' => $this->audience]);
        $client->setHttpClient(new GuzzleClient(['timeout' => 5, 'connect_timeout' => 3]));

        return $client;
    }
}
