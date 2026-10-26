package com.roziqrizal.rizqflow.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit murni memakai [RequestBody]/[ResponseBody] mentah (didukung bawaan Retrofit, tanpa
 * converter) — JSON di-encode/decode manual lewat kotlinx.serialization di `RetrofitAccountRemote`/
 * `RetrofitSyncRemote` ([RemoteCallSupport]), supaya tidak bergantung pada library converter pihak
 * ketiga. Bentuk JSON persis seperti divalidasi/dibalas
 * `server/app/Http/Controllers/Api/AccountController.php` dan `SyncController.php` (Tahap 9).
 */
interface RizqflowApiService {
    @Headers("Content-Type: application/json")
    @POST("auth/google")
    suspend fun loginWithGoogle(@Body body: RequestBody): Response<ResponseBody>

    /** Masuk dengan email dan sandi (Tahap 14, `Api\AccountController::loginWithPassword`). Jawabannya sama dengan [loginWithGoogle]. */
    @Headers("Content-Type: application/json")
    @POST("auth/login")
    suspend fun loginWithEmail(@Body body: RequestBody): Response<ResponseBody>

    /**
     * Memutar token (`POST /api/auth/refresh`, Tahap 12): token ini dicabut dan token baru dikembalikan. Badan
     * berisi `device_name` opsional (Tahap 14); tanpa nama badannya KOSONG, seperti sebelum Tahap 14.
     */
    @POST("auth/refresh")
    suspend fun refreshToken(
        @Header("Authorization") bearerToken: String,
        @Body body: RequestBody,
    ): Response<ResponseBody>

    /** Mencabut hanya token yang dipakai permintaan ini (`POST /api/auth/logout`, 204). Tanpa badan. */
    @POST("auth/logout")
    suspend fun logout(@Header("Authorization") bearerToken: String): Response<ResponseBody>

    /** Daftar perangkat yang masuk ke akun ini (Tahap 14, `Api\AccountController::devices`). Tanpa badan. */
    @GET("auth/tokens")
    suspend fun listTokens(@Header("Authorization") bearerToken: String): Response<ResponseBody>

    /** "Keluar dari semua perangkat" (Tahap 14, `DELETE /api/auth/tokens`, 204): mencabut semua token akun ini. */
    @DELETE("auth/tokens")
    suspend fun revokeAllTokens(@Header("Authorization") bearerToken: String): Response<ResponseBody>

    @DELETE("account")
    suspend fun deleteAccount(@Header("Authorization") bearerToken: String): Response<ResponseBody>

    @Headers("Content-Type: application/json")
    @POST("sync/push")
    suspend fun push(
        @Header("Authorization") bearerToken: String,
        @Body body: RequestBody,
    ): Response<ResponseBody>

    @GET("sync/pull")
    suspend fun pull(
        @Header("Authorization") bearerToken: String,
        @Query("after") after: Long,
        @Query("limit") limit: Int,
    ): Response<ResponseBody>

    /** Keanggotaan ruang yang dibagi (Tahap 9, roadmap.md "Ruang keluarga bersama"). */
    @POST("rooms/{roomId}/join")
    suspend fun joinRoom(
        @Header("Authorization") bearerToken: String,
        @Path("roomId") roomId: String,
    ): Response<ResponseBody>

    @GET("rooms/{roomId}/members")
    suspend fun roomMembers(
        @Header("Authorization") bearerToken: String,
        @Path("roomId") roomId: String,
    ): Response<ResponseBody>

    /** Salinan kunci akun terbungkus kode pemulihan (Tahap 13): unggah/ganti, ambil, hapus. Butuh Sync hanya untuk PUT. */
    @Headers("Content-Type: application/json")
    @PUT("account/recovery-key")
    suspend fun putRecoveryKey(
        @Header("Authorization") bearerToken: String,
        @Body body: RequestBody,
    ): Response<ResponseBody>

    @GET("account/recovery-key")
    suspend fun getRecoveryKey(@Header("Authorization") bearerToken: String): Response<ResponseBody>

    @DELETE("account/recovery-key")
    suspend fun deleteRecoveryKey(@Header("Authorization") bearerToken: String): Response<ResponseBody>

    /** Lapor token pembelian Pro/Sync ke server (Tahap 9, `Api\PurchaseController@claim`). */
    @Headers("Content-Type: application/json")
    @POST("purchases/claim")
    suspend fun claimPurchases(
        @Header("Authorization") bearerToken: String,
        @Body body: RequestBody,
    ): Response<ResponseBody>
}

/**
 * Badan `POST /auth/google`. [deviceName] (Tahap 14) opsional: nama yang bisa dikenali di daftar perangkat;
 * null tidak dikirim (encodeDefaults mati), jadi klien lama dan server lama tetap cocok.
 */
@Serializable
data class LoginRequestDto(
    val credential: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String? = null,
)

/**
 * Badan `POST /auth/login`: `{"email":"...","password":"...","device_id":"..."}`. `toString` sengaja menyembunyikan
 * [password] (sama seperti [RecoveryKeyPutDto]): sandi tidak boleh masuk log, laporan crash, atau pesan galat.
 */
@Serializable
data class EmailLoginRequestDto(
    val email: String,
    val password: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String? = null,
) {
    override fun toString(): String = "EmailLoginRequestDto(email=$email, password=disembunyikan, deviceId=$deviceId)"
}

