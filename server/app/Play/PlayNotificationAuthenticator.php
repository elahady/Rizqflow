<?php

namespace App\Play;

/**
 * Mengautentikasi permintaan push Pub/Sub ke endpoint RTDN. Dipisah jadi kontrak supaya tes
 * tidak butuh jaringan atau tanda tangan Google sungguhan (pola sama dengan GoogleIdTokenVerifier).
 */
interface PlayNotificationAuthenticator
{
    /** Audience belum diisi: endpoint wajib menjawab 503 dan tidak pernah menerima tanpa autentikasi. */
    public function isConfigured(): bool;

    /** Token Bearer adalah JWT OIDC yang sah dari Google untuk audience (dan akun layanan) yang dikonfigurasi. */
    public function isAuthentic(string $bearerToken): bool;
}
