package com.roziqrizal.rizqflow.domain.sync

import java.time.Instant

/**
 * Akun server Rizqflow (Tahap 9, server-sync.md): tukar ID token Google jadi token API, putar token
 * sebelum kedaluwarsa, keluar, dan hapus akun server. Kontrak murni tanpa Retrofit/OkHttp —
 * implementasi sungguhan (`RetrofitAccountRemote`) ada di `:data`. Ini akun SERVER untuk Sync, beda
 * dari akun lokal Android ([com.roziqrizal.rizqflow.domain.auth.LocalAccounts]) yang tidak pernah
 * menyentuh jaringan. Menghapus akun server tidak menyentuh data di ponsel — ponsel tetap sumber
 * kebenaran.
 */
interface AccountRemote {
    /** [deviceId] dipakai server untuk mencabut token lama di perangkat yang sama saat masuk ulang. */
    suspend fun loginWithGoogle(idToken: String, deviceId: String): RemoteResult<ApiAccount>

    /**
     * Masuk dengan email dan sandi akun server (Tahap 14, `POST /api/auth/login`): akun yang SAMA dengan masuk web.
     * Galat aturan (sandi salah, belum diverifikasi, terlalu banyak percobaan) datang sebagai [RemoteError.Rejected]
     * dengan pesan Bahasa Indonesia dari server. [password] tidak pernah disimpan atau dicetak di sisi klien.
     */
    suspend fun loginWithEmail(email: String, password: String, deviceId: String): RemoteResult<ApiAccount>

    /**
     * Memutar token (`POST /api/auth/refresh`): token [apiToken] dicabut server dan diganti token baru
     * dengan masa berlaku penuh. [RemoteError.Unauthorized] berarti token tidak sah atau sudah
     * kedaluwarsa, jadi harus masuk ulang dengan Google. Dua pemutaran bersamaan dengan token yang sama:
     * paling banyak satu berhasil, jadi pemanggil tidak boleh memanggilnya paralel (lihat `TokenRefresher`).
     */
    suspend fun refresh(apiToken: String): RemoteResult<RefreshedToken>

    /** Mencabut HANYA [apiToken] ini di server (`POST /api/auth/logout`); token perangkat lain tidak tersentuh. */
    suspend fun logout(apiToken: String): RemoteResult<Unit>

    suspend fun deleteAccount(apiToken: String): RemoteResult<Unit>
}

/**
 * Token API (Sanctum) dan data akun server yang dikembalikan setelah [AccountRemote.loginWithGoogle].
 * [expiresAt] null bila server (versi lama) tidak mengirim masa berlaku.
 */
data class ApiAccount(
    val apiToken: String,
    val userId: Long,
    val name: String?,
    val email: String,
    val expiresAt: Instant? = null,
) {
    /** [apiToken] disembunyikan: objek ini ikut tersimpan di state layar masuk sebelum ruang kerja terbuka. */
    override fun toString(): String = "ApiAccount(userId=$userId, email=$email, expiresAt=$expiresAt, apiToken=disembunyikan)"
}

/** Token pengganti hasil [AccountRemote.refresh]. [expiresAt] null bila server tidak mengirim masa berlaku. */
data class RefreshedToken(
    val apiToken: String,
    val expiresAt: Instant? = null,
)