/**
 * Jawaban `POST /auth/google`. [expiresAt] (ISO-8601 UTC) field aditif sejak Tahap 12: server lama
 * tidak mengirimnya, jadi bawaannya null dan jawaban tanpa field itu tetap sukses.
 */
@Serializable
data class LoginResponseDto(
    val token: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    val user: UserDto,
)

/** Jawaban `POST /auth/refresh`: `{"token":"...","expires_at":"..."}`. */
@Serializable
data class RefreshResponseDto(
    val token: String,
    @SerialName("expires_at") val expiresAt: String? = null,
)

/** Badan `POST /auth/refresh` (Tahap 14): hanya `device_name` opsional; null berarti badan `{}`. */
@Serializable
data class RefreshRequestDto(
    @SerialName("device_name") val deviceName: String? = null,
)

/** Jawaban `GET /auth/tokens`: `{"devices":[...]}`. Isi token tidak pernah ada di jawaban ini. */
@Serializable
data class DeviceListResponseDto(val devices: List<DeviceDto> = emptyList())

/** Satu perangkat di daftar: waktu dalam ISO-8601 UTC, null bila server tidak mengirim. */
@Serializable
data class DeviceDto(
    val id: Long,
    val label: String? = null,
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("last_used_at") val lastUsedAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    val current: Boolean = false,
)

@Serializable
data class UserDto(
    val id: Long,
    val name: String? = null,
    val email: String,
)

@Serializable
data class PushRequestDto(val entries: List<SyncEntryUploadDto>)

@Serializable
data class SyncEntryUploadDto(
    @SerialName("room_id") val roomId: String? = null,
    @SerialName("record_type") val recordType: String,
    @SerialName("record_id") val recordId: String,
    @SerialName("device_id") val deviceId: String,
    val payload: String,
    val deleted: Boolean,
    @SerialName("changed_at") val changedAt: String,
)

@Serializable
data class PushResponseDto(
    val ids: List<Long>,
    val cursor: Long,
)

@Serializable
data class SyncEntryDownloadDto(
    val id: Long,
    @SerialName("room_id") val roomId: String? = null,
    @SerialName("record_type") val recordType: String,
    @SerialName("record_id") val recordId: String,
    @SerialName("device_id") val deviceId: String,
    val payload: String,
    val deleted: Boolean,
    @SerialName("changed_at") val changedAt: String,
)

@Serializable
data class PullResponseDto(
    val entries: List<SyncEntryDownloadDto>,
    @SerialName("next_after") val nextAfter: Long,
    @SerialName("has_more") val hasMore: Boolean,
)

/** Bentuk error Laravel standar: `{"message": "..."}` (kadang disertai `errors`, sengaja diabaikan). */
@Serializable
data class ErrorResponseDto(val message: String? = null)

@Serializable
data class RoomMemberDto(
    val name: String? = null,
    val email: String,
    val you: Boolean,
)

@Serializable
data class RoomMembersResponseDto(val members: List<RoomMemberDto>)

/** Badan `POST /purchases/claim`: `{"purchases":[{"product_id":"...","purchase_token":"..."}]}`. */
@Serializable
data class PurchaseClaimRequestDto(val purchases: List<PurchaseClaimItemDto>)

@Serializable
data class PurchaseClaimItemDto(
    @SerialName("product_id") val productId: String,
    @SerialName("purchase_token") val purchaseToken: String,
)

/** Jawaban 200: `plans` paket akun di server setelah klaim, `results` sejajar dengan urutan masukan. */
@Serializable
data class PurchaseClaimResponseDto(
    val plans: List<String> = emptyList(),
    val results: List<PurchaseClaimResultDto> = emptyList(),
)

/** [status] sengaja String mentah: nilai yang belum dikenal klien dipetakan ke status UNKNOWN, bukan gagal decode. */
@Serializable
data class PurchaseClaimResultDto(
    @SerialName("product_id") val productId: String,
    val status: String,
)

/**
 * Badan `PUT /account/recovery-key`: `{"wrapped_key":"<base64 standar>","version":1}`. `toString` sengaja
 * menyembunyikan [wrappedKey] (data class biasanya mencetaknya): bungkusan itu sudah terenkripsi kode pemulihan,
 * tetapi tidak ada alasan membiarkannya masuk log, laporan crash, atau pesan galat.
 */
@Serializable
data class RecoveryKeyPutDto(
    @SerialName("wrapped_key") val wrappedKey: String,
    val version: Int,
) {
    override fun toString(): String = "RecoveryKeyPutDto(version=$version, wrappedKey=disembunyikan)"
}

/** Jawaban 200 `PUT`: `{"version":1,"updated_at":"..."}`. */
@Serializable
data class RecoveryKeyReceiptDto(
    val version: Int,
    @SerialName("updated_at") val updatedAt: String,
)

/** Jawaban 200 `GET`: `{"wrapped_key":"<base64>","version":1,"updated_at":"..."}`. `toString` menyembunyikan [wrappedKey], seperti [RecoveryKeyPutDto]. */
@Serializable
data class RecoveryKeyDto(
    @SerialName("wrapped_key") val wrappedKey: String,
    val version: Int,
    @SerialName("updated_at") val updatedAt: String,
) {
    override fun toString(): String = "RecoveryKeyDto(version=$version, updatedAt=$updatedAt, wrappedKey=disembunyikan)"
}
