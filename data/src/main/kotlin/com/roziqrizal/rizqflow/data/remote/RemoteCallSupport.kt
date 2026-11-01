package com.roziqrizal.rizqflow.data.remote

import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import java.io.IOException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Response

internal val apiJson = Json { ignoreUnknownKeys = true }

private val jsonMediaType = "application/json".toMediaType()

internal fun <T> jsonBody(strategy: SerializationStrategy<T>, value: T): RequestBody =
    apiJson.encodeToString(strategy, value).toRequestBody(jsonMediaType)

internal fun <T> ResponseBody.decodeJson(strategy: DeserializationStrategy<T>): T =
    use { apiJson.decodeFromString(strategy, it.string()) }

internal fun bearer(apiToken: String): String = "Bearer $apiToken"

/** Jaring pengaman "tenang, bukan panik": jaringan mati/time out jadi [RemoteError.Unreachable], bukan crash. */
internal suspend fun <T> safeCall(block: suspend () -> RemoteResult<T>): RemoteResult<T> =
    try {
        block()
    } catch (e: IOException) {
        RemoteResult.Failure(RemoteError.Unreachable)
    }

/** Satu pemetaan kode HTTP ke [RemoteError] untuk semua endpoint — lihat [RemoteError] untuk alasan tiap kode. */
internal fun mapHttpError(httpCode: Int, rawErrorBody: String?, retryAfterHeader: String? = null, now: java.time.Instant = java.time.Instant.now()): RemoteError {
    val message = rawErrorBody?.let { raw ->
        runCatching { apiJson.decodeFromString(ErrorResponseDto.serializer(), raw).message }.getOrNull()
    }
    return when (httpCode) {
        401 -> RemoteError.Unauthorized
        403 -> RemoteError.SyncRequired
        422 -> RemoteError.Rejected(message ?: "Permintaan ditolak server.")
        else -> RemoteError.Unexpected(httpCode, parseRetryAfterSeconds(retryAfterHeader, now))
    }
}

/**
 * Header `Retry-After` berupa detik bulat ATAU tanggal HTTP (RFC 1123); keduanya jadi detik dari [now]. Tak terbaca
 * atau sudah lewat menghasilkan null (pemanggil jatuh ke penundaan bawaannya sendiri), tidak pernah melempar.
 */
internal fun parseRetryAfterSeconds(header: String?, now: java.time.Instant): Long? {
    val value = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    value.toLongOrNull()?.let { return it.takeIf { seconds -> seconds >= 0 } }
    return runCatching {
        val at = java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        java.time.Duration.between(now, at).seconds.takeIf { it >= 0 }
    }.getOrNull()
}

/** Baca `errorBody()` ke `RemoteResult.Failure` untuk response Retrofit mana pun yang gagal (!isSuccessful). */
internal fun <T> Response<*>.toFailure(): RemoteResult<T> =
    RemoteResult.Failure(mapHttpError(code(), errorBody()?.string(), headers()["Retry-After"]))
