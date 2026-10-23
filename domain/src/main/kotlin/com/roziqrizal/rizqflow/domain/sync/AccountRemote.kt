package com.roziqrizal.rizqflow.domain.sync

/**
 * Akun server Rizqflow (Tahap 9, server-sync.md): tukar ID token Google jadi token API, dan hapus
 * akun server. Kontrak murni tanpa Retrofit/OkHttp — implementasi sungguhan (`RetrofitAccountRemote`)
 * ada di `:data`. Ini akun SERVER untuk Sync, beda dari akun lokal Android
 * ([com.roziqrizal.rizqflow.domain.auth.LocalAccounts]) yang tidak pernah menyentuh jaringan.
 * Menghapus akun server tidak menyentuh data di ponsel — ponsel tetap sumber kebenaran.
 */
interface AccountRemote {
    /** [deviceId] dipakai server untuk mencabut token lama di perangkat yang sama saat masuk ulang. */
    suspend fun loginWithGoogle(idToken: String, deviceId: String): RemoteResult<ApiAccount>

    suspend fun deleteAccount(apiToken: String): RemoteResult<Unit>
}

/** Token API (Sanctum) dan data akun server yang dikembalikan setelah [AccountRemote.loginWithGoogle]. */
data class ApiAccount(
    val apiToken: String,
    val userId: Long,
    val name: String?,
    val email: String,
)
