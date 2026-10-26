package com.roziqrizal.rizqflow.sync

import com.roziqrizal.rizqflow.domain.sync.ApiAccount
import com.roziqrizal.rizqflow.domain.sync.RefreshedToken
import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import java.time.Instant
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Perbaikan hasil review independen Tahap 12: masuk yang tahan pembatalan, baca-dan-hapus token yang atomik,
 * keputusan Hapus akun, pemilihan sesi saat keluar dari mode demo, dan ulang sekali bila 401 karena token diputar.
 */
class SessionKetahananTest {
    private val now = Instant.parse("2026-12-01T00:00:00Z")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    // --- Masuk / sambungkan ulang tahan pembatalan (butir 2) ---

    @Test
    fun `masuk tetap menyimpan token walau pemanggil dibatalkan saat menunggu server`() = runBlocking {
        // Efek Compose dibatalkan begitu ID token dikonsumsi (kunci efek berubah) tepat saat permintaan terbang.
        val store = FakeTokenStore(token = null)
        val session = ServerSession(store, ioDispatcher = Dispatchers.Unconfined) // tanpa lompat thread: urutan tes deterministik
        val remote = FakeAccountRemote().apply { loginGate = CompletableDeferred() }

        val caller = launch { session.connect(remote, "id-token", "perangkat-1") }
        yield()
        assertEquals(listOf("login"), remote.calls, "permintaan sudah terbang")
        caller.cancel() // kunci efek berubah
        remote.loginGate!!.complete(Unit) // jawaban server datang sesudahnya
        caller.join()

        assertEquals("login-token", store.token, "token server harus tersimpan; ID token sekali pakai sudah terpakai")
        assertFalse(session.needsReconnect.value)
        assertFalse(session.connecting.value)
    }

    @Test
    fun `masuk menulis token di dispatcher IO, bukan di thread pemanggil`() = runBlocking {
        val io = Executors.newSingleThreadExecutor { Thread(it, "io-uji") }
        try {
            val store = FakeTokenStore(token = null)
            val session = ServerSession(store, ioDispatcher = io.asCoroutineDispatcher())

            session.connect(FakeAccountRemote(), "id-token", "perangkat-1")

            assertTrue(store.saveThreads.single().startsWith("io-uji"), "ditulis di thread IO uji: ${store.saveThreads}")
            assertNotEquals(Thread.currentThread().name, store.saveThreads.single())
        } finally {
            io.shutdown()
        }
    }

    @Test
    fun `masuk mencatat waktu terima token dari jam sesi`() = runBlocking {
        val store = FakeTokenStore(token = null)
        val session = ServerSession(store, clock = { now })

        session.connect(FakeAccountRemote(), "id-token", "perangkat-1")

        assertEquals(now, session.lastSavedAt())
    }

    @Test
    fun `pengguna keluar selama masuk berjalan, token baru dibuang dan dicabut sebisanya`() = runBlocking {
        val store = FakeTokenStore(token = null)
        val session = ServerSession(store, ioDispatcher = Dispatchers.Unconfined, revokeOrphanTimeoutMillis = 200)
        val remote = FakeAccountRemote(valid = setOf("login-token")).apply { loginGate = CompletableDeferred() }

        val connecting = async { session.connect(session.guard(remote), "id-token", "perangkat-1") }
        yield()
        session.takeToken() // keluar akun saat permintaan terbang
        remote.loginGate!!.complete(Unit)
        val result = connecting.await()

        assertIs<RemoteResult.Failure>(result, "tidak tersambung: sesi sudah ditutup")
        assertNull(store.token, "sesi yang sudah ditutup tidak boleh hidup lagi")
        assertTrue(session.needsReconnect.value)
        assertTrue("logout:login-token" in remote.calls, "token yatim dicabut di server")
    }

    // --- Baca-dan-hapus atomik (butir 6) ---

    @Test
    fun `takeToken mengembalikan token dan membersihkannya sekaligus`() {
        val store = FakeTokenStore(token = "t-1", expiry = now)
        val session = ServerSession(store)

        assertEquals("t-1", session.takeToken())

        assertNull(store.token)
        assertNull(session.expiresAt())
        assertTrue(session.needsReconnect.value)
        assertNull(session.takeToken(), "kedua kalinya tidak ada apa-apa")
    }

