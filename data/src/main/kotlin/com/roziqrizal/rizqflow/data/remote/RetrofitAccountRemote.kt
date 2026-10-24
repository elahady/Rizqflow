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

/** Implementasi [AccountRemote] sungguhan lewat [RizqflowApiService] (Tahap 9, token berumur Tahap 12). */
class RetrofitAccountRemote(private val service: RizqflowApiService) : AccountRemote {
    override suspend fun loginWithGoogle(idToken: String, deviceId: String): RemoteResult<ApiAccount> =
        safeCall {
            val request = jsonBody(LoginRequestDto.serializer(), LoginRequestDto(credential = idToken, deviceId = deviceId))
            val response = service.loginWithGoogle(request)
            val body = response.body()
            if (response.isSuccessful && body != null) {
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
            val response = service.refreshToken(bearer(apiToken))
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
