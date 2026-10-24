package com.roziqrizal.rizqflow.domain.auth

import java.security.MessageDigest

/**
 * Lokasi data per akun (diputuskan 2026-09-21: satu database per akun).
 *
 * Nama berkas berasal dari hash pengenal akun, bukan pengenalnya: email tidak muncul di nama
 * berkas, dan pengenal apa pun (email, UUID, `debug`, karakter aneh) menghasilkan nama yang aman
 * dan tetap sama untuk akun yang sama. Dua akun berbeda tidak pernah berbagi berkas.
 */
object AccountStorage {
    private const val PREFIX = "rizqflow-"

    /** Pengenal ruang kerja mode demo (S22): database terpisah yang dihapus saat keluar dari demo. */
    const val DEMO_ACCOUNT_ID = "rizqflow-demo"
    private const val HASH_HEX_CHARS = 32

    fun databaseName(accountId: String): String = "$PREFIX${scopeKey(accountId)}.db"

    /**
     * Kunci aman (32 heksadesimal, dari hash pengenal) untuk menamai penyimpanan lain yang juga satu per
     * akun, mis. berkas SharedPreferences. Aturannya sama dengan [databaseName] dan tidak boleh berubah.
     */
    fun scopeKey(accountId: String): String {
        require(accountId.isNotBlank()) { "Pengenal akun tidak boleh kosong" }
        val digest = MessageDigest.getInstance("SHA-256").digest(accountId.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return hex.take(HASH_HEX_CHARS)
    }
}
