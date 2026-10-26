package com.roziqrizal.rizqflow.data.remote

import com.roziqrizal.rizqflow.domain.sync.AccountRemote
import com.roziqrizal.rizqflow.domain.sync.ApiAccount
import com.roziqrizal.rizqflow.domain.sync.RefreshedToken
import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import java.time.DateTimeException
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.SerializationException
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Response

/**
 * Implementasi [AccountRemote] sungguhan lewat [RizqflowApiService] (Tahap 9, token berumur Tahap 12).
 * [deviceName] (Tahap 14) dikirim ke server saat masuk dan memutar token, supaya daftar perangkat bisa menampilkan
 * nama yang dikenali; null berarti tidak dikirim.
 */
class RetrofitAccountRemote(
    private val service: RizqflowApiService,
    private val deviceName: String? = null,
) : AccountRemote {
    override suspend fun loginWithGoogle(idToken: String, deviceId: String): RemoteResult<ApiAccount> =
        safeCall {
            val request = jsonBody(
                LoginRequestDto.serializer(),
                LoginRequestDto(credential = idToken, deviceId = deviceId, deviceName = deviceName),
            )
            parseLoginResponse(service.loginWithGoogle(request))
        }

    override suspend fun loginWithEmail(email: String, password: String, deviceId: String): RemoteResult<ApiAccount> =
        safeCall {
            val request = jsonBody(
                EmailLoginRequestDto.serializer(),
                EmailLoginRequestDto(email = email, password = password, deviceId = deviceId, deviceName = deviceName),
            )
            parseLoginResponse(service.loginWithEmail(request))
        }

    /** Jawaban masuk (Google maupun email+sandi) jadi [RemoteResult]; galat aturan dipetakan lewat [toFailure]. */
    private fun parseLoginResponse(response: Response<ResponseBody>): RemoteResult<ApiAccount> {
        val body = response.body()
        return if (response.isSuccessful && body != null) {
            // Sama seperti refresh: badan 200 yang rusak jadi kegagalan biasa, bukan crash.
            try {
                val decoded = body.decodeJson(LoginResponseDto.serializer())
                RemoteResult.Success(
                    ApiAccount(
                        apiToken = decoded.token,
                        userId = decoded.user.id,
                        name = decoded.user.name,
                        email = decoded.user.email,
                        expiresAt = parseExpiresAt(decoded.expiresAt),
                    ),
                )
            } catch (_: SerializationException) {
                RemoteResult.Failure(RemoteError.Unexpected(response.code()))
            }
        } else {
            response.toFailure()
        }
    }

    override suspend fun refresh(apiToken: String): RemoteResult<RefreshedToken> =
        safeCall {
            // Tanpa nama perangkat badannya kosong, persis seperti sebelum Tahap 14 (server lama tetap cocok).
            val requestBody = deviceName?.let { jsonBody(RefreshRequestDto.serializer(), RefreshRequestDto(deviceName = it)) }
                ?: ByteArray(0).toRequestBody(null)
            val response = service.refreshToken(bearer(apiToken), requestBody)
            val body = response.body()
            if (response.isSuccessful && body != null) {
                // Badan 200 yang bukan JSON sesuai kontrak (mis. halaman galat dari proxy) bukan alasan untuk
                // crash di latar tiap aplikasi dibuka: dilaporkan sebagai kegagalan biasa yang dicoba lagi nanti.
                try {
                    val decoded = body.decodeJson(RefreshResponseDto.serializer())
                    RemoteResult.Success(RefreshedToken(apiToken = decoded.token, expiresAt = parseExpiresAt(decoded.expiresAt)))
                } catch (_: SerializationException) {
                    RemoteResult.Failure(RemoteError.Unexpected(response.code()))
                }
            } else {
                response.toFailure()
            }
        }

    override suspend fun logout(apiToken: String): RemoteResult<Unit> =
        safeCall {
            val response = service.logout(bearer(apiToken))
            if (response.isSuccessful) RemoteResult.Success(Unit) else response.toFailure()
        }

    override suspend fun deleteAccount(apiToken: String): RemoteResult<Unit> =
        safeCall {
            val response = service.deleteAccount(bearer(apiToken))
            if (response.isSuccessful) RemoteResult.Success(Unit) else response.toFailure()
        }
}

/**
 * `expires_at` dari server (ISO-8601, bentuk `Z` maupun `+00:00`) menjadi [Instant]. Yang kosong atau
 * tak terbaca menjadi null ("belum diketahui"), BUKAN galat: tanggal yang aneh tidak boleh menggagalkan
 * masuk atau memutar token yang sudah berhasil. Dibaca lewat [OffsetDateTime] karena `Instant.parse`
 * di sebagian versi runtime Android hanya menerima akhiran `Z`.
 */
internal fun parseExpiresAt(raw: String?): Instant? {
    if (raw.isNullOrBlank()) return null
    return try {
        OffsetDateTime.parse(raw.trim()).toInstant()
    } catch (_: DateTimeException) {
        null
    }
}
