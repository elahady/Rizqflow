package com.roziqrizal.rizqflow.data.remote

import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import com.roziqrizal.rizqflow.domain.sync.RoomMemberInfo
import com.roziqrizal.rizqflow.domain.sync.RoomMembershipRemote

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
                RemoteResult.Success(decoded.members.map { RoomMemberInfo(it.name, it.email, it.you) })
            } else {
                response.toFailure()
            }
        }
}
