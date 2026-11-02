package com.roziqrizal.rizqflow.data.remote

import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import com.roziqrizal.rizqflow.domain.sync.RoomMemberInfo
import com.roziqrizal.rizqflow.domain.sync.RoomMembershipRemote
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Implementasi [RoomMembershipRemote] sungguhan lewat [RizqflowApiService] (Tahap 9). Semua galat lewat [mapHttpError],
 * yang membaca `code` dari badan galat server: 403 `sync_required` (atau 403 tanpa kode) menjadi `SyncRequired`
 * (paywall Sync), 403 `not_room_member`/`not_room_owner` dan 404 `not_room_member`/`member_not_found` serta 422
 * `owner_cannot_leave`/`owner_cannot_be_removed` menjadi `Rejected` berkode, 429 menjadi `Unexpected(429, retryAfter)`.
 */
class RetrofitRoomMembershipRemote(private val service: RizqflowApiService) : RoomMembershipRemote {
    override suspend fun join(apiToken: String, roomId: String, inviteHash: String?): RemoteResult<Unit> =
        safeCall {
            // Tanpa hash badannya kosong, persis seperti sebelum undangan terdaftar (server tetap menerima anggota lama).
            val body = inviteHash?.let { jsonBody(RoomInviteHashDto.serializer(), RoomInviteHashDto(it)) }
                ?: ByteArray(0).toRequestBody(null)
            val response = service.joinRoom(bearer(apiToken), roomId, body)
            if (response.isSuccessful) RemoteResult.Success(Unit) else response.toFailure()
        }

    override suspend fun registerInvite(apiToken: String, roomId: String, inviteHash: String): RemoteResult<Unit> =
        safeCall {
            val response = service.registerRoomInvite(bearer(apiToken), roomId, jsonBody(RoomInviteHashDto.serializer(), RoomInviteHashDto(inviteHash)))
            if (response.isSuccessful) RemoteResult.Success(Unit) else response.toFailure()
        }

    override suspend fun members(apiToken: String, roomId: String): RemoteResult<List<RoomMemberInfo>> =
        safeCall {
            val response = service.roomMembers(bearer(apiToken), roomId)
            val body = response.body()
            if (response.isSuccessful && body != null) {
                val decoded = body.decodeJson(RoomMembersResponseDto.serializer())
                RemoteResult.Success(decoded.members.map { RoomMemberInfo(it.name, it.email, it.you, it.owner, it.userId, it.memberId, it.inviteHash) })
            } else {
                response.toFailure()
            }
        }

    override suspend fun remove(apiToken: String, roomId: String, userId: Long): RemoteResult<Unit> =
        safeCall {
            val response = service.removeRoomMember(bearer(apiToken), roomId, userId)
            if (response.isSuccessful) RemoteResult.Success(Unit) else response.toFailure()
        }

    override suspend fun leave(apiToken: String, roomId: String): RemoteResult<Unit> =
        safeCall {
            val response = service.leaveRoom(bearer(apiToken), roomId)
            if (response.isSuccessful) RemoteResult.Success(Unit) else response.toFailure()
        }

    override suspend fun transferOwnership(apiToken: String, roomId: String, targetUserId: Long): RemoteResult<Unit> =
        safeCall {
            val response = service.makeRoomOwner(bearer(apiToken), roomId, targetUserId)
            if (response.isSuccessful) RemoteResult.Success(Unit) else response.toFailure()
        }
}
