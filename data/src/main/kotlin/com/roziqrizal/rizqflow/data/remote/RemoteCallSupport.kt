package com.roziqrizal.rizqflow.data.remote

import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import com.roziqrizal.rizqflow.domain.sync.ServerErrorCodes
import java.io.IOException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
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

/**
 * Satu pemetaan kode HTTP ke [RemoteError] untuk semua endpoint — lihat [RemoteError] untuk alasan tiap kode. Badan galat
 * server `{message, code, retry_after}` dibaca bila ada (`code` stabil, lihat [ServerErrorCodes]):
 * - 401: [RemoteError.Unauthorized].
 * - 403: [RemoteError.SyncRequired] (paywall Sync) HANYA bila `code` = `sync_required` atau tidak ada kode sama sekali
 *   (server lama); 403 dengan kode lain (mis. `not_room_member`, `not_room_owner`) menjadi [RemoteError.Rejected] berkode.
 * - 422: [RemoteError.Rejected] (dengan kode bila ada).
 * - status lain dengan kode keanggotaan ruang yang dikenal ([ServerErrorCodes.REJECTION_CODES], mis. 404 `not_room_member`):
 *   [RemoteError.Rejected] berkode. 404 tanpa kode atau dengan kode lain tetap [RemoteError.Unexpected].
 * - sisanya [RemoteError.Unexpected] dengan `Retry-After` (header lebih dulu, lalu `retry_after` di badan) — termasuk 429.
 */
internal fun mapHttpError(httpCode: Int, rawErrorBody: String?, retryAfterHeader: String? = null, now: java.time.Instant = java.time.Instant.now()): RemoteError {
    val body = rawErrorBody?.let { raw ->
        runCatching { apiJson.decodeFromString(ErrorResponseDto.serializer(), raw) }.getOrNull()
    }
    val message = body?.message
    val code = body?.codeOrNull()
    return when {
        httpCode == 401 -> RemoteError.Unauthorized
        httpCode == 403 ->
            if (code == null || code == ServerErrorCodes.SYNC_REQUIRED) {
                RemoteError.SyncRequired
            } else {
                RemoteError.Rejected(message ?: "Permintaan ditolak server.", code)
            }

        httpCode == 422 -> RemoteError.Rejected(message ?: "Permintaan ditolak server.", code)
        code != null && code in ServerErrorCodes.REJECTION_CODES -> RemoteError.Rejected(message ?: "Permintaan ditolak server.", code)
        else -> RemoteError.Unexpected(httpCode, parseRetryAfterSeconds(retryAfterHeader, now) ?: body?.retryAfterSecondsOrNull())
    }
}

private fun ErrorResponseDto.codeOrNull(): String? =
    (code as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

/** `retry_after` di badan galat (detik bulat, tidak negatif); bentuk lain dianggap tidak ada, tidak pernah melempar. */
private fun ErrorResponseDto.retryAfterSecondsOrNull(): Long? =
    (retryAfter as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 0 }

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
