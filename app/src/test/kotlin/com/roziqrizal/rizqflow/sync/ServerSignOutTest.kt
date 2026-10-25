package com.roziqrizal.rizqflow.sync

import com.roziqrizal.rizqflow.domain.sync.AccountRemote
import com.roziqrizal.rizqflow.domain.sync.ApiAccount
import com.roziqrizal.rizqflow.domain.sync.RefreshedToken
import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [ServerSignOut]: keluar lokal tidak pernah menunggu jaringan (Tahap 12). */
class ServerSignOutTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    /** Remote yang logout-nya tidak pernah selesai (jaringan menggantung) sampai dibatalkan. */
    private class HangingRemote : AccountRemote {
        val started = CompletableDeferred<String>()
        var cancelled = false

        override suspend fun loginWithGoogle(idToken: String, deviceId: String): RemoteResult<ApiAccount> = error("tidak dipakai")

        override suspend fun loginWithEmail(email: String, password: String, deviceId: String): RemoteResult<ApiAccount> = error("tidak dipakai")

        override suspend fun refresh(apiToken: String): RemoteResult<RefreshedToken> = error("tidak dipakai")

        override suspend fun logout(apiToken: String): RemoteResult<Unit> {
            started.complete(apiToken)
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }

        override suspend fun deleteAccount(apiToken: String): RemoteResult<Unit> = error("tidak dipakai")
    }

    @Test
    fun `token lokal sudah bersih seketika walau jaringan menggantung`() = runBlocking {
        val store = FakeTokenStore("t-1")
        val session = ServerSession(store)
        val remote = HangingRemote()
        val signOut = ServerSignOut(scope, timeoutMillis = 60_000) // batas panjang: tes membuktikan tidak menunggu

        val job = signOut.signOut(session, remote)

        // Sudah kembali dan bersih padahal logout belum selesai (dan tidak akan selesai dalam waktu dekat).
        assertNull(store.token)
        assertTrue(session.needsReconnect.value)
        assertNotNull(job)
        assertTrue(job.isActive, "pencabutan di server masih berjalan di latar")
        assertEquals("t-1", withTimeout(2_000) { remote.started.await() })
        job.cancel()
    }

    @Test
    fun `pencabutan di server dihentikan setelah batas waktu pendek`() = runBlocking {
        val session = ServerSession(FakeTokenStore("t-1"))
        val remote = HangingRemote()
        val signOut = ServerSignOut(scope, timeoutMillis = 100)

        val job = signOut.signOut(session, remote)!!
        withTimeout(5_000) { job.join() }

        assertTrue(remote.cancelled, "panggilan jaringan dibatalkan ketika waktu habis")
    }

    @Test
    fun `logout memakai token yang tersimpan sebelum dibersihkan`() = runBlocking {
        val remote = FakeAccountRemote(valid = setOf("t-1"))
        val session = ServerSession(FakeTokenStore("t-1"))

        ServerSignOut(scope).signOut(session, session.guard(remote))!!.join()

        assertEquals(listOf("logout:t-1"), remote.calls)
        assertTrue("t-1" !in remote.valid, "token itu dicabut di server")
    }

    @Test
    fun `server menolak atau gangguan tidak mengubah apa pun dan token lokal tetap bersih`() = runBlocking {
        for (failure in listOf(RemoteError.Unreachable, RemoteError.Unexpected(503), RemoteError.Unauthorized)) {
            val store = FakeTokenStore("t-1")
            val session = ServerSession(store)
            val remote = FakeAccountRemote(valid = setOf("t-1")).apply { logoutResult = RemoteResult.Failure(failure) }

            ServerSignOut(scope).signOut(session, session.guard(remote))!!.join()

            assertNull(store.token, "token lokal bersih untuk $failure")
        }
    }

    @Test
    fun `putar token yang menyela saat keluar tidak meninggalkan token baru yatim di server`() = runBlocking {
        val store = FakeTokenStore("t-1")
        val session = ServerSession(store)
        val remote = FakeAccountRemote(valid = setOf("t-1"))
        var outcome: RotationOutcome? = null
        val interrupter = Thread {
            outcome = session.onRotated("t-1", com.roziqrizal.rizqflow.domain.sync.RefreshedToken("t-2", null), at = java.time.Instant.EPOCH)
        }
        // Putar token selesai di antara pembacaan token dan pembersihannya, dari thread lain.
        store.onRead = {
            interrupter.start()
            interrupter.join(300)
        }

        val job = ServerSignOut(scope).signOut(session, session.guard(remote))!!
        interrupter.join()
        job.join()

        assertEquals(RotationOutcome.SUPERSEDED, outcome, "baca-dan-hapus atomik: putar yang menyela melihat sesi sudah ditutup")
        assertNull(store.token)
    }

    @Test
    fun `tanpa token tidak ada panggilan jaringan sama sekali`() = runBlocking {
        val remote = FakeAccountRemote()
        val session = ServerSession(FakeTokenStore(null))

        val job = ServerSignOut(scope).signOut(session, remote)

        assertNull(job)
        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun `galat tak terduga dari remote tidak menjatuhkan aplikasi`() = runBlocking {
        val session = ServerSession(FakeTokenStore("t-1"))
        val boom = object : AccountRemote {
            override suspend fun loginWithGoogle(idToken: String, deviceId: String): RemoteResult<ApiAccount> = error("x")

            override suspend fun loginWithEmail(email: String, password: String, deviceId: String): RemoteResult<ApiAccount> = error("x")

            override suspend fun refresh(apiToken: String): RemoteResult<RefreshedToken> = error("x")

            override suspend fun logout(apiToken: String): RemoteResult<Unit> = throw IllegalStateException("meledak")

            override suspend fun deleteAccount(apiToken: String): RemoteResult<Unit> = error("x")
        }

        val job = ServerSignOut(scope).signOut(session, boom)!!
        job.join()

        assertTrue(job.isCompleted && !job.isCancelled, "pekerjaan latar selesai normal, galatnya ditelan")
    }
}
