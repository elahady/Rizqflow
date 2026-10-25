package com.roziqrizal.rizqflow.data.remote

import com.roziqrizal.rizqflow.domain.sync.RECOVERY_KEY_VERSION
import com.roziqrizal.rizqflow.domain.sync.RecoveryKeyLookup
import com.roziqrizal.rizqflow.domain.sync.RecoveryKeyReceipt
import com.roziqrizal.rizqflow.domain.sync.RecoveryKeyRemote
import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/**
 * Implementasi [RecoveryKeyRemote] sungguhan lewat [RizqflowApiService] (Tahap 13). Badan persis kontrak:
 * `wrapped_key` base64 STANDAR (dengan padding, bukan URL-safe) dan `version` 1. Server tidak pernah melihat
 * kode 24 kata atau kunci terbaca; yang dikirim hanya bungkusan hasil `AccountKeyRecoveryCrypto.wrap`.
 *
 * - 404 pada GET adalah hasil biasa ([RecoveryKeyLookup.NotFound]), bukan galat, HANYA bila badannya memuat
 *   penanda kontrak `"code":"no_recovery_key"` (kontrak disepakati dengan server: `{"message":"Belum ada kode
 *   pemulihan","code":"no_recovery_key"}`). 404 lain (rute belum dideploy, proxy, server lama) menjadi
 *   [RemoteError.Unexpected] dengan kode 404, supaya UI bisa berkata jujur "server belum siap" alih-alih
 *   menuduh "belum ada kode" (yang di perangkat baru menyesatkan: kode mungkin ada, servernya yang belum siap).
 * - 403 (PUT tanpa langganan Sync) menjadi [RemoteError.SyncRequired] lewat [mapHttpError]; 422 [RemoteError.Rejected].
 * - Badan 200 yang tidak sesuai kontrak (bukan JSON, base64 rusak) menjadi [RemoteError.Unexpected], bukan crash.
 */
class RetrofitRecoveryKeyRemote(private val service: RizqflowApiService) : RecoveryKeyRemote {
    override suspend fun put(apiToken: String, wrappedKey: ByteArray): RemoteResult<RecoveryKeyReceipt> =
        safeCall {
            val request = jsonBody(
                RecoveryKeyPutDto.serializer(),
                RecoveryKeyPutDto(wrappedKey = Base64.getEncoder().encodeToString(wrappedKey), version = RECOVERY_KEY_VERSION),
            )
            val response = service.putRecoveryKey(bearer(apiToken), request)
            val body = response.body()
            if (response.isSuccessful && body != null) {
                try {
                    val decoded = body.decodeJson(RecoveryKeyReceiptDto.serializer())
                    RemoteResult.Success(RecoveryKeyReceipt(decoded.version, decoded.updatedAt))
                } catch (_: SerializationException) {
                    RemoteResult.Failure(RemoteError.Unexpected(response.code()))
                }
            } else {
                response.toFailure()
            }
        }

    override suspend fun get(apiToken: String): RemoteResult<RecoveryKeyLookup> =
        safeCall {
            val response = service.getRecoveryKey(bearer(apiToken))
            val body = response.body()
            when {
                response.code() == 404 -> {
                    val raw = response.errorBody()?.use { it.string() }
                    if (isNoRecoveryKeyMarker(raw)) {
                        RemoteResult.Success(RecoveryKeyLookup.NotFound)
                    } else {
                        RemoteResult.Failure(RemoteError.Unexpected(404))
                    }
                }

                response.isSuccessful && body != null -> try {
                    val decoded = body.decodeJson(RecoveryKeyDto.serializer())
                    RemoteResult.Success(
                        RecoveryKeyLookup.Found(
                            wrappedKey = Base64.getDecoder().decode(decoded.wrappedKey),
                            version = decoded.version,
                            updatedAt = decoded.updatedAt,
                        ),
                    )
                } catch (_: SerializationException) {
                    RemoteResult.Failure(RemoteError.Unexpected(response.code()))
                } catch (_: IllegalArgumentException) {
                    // Base64 rusak.
                    RemoteResult.Failure(RemoteError.Unexpected(response.code()))
                }

                else -> response.toFailure()
            }
        }

    override suspend fun delete(apiToken: String): RemoteResult<Unit> =
        safeCall {
            val response = service.deleteRecoveryKey(bearer(apiToken))
            if (response.isSuccessful) RemoteResult.Success(Unit) else response.toFailure()
        }
}

/** Penanda kontrak pada 404 aplikasi: server hanya menulis `code` ini saat memang belum ada salinan. */
private const val NO_RECOVERY_KEY_CODE = "no_recovery_key"

@Serializable
private data class RecoveryErrorCodeDto(val code: String? = null)

/** Apakah [rawErrorBody] adalah badan 404 aplikasi dengan `code == "no_recovery_key"`. Badan kosong, bukan JSON, atau tanpa `code` = bukan. */
private fun isNoRecoveryKeyMarker(rawErrorBody: String?): Boolean {
    if (rawErrorBody.isNullOrBlank()) return false
    val code = runCatching { apiJson.decodeFromString(RecoveryErrorCodeDto.serializer(), rawErrorBody).code }.getOrNull()
    return code == NO_RECOVERY_KEY_CODE
}