    @Test
    fun `putar token yang menyela di antara baca dan hapus tidak lolos, yang dicabut adalah token terbaru`() {
        val store = FakeTokenStore(token = "t-1")
        val session = ServerSession(store)
        var interruptionOutcome: RotationOutcome? = null
        val interrupter = Thread {
            interruptionOutcome = session.onRotated("t-1", RefreshedToken("t-2", null), at = now)
        }
        // Meniru putar token yang selesai TEPAT di antara pembacaan dan penghapusan, dari thread lain.
        store.onRead = {
            interrupter.start()
            interrupter.join(300) // beri kesempatan; bila baca-dan-hapus atomik, thread ini tertahan kunci
        }

        val taken = session.takeToken()
        interrupter.join()

        assertEquals("t-1", taken)
        assertEquals(
            RotationOutcome.SUPERSEDED,
            interruptionOutcome,
            "putar yang menyela harus melihat sesi sudah ditutup, bukan menulis token baru yang lalu ikut terhapus",
        )
        assertNull(store.token)
    }

    // --- Keputusan Hapus akun (butir 3) ---

    @Test
    fun `ada token berarti hapus di server`() {
        assertEquals(DeleteAccountPlan.DeleteOnServer("t"), planDeleteAccount(google = true, token = "t", everHeldToken = true))
        assertEquals(DeleteAccountPlan.DeleteOnServer("t"), planDeleteAccount(google = false, token = "t", everHeldToken = false))
    }

    @Test
    fun `akun Google yang pernah punya token tetapi tokennya hilang diminta sambungkan ulang, tidak keluar diam-diam`() {
        assertEquals(DeleteAccountPlan.ReconnectFirst, planDeleteAccount(google = true, token = null, everHeldToken = true))
    }

    @Test
    fun `akun yang tak pernah punya akun server boleh langsung keluar`() {
        assertEquals(DeleteAccountPlan.SignOutOnly, planDeleteAccount(google = true, token = null, everHeldToken = false), "Google yang tak pernah tersambung")
        assertEquals(DeleteAccountPlan.SignOutOnly, planDeleteAccount(google = false, token = null, everHeldToken = false), "akun lokal")
        assertEquals(DeleteAccountPlan.SignOutOnly, planDeleteAccount(google = false, token = null, everHeldToken = true), "akun non-Google tak punya akun server")
    }

    @Test
    fun `sesi yang berakhir lewat 401 tetap ingat pernah punya akun server, sehingga Hapus akun meminta sambungkan ulang`() {
        val store = FakeTokenStore(token = "t")
        val session = ServerSession(store)
        session.onUnauthorized("t")

        val plan = planDeleteAccount(google = true, token = session.token(), everHeldToken = session.hasEverHeldToken())

        assertEquals(DeleteAccountPlan.ReconnectFirst, plan)
    }

    @Test
    fun `akun yang tak pernah tersambung tidak dianggap pernah punya akun server`() {
        val session = ServerSession(FakeTokenStore(token = null))

        val plan = planDeleteAccount(google = true, token = session.token(), everHeldToken = session.hasEverHeldToken())

        assertEquals(DeleteAccountPlan.SignOutOnly, plan)
    }

    @Test
    fun `setelah akun server dihapus tidak ada lagi yang perlu disambungkan ulang sebelum keluar`() {
        val store = FakeTokenStore(token = "t")
        val session = ServerSession(store)

        session.onAccountDeleted()

        assertNull(store.token)
        assertFalse(session.hasEverHeldToken())
        assertTrue(session.needsReconnect.value)
    }

    // --- Keluar dari mode demo membersihkan sesi akun asli (butir 1) ---

    private class Link(val store: FakeTokenStore, val remote: FakeAccountRemote, token: String) {
        val session = ServerSession(store)
        val link = ServerLink(session, session.guard(remote))

        init {
            remote.valid += token
        }
    }

    @Test
    fun `keluar saat mode demo aktif membersihkan dan mencabut token akun asli, bukan ruang kerja demo`() = runBlocking {
        val demoWorkspace = Link(FakeTokenStore(token = null), FakeAccountRemote(), "tidak-dipakai")
        val real = Link(FakeTokenStore(token = "token-asli"), FakeAccountRemote(), "token-asli")

        ServerSignOut(scope).signOutAccount(demoActive = true, workspaceLink = demoWorkspace.link, realAccountLink = { real.link })!!.join()

        assertNull(real.store.token, "token akun asli dibersihkan")
        assertEquals(listOf("logout:token-asli"), real.remote.calls, "dan dicabut di server")
        assertTrue(demoWorkspace.remote.calls.isEmpty())
    }

