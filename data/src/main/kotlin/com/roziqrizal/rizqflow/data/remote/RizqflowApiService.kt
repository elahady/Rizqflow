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

    /** Lapor token pembelian Pro/Sync ke server (Tahap 9, `Api\PurchaseController@claim`). */
    @Headers("Content-Type: application/json")
    @POST("purchases/claim")
    suspend fun claimPurchases(
        @Header("Authorization") bearerToken: String,
        @Body body: RequestBody,
    ): Response<ResponseBody>
}

@Serializable
data class LoginRequestDto(
    val credential: String,
    @SerialName("device_id") val deviceId: String,
)

@Serializable
data class LoginResponseDto(
    val token: String,
    val user: UserDto,
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
