package com.roziqrizal.rizqflow.sync

import com.roziqrizal.rizqflow.domain.sync.AccountRemote
import com.roziqrizal.rizqflow.domain.sync.ApiAccount
import com.roziqrizal.rizqflow.domain.sync.DeviceRemote
import com.roziqrizal.rizqflow.domain.sync.PurchaseClaim
import com.roziqrizal.rizqflow.domain.sync.PurchaseClaimResponse
import com.roziqrizal.rizqflow.domain.sync.PurchaseRemote
import com.roziqrizal.rizqflow.domain.sync.RecoveryKeyLookup
import com.roziqrizal.rizqflow.domain.sync.RecoveryKeyReceipt
import com.roziqrizal.rizqflow.domain.sync.RecoveryKeyRemote
import com.roziqrizal.rizqflow.domain.sync.RefreshedToken
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import com.roziqrizal.rizqflow.domain.sync.RoomMemberInfo
import com.roziqrizal.rizqflow.domain.sync.RoomMembershipRemote
import com.roziqrizal.rizqflow.domain.sync.ServerDevice
import com.roziqrizal.rizqflow.domain.sync.SyncLogEntryUpload
import com.roziqrizal.rizqflow.domain.sync.SyncPullResult
import com.roziqrizal.rizqflow.domain.sync.SyncPushResult
import com.roziqrizal.rizqflow.domain.sync.SyncRemote

/*
 * Pembungkus remote yang menyalurkan jawaban 401 ke satu tempat, [ServerSession.observe]. Pembungkus tidak
 * mengubah jawaban: pemanggil tetap menerima RemoteResult yang sama (Failure(Unauthorized)) dan tidak perlu
 * tahu soal sesi. Dirakit hanya lewat ServerSession.guard di AccountWorkspace.
 */

internal class GuardedAccountRemote(
    private val inner: AccountRemote,
    private val session: ServerSession,
) : AccountRemote {
    // Sengaja TIDAK dibungkus: 401 dari masuk berarti ID token Google ditolak, bukan sesi server yang berakhir
    // (belum ada sesi), dan token yang tersimpan (bila ada) tidak ikut bermasalah.
    override suspend fun loginWithGoogle(idToken: String, deviceId: String): RemoteResult<ApiAccount> =
        inner.loginWithGoogle(idToken, deviceId)

    // Sama seperti Google: masuk email+sandi belum punya sesi (401 berarti sandi salah, bukan sesi berakhir).
    override suspend fun loginWithEmail(email: String, password: String, deviceId: String): RemoteResult<ApiAccount> =
        inner.loginWithEmail(email, password, deviceId)

    override suspend fun refresh(apiToken: String): RemoteResult<RefreshedToken> =
        session.observe(apiToken, inner.refresh(apiToken))

    override suspend fun logout(apiToken: String): RemoteResult<Unit> =
        session.observe(apiToken, inner.logout(apiToken))

    override suspend fun deleteAccount(apiToken: String): RemoteResult<Unit> =
        session.observe(apiToken, inner.deleteAccount(apiToken))
}

internal class GuardedSyncRemote(
    private val inner: SyncRemote,
    private val session: ServerSession,
) : SyncRemote {
    override suspend fun push(apiToken: String, entries: List<SyncLogEntryUpload>): RemoteResult<SyncPushResult> =
        session.observe(apiToken, inner.push(apiToken, entries))

    override suspend fun pull(apiToken: String, deviceId: String, after: Long, limit: Int): RemoteResult<SyncPullResult> =
        session.observe(apiToken, inner.pull(apiToken, deviceId, after, limit))
}

internal class GuardedRoomMembershipRemote(
    private val inner: RoomMembershipRemote,
    private val session: ServerSession,
) : RoomMembershipRemote {
    override suspend fun join(apiToken: String, roomId: String): RemoteResult<Unit> =
        session.observe(apiToken, inner.join(apiToken, roomId))

    override suspend fun members(apiToken: String, roomId: String): RemoteResult<List<RoomMemberInfo>> =
        session.observe(apiToken, inner.members(apiToken, roomId))
}

internal class GuardedPurchaseRemote(
    private val inner: PurchaseRemote,
    private val session: ServerSession,
) : PurchaseRemote {
    override suspend fun claim(apiToken: String, claims: List<PurchaseClaim>): RemoteResult<PurchaseClaimResponse> =
        session.observe(apiToken, inner.claim(apiToken, claims))
}

internal class GuardedDeviceRemote(
    private val inner: DeviceRemote,
    private val session: ServerSession,
) : DeviceRemote {
    override suspend fun listDevices(apiToken: String): RemoteResult<List<ServerDevice>> =
        session.observe(apiToken, inner.listDevices(apiToken))

    override suspend fun revokeAllDevices(apiToken: String): RemoteResult<Unit> =
        session.observe(apiToken, inner.revokeAllDevices(apiToken))
}

internal class GuardedRecoveryKeyRemote(
    private val inner: RecoveryKeyRemote,
    private val session: ServerSession,
) : RecoveryKeyRemote {
    override suspend fun put(apiToken: String, wrappedKey: ByteArray): RemoteResult<RecoveryKeyReceipt> =
        session.observe(apiToken, inner.put(apiToken, wrappedKey))

    override suspend fun get(apiToken: String): RemoteResult<RecoveryKeyLookup> =
        session.observe(apiToken, inner.get(apiToken))

    override suspend fun delete(apiToken: String): RemoteResult<Unit> =
        session.observe(apiToken, inner.delete(apiToken))
}
