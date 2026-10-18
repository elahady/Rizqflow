<?php

namespace App\Auth;

/**
 * Memeriksa ID token dari tombol Masuk dengan Google. Dipisah jadi kontrak supaya tes tidak
 * butuh jaringan, dan supaya verifikasi yang sama bisa dipakai akun server Android (Tahap 9).
 */
interface GoogleIdTokenVerifier
{
    /**
     * Klaim token bila tanda tangan, penerbit, audiens (client ID kita), dan masa berlakunya sah;
     * null bila tidak.
     *
     * @return array{sub: string, email?: string, email_verified?: bool, name?: string}|null
     */
    public function verify(string $idToken): ?array;
}
