package com.roziqrizal.rizqflow.data.remote

import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import com.roziqrizal.rizqflow.domain.sync.ServerSyncDevice
import com.roziqrizal.rizqflow.domain.sync.ServerSyncHistory
import com.roziqrizal.rizqflow.domain.sync.SyncCompactionInfo
import com.roziqrizal.rizqflow.domain.sync.SyncHistoryRemote
import com.roziqrizal.rizqflow.domain.sync.SyncQuotaUsage
import kotlinx.serialization.SerializationException

/**
 * Implementasi [SyncHistoryRemote] sungguhan lewat [RizqflowApiService] (`GET /api/sync/history`, hanya metadata).
 * Waktu yang tak terbaca menjadi null dan tidak menggagalkan jawaban; badan 200 yang tidak sesuai kontrak menjadi
 * [RemoteError.Unexpected], bukan crash. Pola sama dengan [RetrofitDeviceRemote].
 */
class RetrofitSyncHistoryRemote(private val service: RizqflowApiService) : SyncHistoryRemote {
    override suspend fun history(apiToken: String): RemoteResult<ServerSyncHistory> =
        safeCall {
            val response = service.syncHistory(bearer(apiToken))
            val body = response.body()
            if (response.isSuccessful && body != null) {
                try {
                    RemoteResult.Success(body.decodeJson(SyncHistoryResponseDto.serializer()).toDomain())
                } catch (_: SerializationException) {
                    RemoteResult.Failure(RemoteError.Unexpected(response.code()))
                }
            } else {
                response.toFailure()
            }
        }
}

internal fun SyncHistoryResponseDto.toDomain(): ServerSyncHistory =
    ServerSyncHistory(
        devices = devices.map {
            ServerSyncDevice(
                deviceId = it.deviceId,
                rows = it.rows,
                lastPushAt = parseExpiresAt(it.lastPushAt),
                lastPullAt = parseExpiresAt(it.lastPullAt),
                cursor = it.cursor,
                active = it.active,
            )
        },
        quota = SyncQuotaUsage(rows = quota.rows, bytes = quota.bytes, maxRows = quota.maxRows, maxBytes = quota.maxBytes),
        compaction = SyncCompactionInfo(
            lastRunAt = parseExpiresAt(compaction.lastRunAt),
            removedVersions = compaction.lastRemovedVersions,
            removedTombstones = compaction.lastRemovedTombstones,
        ),
        serverTimeMs = serverTimeMs,
    )
