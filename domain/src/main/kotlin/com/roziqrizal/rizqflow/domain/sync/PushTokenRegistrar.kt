package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.entitlement.Entitlements
import com.roziqrizal.rizqflow.domain.entitlement.Feature

/**
 * Mendaftarkan dan mencabut token FCM perangkat ini di server (Tahap 9/13, wiring FCM 2026-11-01). Kontrak murni;
 * implementasinya `RetrofitPushTokenRegistrar` di `:data`, dibungkus penjaga 401 di `:app`. Kontrak endpoint ada di
 * docs/server-sync.md bagian "Push notifikasi":
 * - `POST /api/push/tokens` badan `{"token":"...","device_id":"...","platform":"android"}`
 * - `DELETE /api/push/tokens/{device_id}`
 *
 * Satu token per perangkat ([PushTokenRegistration.deviceId] = pengenal pemasangan yang sama dengan masuk). Server
 * memakai token ini hanya untuk mengirim kabar generik (`roomId` + `kind`), tidak pernah data keuangan.
 */
interface PushTokenRegistrar {
    suspend fun register(apiToken: String, registration: PushTokenRegistration): RemoteResult<Unit>

    suspend fun revoke(apiToken: String, deviceId: String): RemoteResult<Unit>
}

/**
 * Satu pendaftaran. [platform] selalu `android` (kontrak). `toString` menyembunyikan [token]: token FCM bukan data
 * keuangan, tetapi siapa pun yang memegangnya bisa mengirim push ke perangkat ini lewat proyek Firebase yang sama.
 */
data class PushTokenRegistration(val token: String, val deviceId: String) {
    val platform: String get() = PLATFORM_ANDROID

    override fun toString(): String = "PushTokenRegistration(deviceId=$deviceId, platform=$platform, token=disembunyikan)"

    companion object {
        const val PLATFORM_ANDROID = "android"
    }
}

/** Hasil [PushTokenSync.register]. Selain [Registered] tidak ada yang dikirim ke server (kecuali [Failed]). */
sealed interface PushRegistrationOutcome {
    data object Registered : PushRegistrationOutcome

    /** FCM mati (tanpa `google-services.json`) atau token belum ada: tidak ada yang dikirim. */
    data object NoFcmToken : PushRegistrationOutcome

    /** Pengguna tidak punya Sync: token tidak dikirim. */
    data object NoSync : PushRegistrationOutcome

    /** Tidak ada token API server (belum masuk, akun lokal, atau sesi berakhir): token tidak dikirim. */
    data object NoServerSession : PushRegistrationOutcome

    /** Server menolak atau tidak terjangkau; dicoba lagi pada pemicu berikutnya (buka aplikasi, token FCM baru). */
    data class Failed(val error: RemoteError) : PushRegistrationOutcome
}

/**
 * Kapan token FCM dikirim dan dicabut (keputusan murni, diuji di `:domain`):
 * - [register] saat masuk, saat aplikasi dibuka, dan saat FCM memberi token baru; HANYA bila ada token FCM, pengguna
 *   punya Sync ([Feature.ENCRYPTED_SYNC], lewat satu lapisan entitlement), dan ada token API server.
 * - [revoke] saat keluar dan sebelum hapus akun, dengan token API yang masih berlaku saat itu (pemanggil yang
 *   memegangnya, karena keluar membersihkan token lokal lebih dulu). Mencabut tidak digerbang Sync: pengguna yang
 *   langganannya habis tetap harus bisa berhenti menerima push.
 */
class PushTokenSync(
    private val registrar: PushTokenRegistrar,
    private val entitlements: Entitlements,
    private val apiToken: () -> String?,
    private val deviceId: String,
) {
    suspend fun register(fcmToken: String?): PushRegistrationOutcome {
        val token = fcmToken?.takeIf { it.isNotBlank() } ?: return PushRegistrationOutcome.NoFcmToken
        if (!entitlements.isEnabled(Feature.ENCRYPTED_SYNC)) return PushRegistrationOutcome.NoSync
        val api = apiToken() ?: return PushRegistrationOutcome.NoServerSession
        return when (val result = registrar.register(api, PushTokenRegistration(token, deviceId))) {
            is RemoteResult.Success -> PushRegistrationOutcome.Registered
            is RemoteResult.Failure -> PushRegistrationOutcome.Failed(result.error)
        }
    }

    suspend fun revoke(apiToken: String): RemoteResult<Unit> = registrar.revoke(apiToken, deviceId)
}
