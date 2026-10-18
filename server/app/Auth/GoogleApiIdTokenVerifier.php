<?php

namespace App\Auth;

use Google\Client;
use Illuminate\Support\Facades\Log;
use Throwable;

/**
 * Verifikasi lewat google/apiclient: kunci publik Google, iss, aud = client ID Web yang juga
 * dipakai Android (`google_web_client_id`), dan exp.
 */
class GoogleApiIdTokenVerifier implements GoogleIdTokenVerifier
{
    public function __construct(private readonly string $clientId) {}

    public function verify(string $idToken): ?array
    {
        if ($this->clientId === '') {
            return null;
        }

        try {
            $payload = (new Client(['client_id' => $this->clientId]))->verifyIdToken($idToken);
        } catch (Throwable $e) {
            Log::warning('ID token Google ditolak: '.$e->getMessage());

            return null;
        }

        return is_array($payload) && isset($payload['sub']) ? $payload : null;
    }
}
