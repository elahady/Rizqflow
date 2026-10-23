package com.roziqrizal.rizqflow.data.remote

import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import com.roziqrizal.rizqflow.domain.sync.SyncLogEntryDownload
import com.roziqrizal.rizqflow.domain.sync.SyncLogEntryUpload
import com.roziqrizal.rizqflow.domain.sync.SyncPullResult
import com.roziqrizal.rizqflow.domain.sync.SyncPushResult
import com.roziqrizal.rizqflow.domain.sync.SyncRemote

/** Implementasi [SyncRemote] sungguhan lewat [RizqflowApiService] (Tahap 9). */
class RetrofitSyncRemote(private val service: RizqflowApiService) : SyncRemote {
    override suspend fun push(apiToken: String, entries: List<SyncLogEntryUpload>): RemoteResult<SyncPushResult> =
        safeCall {
            val request = jsonBody(PushRequestDto.serializer(), PushRequestDto(entries.map { it.toDto() }))
            val response = service.push(bearer(apiToken), request)
            val body = response.body()
            if (response.isSuccessful && body != null) {
                val decoded = body.decodeJson(PushResponseDto.serializer())
                RemoteResult.Success(SyncPushResult(ids = decoded.ids, cursor = decoded.cursor))
            } else {
                response.toFailure()
            }
        }

    override suspend fun pull(apiToken: String, after: Long, limit: Int): RemoteResult<SyncPullResult> =
        safeCall {
            val response = service.pull(bearer(apiToken), after, limit)
            val body = response.body()
            if (response.isSuccessful && body != null) {
                val decoded = body.decodeJson(PullResponseDto.serializer())
                RemoteResult.Success(
                    SyncPullResult(
                        entries = decoded.entries.map { it.toDomain() },
                        nextAfter = decoded.nextAfter,
                        hasMore = decoded.hasMore,
                    ),
                )
            } else {
                response.toFailure()
            }
        }
}

private fun SyncLogEntryUpload.toDto() = SyncEntryUploadDto(
    roomId = roomId,
    recordType = recordType,
    recordId = recordId,
    deviceId = deviceId,
    payload = payload,
    deleted = deleted,
    changedAt = changedAt,
)

private fun SyncEntryDownloadDto.toDomain() = SyncLogEntryDownload(
    id = id,
    roomId = roomId,
    recordType = recordType,
    recordId = recordId,
    deviceId = deviceId,
    payload = payload,
    deleted = deleted,
    changedAt = changedAt,
)
