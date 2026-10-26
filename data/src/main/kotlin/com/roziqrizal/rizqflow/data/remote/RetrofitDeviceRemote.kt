package com.roziqrizal.rizqflow.data.remote

import com.roziqrizal.rizqflow.domain.sync.DeviceRemote
import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import com.roziqrizal.rizqflow.domain.sync.ServerDevice
import kotlinx.serialization.SerializationException

/**
 * Implementasi [DeviceRemote] sungguhan lewat [RizqflowApiService] (Tahap 14, "Keluar dari semua perangkat").
 * Jawaban daftar hanya metadata; waktu yang tak terbaca menjadi null (lihat [parseExpiresAt]) dan tidak menggagalkan daftar.
 * Badan 200 yang tidak sesuai kontrak menjadi [RemoteError.Unexpected], bukan crash.
 */
class RetrofitDeviceRemote(private val service: RizqflowApiService) : DeviceRemote {
    override suspend fun listDevices(apiToken: String): RemoteResult<List<ServerDevice>> =
        safeCall {
            val response = service.listTokens(bearer(apiToken))
            val body = response.body()
            if (response.isSuccessful && body != null) {
                try {
                    val decoded = body.decodeJson(DeviceListResponseDto.serializer())
                    RemoteResult.Success(decoded.devices.map { it.toDomain() })
                } catch (_: SerializationException) {
                    RemoteResult.Failure(RemoteError.Unexpected(response.code()))
                }
            } else {
                response.toFailure()
            }
        }

    override suspend fun revokeAllDevices(apiToken: String): RemoteResult<Unit> =
        safeCall {
            val response = service.revokeAllTokens(bearer(apiToken))
            if (response.isSuccessful) RemoteResult.Success(Unit) else response.toFailure()
        }
}

/** Nama kosong dari server dianggap tidak ada, supaya tampilan jatuh ke label bawaan. */
internal fun DeviceDto.toDomain(): ServerDevice =
    ServerDevice(
        id = id,
        label = label?.takeIf { it.isNotBlank() } ?: "Perangkat lain",
        deviceName = deviceName?.takeIf { it.isNotBlank() },
        createdAt = parseExpiresAt(createdAt),
        lastUsedAt = parseExpiresAt(lastUsedAt),
        expiresAt = parseExpiresAt(expiresAt),
        current = current,
    )
