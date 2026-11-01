package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.auth.runSuspend
import com.roziqrizal.rizqflow.domain.model.RoomId
import kotlin.test.Test
import kotlin.test.assertEquals

/** Gerbang kode pemulihan dan pemetaan hasil untuk langkah sync otomatis (Tahap 6, keputusan #14). */
class WorkspaceAutoSyncStepsTest {
    private class Calls {
        var personal = 0
        val rooms = mutableListOf<String>()
    }

    private fun steps(
        calls: Calls,
        configured: Boolean,
        personal: PersonalSyncResult = PersonalSyncResult.Success(pushed = 4, applied = 2, skipped = 0, pushGated = false),
        room: SyncResult = SyncResult.Success(pushed = 1, applied = 1, skipped = 0),
    ) = WorkspaceAutoSyncSteps(
        recoveryConfigured = { configured },
        personalSync = { calls.personal++; personal },
        activeSharedRoomIds = { listOf(RoomId("r1")) },
        sharedRoomSync = { calls.rooms += it.value; room },
    )

    @Test
    fun `sebelum kode pemulihan terkonfigurasi cakupan pribadi tidak mengirim request apa pun`() {
        val calls = Calls()

        val outcome = runSuspend { steps(calls, configured = false).personal() }

        assertEquals(0, calls.personal, "tidak push dan tidak pull: kursor tidak boleh maju melewati baris yang belum bisa dibuka")
        assertEquals(ScopeSyncOutcome.Done(0, 0, pushWaitsForRecovery = true), outcome)
    }

    @Test
    fun `setelah terkonfigurasi cakupan pribadi berjalan dan hasilnya dipetakan`() {
        val calls = Calls()

        val outcome = runSuspend { steps(calls, configured = true).personal() }

        assertEquals(1, calls.personal)
        assertEquals(ScopeSyncOutcome.Done(pushed = 4, applied = 2, pushWaitsForRecovery = false), outcome)
    }

    @Test
    fun `kegagalan dan belum masuk dipetakan apa adanya`() {
        val failed = PersonalSyncResult.Failed(RemoteError.Unreachable)

        assertEquals(ScopeSyncOutcome.Failed(RemoteError.Unreachable), runSuspend { steps(Calls(), true, personal = failed).personal() })
        assertEquals(ScopeSyncOutcome.NotSignedIn, runSuspend { steps(Calls(), true, personal = PersonalSyncResult.NotSignedIn).personal() })
        assertEquals(
            ScopeSyncOutcome.Failed(RemoteError.SyncRequired),
            runSuspend { steps(Calls(), true, room = SyncResult.Failed(RemoteError.SyncRequired)).sharedRoom(RoomId("r1")) },
        )
    }

    @Test
    fun `saklar hanya Wi-Fi menahan jaringan berbayar saja, tanpa jaringan selalu offline`() {
        fun check(wifiOnly: Boolean, kind: NetworkKind) = AutoSyncNetworkPolicy.check(wifiOnly, kind)

        assertEquals(NetworkCheck.WIFI_ONLY_BLOCKED, check(true, NetworkKind.METERED))
        assertEquals(NetworkCheck.OK, check(false, NetworkKind.METERED))
        assertEquals(NetworkCheck.OK, check(true, NetworkKind.UNMETERED))
        assertEquals(NetworkCheck.OK, check(false, NetworkKind.UNMETERED))
        assertEquals(NetworkCheck.NO_NETWORK, check(true, NetworkKind.NONE))
        assertEquals(NetworkCheck.NO_NETWORK, check(false, NetworkKind.NONE))
    }

    @Test
    fun `ruang bersama tidak bergantung pada kode pemulihan`() {
        val calls = Calls()
        val workspace = steps(calls, configured = false)

        val rooms = runSuspend { workspace.activeSharedRooms() }
        val outcome = runSuspend { workspace.sharedRoom(RoomId("r1")) }

        assertEquals(listOf(RoomId("r1")), rooms)
        assertEquals(listOf("r1"), calls.rooms)
        assertEquals(ScopeSyncOutcome.Done(pushed = 1, applied = 1), outcome)
    }
}
