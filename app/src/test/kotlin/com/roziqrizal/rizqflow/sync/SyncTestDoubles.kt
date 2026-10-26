package com.roziqrizal.rizqflow.sync

import com.roziqrizal.rizqflow.domain.sync.AccountRemote
import com.roziqrizal.rizqflow.domain.sync.ApiAccount
import com.roziqrizal.rizqflow.domain.sync.ApiTokenStore
import com.roziqrizal.rizqflow.domain.sync.PurchaseClaim
import com.roziqrizal.rizqflow.domain.sync.PurchaseClaimResponse
import com.roziqrizal.rizqflow.domain.sync.PurchaseRemote
import com.roziqrizal.rizqflow.domain.sync.RefreshedToken
import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import com.roziqrizal.rizqflow.domain.sync.RoomMemberInfo
import com.roziqrizal.rizqflow.domain.sync.RoomMembershipRemote
import com.roziqrizal.rizqflow.domain.sync.SyncLogEntryUpload
import com.roziqrizal.rizqflow.domain.sync.SyncPullResult
import com.roziqrizal.rizqflow.domain.sync.SyncPushResult
import com.roziqrizal.rizqflow.domain.sync.SyncRemote
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred

/** Penyimpanan token di memori. [persist] false meniru disk yang gagal menulis (token tetap hidup di memori). */
class FakeTokenStore(
    var token: String? = null,
    var expiry: Instant? = null,
    var persist: Boolean = true,
    var savedAt: Instant? = null,
    var everHeld: Boolean = token != null,
) : ApiTokenStore {
    var saves = 0

    /** Nama thread tiap [save]: tes memastikan penulisan ke disk tidak berjalan di thread pemanggil. */
    val saveThreads = mutableListOf<String>()

    /** Dipanggil di dalam [read] (setelah nilai dibaca) untuk meniru langkah lain yang menyela. Sekali pakai. */
    var onRead: (() -> Unit)? = null

    override fun read(): String? {
        val value = token
        onRead?.let {
            onRead = null
            it()
        }
        return value
    }

    override fun expiresAt() = if (token == null) null else expiry

    override fun lastSavedAt() = if (token == null) null else savedAt

    override fun hasEverHeldToken() = everHeld || token != null

    override fun save(token: String, expiresAt: Instant?): Boolean = save(token, expiresAt, null)

    override fun save(token: String, expiresAt: Instant?, savedAt: Instant?): Boolean {
        saves++
        saveThreads += Thread.currentThread().name
        this.token = token
        this.expiry = expiresAt
        this.savedAt = savedAt
        this.everHeld = true
        return persist
    }

    override fun clear() {
        if (token != null) everHeld = true
        token = null
        expiry = null
        savedAt = null
    }

    override fun forgetAccount() {
        token = null
        expiry = null
        savedAt = null
        everHeld = false
    }
}

/**
 * Server palsu untuk akun: meniru perilaku nyata bahwa putar mencabut token lama. [valid] adalah token yang
 * diterima server; [refresh] dengan token di luar [valid] dijawab 401. Setiap panggilan dicatat di [calls].
 */
class FakeAccountRemote(valid: Set<String> = emptySet()) : AccountRemote {
    val valid = valid.toMutableSet()
    val calls = mutableListOf<String>()

    /** Menahan jawaban putar sampai tes melepasnya (meniru jaringan lambat). */
    var refreshGate: CompletableDeferred<Unit>? = null
    var refreshFailure: RemoteError? = null
    var refreshExpiry: Instant? = Instant.parse("2027-02-01T00:00:00Z")
    private var counter = 0

    /** Jawaban tetap untuk logout/hapus akun; null = ikuti [valid]. */
    var logoutResult: RemoteResult<Unit>? = null
    var deleteResult: RemoteResult<Unit>? = null
    var loginResult: RemoteResult<ApiAccount> =
        RemoteResult.Success(ApiAccount("login-token", 1, "Roziq", "roziq@example.com", Instant.parse("2027-03-01T00:00:00Z")))

    /** Menahan jawaban masuk sampai tes melepasnya (meniru jaringan lambat). */
    var loginGate: CompletableDeferred<Unit>? = null

    override suspend fun loginWithGoogle(idToken: String, deviceId: String): RemoteResult<ApiAccount> {
        calls += "login"
        loginGate?.await()
        return loginResult
    }

    override suspend fun loginWithEmail(email: String, password: String, deviceId: String): RemoteResult<ApiAccount> {
        calls += "login-email"
        return loginResult
    }

    override suspend fun refresh(apiToken: String): RemoteResult<RefreshedToken> {
        calls += "refresh:$apiToken"
        refreshGate?.await()
        refreshFailure?.let { return RemoteResult.Failure(it) }
        if (apiToken !in valid) return RemoteResult.Failure(RemoteError.Unauthorized)
        valid -= apiToken
        val fresh = "rotated-${++counter}"
        valid += fresh
        return RemoteResult.Success(RefreshedToken(fresh, refreshExpiry))
    }

    override suspend fun logout(apiToken: String): RemoteResult<Unit> {
        calls += "logout:$apiToken"
        logoutResult?.let { return it }
        return if (valid.remove(apiToken)) RemoteResult.Success(Unit) else RemoteResult.Failure(RemoteError.Unauthorized)
    }

    override suspend fun deleteAccount(apiToken: String): RemoteResult<Unit> {
        calls += "delete:$apiToken"
        deleteResult?.let { return it }
        return if (apiToken in valid) RemoteResult.Success(Unit) else RemoteResult.Failure(RemoteError.Unauthorized)
    }
}

/** Remote sync/ruang/pembelian yang selalu menjawab [result] (kegagalan yang sama untuk semua pemanggilan). */
class FailingSyncRemote(private val error: RemoteError) : SyncRemote {
    override suspend fun push(apiToken: String, entries: List<SyncLogEntryUpload>): RemoteResult<SyncPushResult> =
        RemoteResult.Failure(error)

    override suspend fun pull(apiToken: String, deviceId: String, after: Long, limit: Int): RemoteResult<SyncPullResult> =
        RemoteResult.Failure(error)
}

class FailingRoomMembershipRemote(private val error: RemoteError) : RoomMembershipRemote {
    override suspend fun join(apiToken: String, roomId: String): RemoteResult<Unit> = RemoteResult.Failure(error)

    override suspend fun members(apiToken: String, roomId: String): RemoteResult<List<RoomMemberInfo>> =
        RemoteResult.Failure(error)
}

class FailingPurchaseRemote(private val error: RemoteError) : PurchaseRemote {
    override suspend fun claim(apiToken: String, claims: List<PurchaseClaim>): RemoteResult<PurchaseClaimResponse> =
        RemoteResult.Failure(error)
}
