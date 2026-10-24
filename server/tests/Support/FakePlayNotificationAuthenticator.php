<?php

namespace Tests\Support;

use App\Play\PlayNotificationAuthenticator;

/** Pengganti autentikator RTDN untuk tes: hanya token "token-sah" yang lolos. */
class FakePlayNotificationAuthenticator implements PlayNotificationAuthenticator
{
    public const VALID_TOKEN = 'token-sah';

    public function __construct(public bool $configured = true) {}

    public function isConfigured(): bool
    {
        return $this->configured;
    }

    public function isAuthentic(string $bearerToken): bool
    {
        return $bearerToken === self::VALID_TOKEN;
    }
}
