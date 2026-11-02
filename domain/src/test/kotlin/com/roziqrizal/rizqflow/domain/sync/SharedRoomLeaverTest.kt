package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.model.RoomId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Anggota keluar dari ruang bersama (Tahap 9): urutan "cabut di server, baru tandai lokal", tanpa mock berlebihan —
 * remote dan penanda lokal adalah catatan sederhana, bukan kerangka mock.
 */
class SharedRoomLeaverTest {
    private val room = RoomId("keluarga")

    /** Remote palsu yang menjawab [answer] dan mencatat panggilan `leave`. */
    private class RecordingRemote(var answer: RemoteResult<Unit>) : RoomMembershipRemote {
        val leaveCalls = mutableListOf<Pair<String, String>>()

        override suspend fun join(apiToken: String, roomId: String, inviteHash: String?): RemoteResult<Unit> = error("tidak dipakai")

        override suspend fun registerInvite(apiToken: String, roomId: String, inviteHash: String): RemoteResult<Unit> = error("tidak dipakai")

        override suspend fun members(apiToken: String, roomId: String): RemoteResult<List<RoomMemberInfo>> = error("tidak dipakai")

        override suspend fun remove(apiToken: String, roomId: String, userId: Long): RemoteResult<Unit> = error("tidak dipakai")

        override suspend fun transferOwnership(apiToken: String, roomId: String, targetUserId: Long): RemoteResult<Unit> =
            error("tidak dipakai")

        override suspend fun leave(apiToken: String, roomId: String): RemoteResult<Unit> {
            leaveCalls += apiToken to roomId
            return answer
        }
    }

    private val marked = mutableListOf<RoomId>()

    private fun leaver(remote: RoomMembershipRemote) = SharedRoomLeaver(remote) { marked += it }

    private fun rejected(code: String) = RemoteResult.Failure(RemoteError.Rejected("pesan server mentah", code))

    @Test
    fun `sukses mencabut di server lalu menandai ruang keluar di perangkat`() = runBlocking {
        val remote = RecordingRemote(RemoteResult.Success(Unit))

        val outcome = leaver(remote).leave("token", room)

        assertEquals(LeaveRoomOutcome.Left, outcome)
        assertEquals(listOf("token" to "keluarga"), remote.leaveCalls)
        assertEquals(listOf(room), marked)
    }

    @Test
    fun `server menjawab bukan anggota tetap menandai keluar, supaya ulangan setelah jawaban hilang selesai`() = runBlocking {
        val remote = RecordingRemote(rejected(ServerErrorCodes.NOT_ROOM_MEMBER))

        val outcome = leaver(remote).leave("token", room)

        assertEquals(LeaveRoomOutcome.NoLongerMember, outcome)
        assertEquals(listOf(room), marked)
    }

    @Test
    fun `pemilik ditolak server dan tidak ada yang ditandai`() = runBlocking {
        val remote = RecordingRemote(rejected(ServerErrorCodes.OWNER_CANNOT_LEAVE))

        val outcome = leaver(remote).leave("token", room)

        assertEquals(LeaveRoomOutcome.OwnerCannotLeave, outcome)
        assertTrue(marked.isEmpty())
    }

    @Test
    fun `galat lain tidak menandai apa pun dan diteruskan apa adanya`() = runBlocking {
        val errors = listOf(
            RemoteError.Unreachable,
            RemoteError.Unauthorized,
            RemoteError.SyncRequired,
            RemoteError.Unexpected(429, 30L),
            RemoteError.Unexpected(500),
            RemoteError.Unexpected(404),
            RemoteError.Rejected("x", ServerErrorCodes.INVALID_ROOM_ID),
            RemoteError.Rejected("x", ServerErrorCodes.NOT_ROOM_OWNER),
            RemoteError.Rejected("x"),
        )
        for (error in errors) {
            val outcome = leaver(RecordingRemote(RemoteResult.Failure(error))).leave("token", room)

            assertEquals(LeaveRoomOutcome.Failed(error), outcome, "galat $error")
        }
        assertTrue(marked.isEmpty(), "tidak ada ruang yang boleh ditandai keluar bila server belum mencabut keanggotaan")
    }

    @Test
    fun `penandaan lokal tidak hilang bila pemanggil dibatalkan setelah server berhasil`() = runBlocking {
        val inRemote = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val slowRemote = object : RoomMembershipRemote {
            override suspend fun join(apiToken: String, roomId: String, inviteHash: String?): RemoteResult<Unit> = error("tidak dipakai")

        override suspend fun registerInvite(apiToken: String, roomId: String, inviteHash: String): RemoteResult<Unit> = error("tidak dipakai")

            override suspend fun members(apiToken: String, roomId: String): RemoteResult<List<RoomMemberInfo>> = error("tidak dipakai")

            override suspend fun remove(apiToken: String, roomId: String, userId: Long): RemoteResult<Unit> = error("tidak dipakai")

            override suspend fun transferOwnership(apiToken: String, roomId: String, targetUserId: Long): RemoteResult<Unit> =
                error("tidak dipakai")

            override suspend fun leave(apiToken: String, roomId: String): RemoteResult<Unit> {
                inRemote.complete(Unit)
                release.await()
                return RemoteResult.Success(Unit)
            }
        }

        val job = async(start = CoroutineStart.UNDISPATCHED) { leaver(slowRemote).leave("token", room) }
        inRemote.await()
        // Layar ditutup (cakupan dibatalkan) selagi jawaban server belum tiba; jawaban lalu datang.
        val cancelling = async { job.cancelAndJoin() }
        release.complete(Unit)
        cancelling.await()

        assertEquals(listOf(room), marked)
    }
}
