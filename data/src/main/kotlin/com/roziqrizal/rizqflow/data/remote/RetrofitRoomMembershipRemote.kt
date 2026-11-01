package com.roziqrizal.rizqflow.data.remote

import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import com.roziqrizal.rizqflow.domain.sync.RoomMemberInfo
import com.roziqrizal.rizqflow.domain.sync.RoomMembershipRemote
import kotlinx.serialization.Serializable

/** Kode galat 403 `DELETE /rooms/{roomId}/members/{userId}` saat pemanggil bukan pemilik ruang. */
internal const val NOT_ROOM_OWNER_CODE = "not_room_owner"

/** Implementasi [RoomMembershipRemote] sungguhan lewat [RizqflowApiService] (Tahap 9). */
class RetrofitRoomMembershipRemote(private val service: RizqflowApiService) : RoomMembershipRemote {
    override suspend fun join(apiToken: String, roomId: String): RemoteResult<Unit> =
        safeCall {
            val response = service.joinRoom(bearer(apiToken), roomId)
            if (response.isSuccessful) RemoteResult.Success(Unit) else response.toFailure()
        }

    override suspend fun members(apiToken: String, roomId: String): RemoteResult<List<RoomMemberInfo>> =
        safeCall {
            val response = service.roomMembers(bearer(apiToken), roomId)
            val body = response.body()
            if (response.isSuccessful && body != null) {
                val decoded = body.decodeJson(RoomMembersResponseDto.serializer())
                RemoteResult.Success(decoded.members.map { RoomMemberInfo(it.name, it.email, it.you, it.owner, it.userId) })
            } else {
                response.toFailure()
            }
        }

    /**
     * 403 umumnya berarti "butuh Sync" ([RemoteError.SyncRequired], membuka paywall). Di sini 403 bisa juga berarti
     * "bukan pemilik ruang" (`code` = [NOT_ROOM_OWNER_CODE]); itu dijawab [RemoteError.Rejected] supaya pengguna yang
     * sudah berlangganan tidak diarahkan ke paywall.
     */
    override suspend fun remove(apiToken: String, roomId: String, userId: Long): RemoteResult<Unit> =
        safeCall {
            val response = service.removeRoomMember(bearer(apiToken), roomId, userId)
            if (response.isSuccessful) return@safeCall RemoteResult.Success(Unit)
            val raw = response.errorBody()?.string()
            val error = raw?.let { runCatching { apiJson.decodeFromString(RoomRemoveErrorDto.serializer(), it) }.getOrNull() }
            if (response.code() == 403 && error?.code == NOT_ROOM_OWNER_CODE) {
                RemoteResult.Failure(RemoteError.Rejected(error.message ?: "Hanya pemilik ruang yang bisa mengeluarkan anggota."))
            } else {
                RemoteResult.Failure(mapHttpError(response.code(), raw))
            }
        }
}

@Serializable
private data class RoomRemoveErrorDto(val message: String? = null, val code: String? = null)
