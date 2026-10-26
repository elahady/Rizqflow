package com.roziqrizal.rizqflow.domain.sync

import java.time.Instant

/**
 * Daftar perangkat yang masuk ke akun server dan "Keluar dari semua perangkat" (Tahap 14, menu Lainnya).
 * Kontrak murni; implementasinya `RetrofitDeviceRemote` di `:data`. Dipisah dari [AccountRemote] supaya
 * kontrak akun yang sudah ada dan semua tesnya tidak ikut berubah.
 *
 * Daftar hanya memuat metadata token (tanpa isi token), termasuk token perangkat ini sendiri yang ditandai
 * [ServerDevice.current]. Mencabut semua token membuat perangkat ini ikut keluar; pemanggil wajib keluar lokal
 * setelah [RemoteResult.Success], dan juga setelah [RemoteError.Unauthorized] (token sudah tidak sah).
 */
interface DeviceRemote {
    suspend fun listDevices(apiToken: String): RemoteResult<List<ServerDevice>>

    /** Mencabut SEMUA token API akun ini (`DELETE /api/auth/tokens`), termasuk token perangkat yang memanggil. */
    suspend fun revokeAllDevices(apiToken: String): RemoteResult<Unit>
}

/**
 * Satu perangkat yang masuk. [deviceName] nama yang dikirim klien (mis. "Samsung SM-S911B"), null bila klien
 * lama tidak mengirimnya. [label] bawaan server ("Android" atau "Perangkat lain") untuk tampilan cadangan.
 * Waktu null berarti server tidak mengirimnya; itu bukan galat.
 */
data class ServerDevice(
    val id: Long,
    val label: String,
    val deviceName: String?,
    val createdAt: Instant?,
    val lastUsedAt: Instant?,
    val expiresAt: Instant?,
    val current: Boolean,
) {
    /** Sudah lewat masa berlakunya pada [now]; token seperti itu tidak bisa dipakai lagi di server. */
    fun isExpiredAt(now: Instant): Boolean = expiresAt?.isBefore(now) == true
}
