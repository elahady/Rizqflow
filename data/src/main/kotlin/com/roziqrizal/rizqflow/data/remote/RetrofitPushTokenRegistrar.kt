package com.roziqrizal.rizqflow.data.remote

import com.roziqrizal.rizqflow.domain.sync.PushTokenRegistrar
import com.roziqrizal.rizqflow.domain.sync.PushTokenRegistration
import com.roziqrizal.rizqflow.domain.sync.RemoteResult

/**
 * Implementasi [PushTokenRegistrar] lewat [RizqflowApiService] (wiring FCM 2026-11-01). Kontrak di
 * docs/server-sync.md bagian "Push notifikasi": `POST /push/tokens` dan `DELETE /push/tokens/{device_id}`. Jawaban
 * 2xx mana pun dianggap berhasil (badannya tidak dibaca); kode lain lewat pemetaan galat yang sama dengan remote lain.
 */
class RetrofitPushTokenRegistrar(private val service: RizqflowApiService) : PushTokenRegistrar {
    override suspend fun register(apiToken: String, registration: PushTokenRegistration): RemoteResult<Unit> =
        safeCall {
            val body = jsonBody(
                PushTokenRequestDto.serializer(),
                PushTokenRequestDto(registration.token, registration.deviceId, registration.platform),
            )
            val response = service.registerPushToken(bearer(apiToken), body)
            if (response.isSuccessful) {
                response.body()?.close()
                RemoteResult.Success(Unit)
            } else {
                response.toFailure()
            }
        }

    override suspend fun revoke(apiToken: String, deviceId: String): RemoteResult<Unit> =
        safeCall {
            val response = service.revokePushToken(bearer(apiToken), deviceId)
            if (response.isSuccessful) {
                response.body()?.close()
                RemoteResult.Success(Unit)
            } else {
                response.toFailure()
            }
        }
}