    @Test
    fun `keluar di data asli memakai sesi ruang kerja dan tidak membuka sesi akun lain`() = runBlocking {
        val workspace = Link(FakeTokenStore(token = "token-asli"), FakeAccountRemote(), "token-asli")
        var opened = false

        ServerSignOut(scope).signOutAccount(
            demoActive = false,
            workspaceLink = workspace.link,
            realAccountLink = {
                opened = true
                error("tidak boleh dibuka")
            },
        )!!.join()

        assertNull(workspace.store.token)
        assertEquals(listOf("logout:token-asli"), workspace.remote.calls)
        assertFalse(opened)
    }

    // --- 401 karena token baru diputar bukan sesi berakhir (butir 5) ---

    @Test
    fun `401 karena token diputar selama permintaan diulang sekali dengan token baru`() = runBlocking {
        val store = FakeTokenStore(token = "t-1")
        val session = ServerSession(store)
        val tokensUsed = mutableListOf<String>()

        val result = session.retryOnceIfRotated(
            isUnauthorized = { it.isUnauthorized() },
            call = {
                val token = session.token()!!
                tokensUsed += token
                if (token == "t-1") {
                    // Permintaan terbang dengan t-1; putar token menyimpan t-2; server menjawab 401 untuk t-1.
                    session.onRotated("t-1", RefreshedToken("t-2", null), at = now)
                    session.guard(FailingSyncRemote(RemoteError.Unauthorized)).pull(token, "device-x")
                } else {
                    RemoteResult.Failure(RemoteError.Unreachable)
                }
            },
        )

        assertEquals(listOf("t-1", "t-2"), tokensUsed, "diulang sekali dengan token baru")
        assertEquals("t-2", store.token, "token baru tidak dibuang oleh 401 token lama")
        assertFalse(session.needsReconnect.value)
        assertEquals(RemoteResult.Failure(RemoteError.Unreachable), result, "hasil ulangan yang dikembalikan")
    }

    @Test
    fun `401 dengan token yang tidak berubah tidak diulang dan sesi dinyatakan berakhir`() = runBlocking {
        val store = FakeTokenStore(token = "t-1")
        val session = ServerSession(store)
        var calls = 0

        val result = session.retryOnceIfRotated(
            isUnauthorized = { it.isUnauthorized() },
            call = {
                calls++
                session.guard(FailingSyncRemote(RemoteError.Unauthorized)).pull(session.token()!!, "device-x")
            },
        )

        assertEquals(1, calls)
        assertTrue(result.isUnauthorized())
        assertTrue(session.sessionEndedBy(result), "token dibersihkan: memang sesi berakhir")
    }

    @Test
    fun `sesi dinyatakan berakhir hanya bila 401 dan sesi benar-benar berakhir`() {
        val rotated = ServerSession(FakeTokenStore(token = "t-2")) // sesi masih sah
        val unauthorized = RemoteResult.Failure(RemoteError.Unauthorized)
        assertFalse(rotated.sessionEndedBy(unauthorized), "401 untuk token lama padahal sesi sah: bukan berakhir")
        assertFalse(rotated.sessionEndedBy(RemoteError.Unauthorized))

        val ended = ServerSession(FakeTokenStore(token = null))
        assertTrue(ended.sessionEndedBy(unauthorized))
        assertTrue(ended.sessionEndedBy(RemoteError.Unauthorized))
        assertFalse(ended.sessionEndedBy(RemoteResult.Failure(RemoteError.Unreachable)), "bukan 401")
    }

    @Test
    fun `hasil bukan 401 dikembalikan apa adanya tanpa ulang`() = runBlocking {
        val session = ServerSession(FakeTokenStore(token = "t-1"))
        var calls = 0

        val result = session.retryOnceIfRotated(isUnauthorized = { it.isUnauthorized() }, call = {
            calls++
            RemoteResult.Success(ApiAccount("x", 1, null, "a@b.c"))
        })

        assertEquals(1, calls)
        assertIs<RemoteResult.Success<*>>(result)
    }
}
