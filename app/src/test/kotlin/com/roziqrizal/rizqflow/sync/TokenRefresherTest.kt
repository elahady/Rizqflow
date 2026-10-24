package com.roziqrizal.rizqflow.sync

import com.roziqrizal.rizqflow.domain.sync.RemoteError
import java.time.Instant
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [TokenRefresher]: kapan token diputar dan bahwa token tidak pernah hilang (Tahap 12). */
class TokenRefresherTest {
    private val now = Instant.parse("2026-12-01T00:00:00Z")
    private val soon = now.plusSeconds(10L * 24 * 3600) // 10 hari lagi
    private val far = now.plusSeconds(80L * 24 * 3600) // 80 hari lagi
    private val remote = FakeAccountRemote(valid = setOf("t-lama"))

    // Dispatcher tulis-ke-disk: Unconfined supaya urutan tes deterministik (tanpa lompat thread); tes khusus
    // thread memakai dispatcher sungguhan.
    private fun newRefresher(
        store: FakeTokenStore,
        ioDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        clock: () -> Instant = { now },
    ): Pair<ServerSession, TokenRefresher> {
        val session = ServerSession(store, clock)
        return session to TokenRefresher(session, session.guard(remote), clock, revokeOrphanTimeoutMillis = 200, ioDispatcher = ioDispatcher)
    }

    // --- Keputusan ---

    @Test
    fun `token yang masih jauh dari kedaluwarsa tidak diputar dan tidak ada panggilan jaringan`() = runBlocking {
        val (_, refresher) = newRefresher(FakeTokenStore("t-lama", far))

        assertEquals(RefreshOutcome.NotNeeded, refresher.refreshIfDue())
        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun `tanpa token tidak ada yang diputar`() = runBlocking {
        val (_, refresher) = newRefresher(FakeTokenStore(null))

        assertEquals(RefreshOutcome.NotNeeded, refresher.refreshIfDue())
        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun `token yang kedaluwarsa dalam 30 hari diputar dan token baru beserta tanggalnya tersimpan`() = runBlocking {
        val store = FakeTokenStore("t-lama", soon)
        val (session, refresher) = newRefresher(store)

        assertEquals(RefreshOutcome.Rotated, refresher.refreshIfDue())

        assertEquals("rotated-1", store.token)
        assertEquals(remote.refreshExpiry, session.expiresAt())
        assertFalse(session.needsReconnect.value)
        assertEquals(listOf("refresh:t-lama"), remote.calls)
    }

    @Test
    fun `token lama tanpa masa berlaku diputar sekali lalu membawa tanggal`() = runBlocking {
        val store = FakeTokenStore("t-lama", expiry = null)
        val (_, refresher) = newRefresher(store)

        assertEquals(RefreshOutcome.Rotated, refresher.refreshIfDue())
        assertEquals(RefreshOutcome.NotNeeded, refresher.refreshIfDue(), "token baru sudah membawa tanggal 90 hari")

        assertEquals(1, remote.calls.size)
    }

    @Test
    fun `keputusan mengikuti jam yang disuntikkan`() = runBlocking {
        val store = FakeTokenStore("t-lama", now.plusSeconds(40L * 24 * 3600))
        var clockNow = now
        val (_, refresher) = newRefresher(store) { clockNow }

        assertEquals(RefreshOutcome.NotNeeded, refresher.refreshIfDue(), "40 hari lagi: belum")
        clockNow = now.plusSeconds(15L * 24 * 3600) // tinggal 25 hari
        assertEquals(RefreshOutcome.Rotated, refresher.refreshIfDue())
    }

    // --- Jeda minimum antar putar (jam ponsel jauh ke depan) ---

    @Test
    fun `jam ponsel jauh ke depan tidak membuat token diputar beruntun`() = runBlocking {
        val year = 365L * 24 * 3600
        val clockNow = now.plusSeconds(5 * year) // masa berlaku dari server (2027) tampak sudah lewat sejak lama
        val store = FakeTokenStore("t-lama", expiry = now.plusSeconds(10L * 24 * 3600))
        val (_, refresher) = newRefresher(store) { clockNow }

        assertEquals(RefreshOutcome.Rotated, refresher.refreshIfDue(), "pertama kali memang perlu diputar")
        assertEquals(RefreshOutcome.NotNeeded, refresher.refreshIfDue(), "token baru belum sehari: jangan diputar lagi")
        assertEquals(RefreshOutcome.NotNeeded, refresher.refreshIfDue())

        assertEquals(1, remote.calls.count { it.startsWith("refresh") })
    }

    @Test
    fun `aplikasi dibuka ulang di jam yang sama tetap tidak memutar lagi karena waktu terima tersimpan`() = runBlocking {
        val clockNow = now.plusSeconds(5L * 365 * 24 * 3600)
        val store = FakeTokenStore("t-lama", expiry = soon)
        newRefresher(store) { clockNow }.second.refreshIfDue()

        // Proses baru (penyimpanan yang sama, pemutar dan sesi baru): tidak ada ingatan memori yang tersisa.
        val (_, reopened) = newRefresher(store) { clockNow.plusSeconds(3600) }

        assertEquals(RefreshOutcome.NotNeeded, reopened.refreshIfDue())
        assertEquals(1, remote.calls.size)
    }

    @Test
    fun `sesudah jeda sehari token boleh diputar lagi bila masa berlakunya memang dekat`() = runBlocking {
        val clockNow = now.plusSeconds(5L * 365 * 24 * 3600)
        var current = clockNow
        val store = FakeTokenStore("t-lama", expiry = soon)
        val (_, refresher) = newRefresher(store) { current }
        refresher.refreshIfDue()

        current = clockNow.plusSeconds(24 * 3600)

        assertEquals(RefreshOutcome.Rotated, refresher.refreshIfDue())
    }

    // --- Penulisan ke disk di luar thread pemanggil ---

    @Test
    fun `token baru ditulis di dispatcher IO, bukan di thread pemanggil`() = runBlocking {
        val io = Executors.newSingleThreadExecutor { Thread(it, "io-uji") }
        try {
            val store = FakeTokenStore("t-lama", soon)
            val (_, refresher) = newRefresher(store, ioDispatcher = io.asCoroutineDispatcher())

            assertEquals(RefreshOutcome.Rotated, refresher.refreshIfDue())

            assertTrue(store.saveThreads.single().startsWith("io-uji"), "ditulis di thread IO uji: ${store.saveThreads}")
            assertNotEquals(Thread.currentThread().name, store.saveThreads.single())
        } finally {
            io.shutdown()
        }
    }

    // --- Jaringan dan 401 ---

    @Test
    fun `jaringan mati diam dan tidak mencabut apa pun`() = runBlocking {
        val store = FakeTokenStore("t-lama", soon)
        val (session, refresher) = newRefresher(store)
        remote.refreshFailure = RemoteError.Unreachable

        val outcome = refresher.refreshIfDue()

        assertEquals(RefreshOutcome.Deferred(RemoteError.Unreachable), outcome)
        assertEquals("t-lama", store.token)
        assertEquals(soon, session.expiresAt())
        assertFalse(session.needsReconnect.value)
    }

    @Test
    fun `server gangguan 503 diam dan dicoba lagi pada pemicu berikutnya`() = runBlocking {
        val store = FakeTokenStore("t-lama", soon)
        val (_, refresher) = newRefresher(store)
        remote.refreshFailure = RemoteError.Unexpected(503)
        assertEquals(RefreshOutcome.Deferred(RemoteError.Unexpected(503)), refresher.refreshIfDue())

        remote.refreshFailure = null
        assertEquals(RefreshOutcome.Rotated, refresher.refreshIfDue())
        assertEquals("rotated-1", store.token)
    }

    @Test
    fun `401 mengakhiri sesi, membersihkan token, dan menaikkan perlu sambungkan ulang`() = runBlocking {
        val store = FakeTokenStore("t-tak-dikenal", soon)
        val (session, refresher) = newRefresher(store)

        assertEquals(RefreshOutcome.SessionEnded, refresher.refreshIfDue())

        assertNull(store.token)
        assertTrue(session.needsReconnect.value)
    }

    // --- Penyimpanan token baru tidak boleh diam ---

    @Test
    fun `token baru gagal ditulis ke disk dilaporkan sebagai StorageFailed`() = runBlocking {
        val store = FakeTokenStore("t-lama", soon, persist = false)
        val (session, refresher) = newRefresher(store)

        assertEquals(RefreshOutcome.StorageFailed, refresher.refreshIfDue())

        assertEquals("rotated-1", store.token, "dipakai proses ini; token lama sudah dicabut server")
        assertFalse(session.needsReconnect.value)
    }

    // --- Serialisasi ---

    @Test
    fun `dua putar bersamaan hanya memanggil server sekali dan tidak saling membuang token`() = runBlocking {
        val store = FakeTokenStore("t-lama", soon)
        val (session, refresher) = newRefresher(store)
        remote.refreshGate = CompletableDeferred()

        // Sync pembukaan dan klaim pembelian memicu putar pada saat yang sama.
        val first = async { refresher.refreshIfDue() }
        val second = async { refresher.refreshIfDue() }
        yield()
        remote.refreshGate!!.complete(Unit)
        val outcomes = awaitAll(first, second)

        assertEquals(1, remote.calls.count { it.startsWith("refresh") }, "putar kedua harus melihat token baru dan berhenti")
        assertEquals(setOf(RefreshOutcome.Rotated, RefreshOutcome.NotNeeded), outcomes.toSet())
        assertEquals("rotated-1", store.token)
        assertFalse(session.needsReconnect.value, "tidak ada 401 yang menghapus sesi sah")
    }

    @Test
    fun `banyak putar bersamaan tetap berakhir dengan satu token sah di server dan tersimpan`() = runBlocking {
        val store = FakeTokenStore("t-lama", soon)
        val (session, refresher) = newRefresher(store)

        List(10) { async { refresher.refreshIfDue() } }.awaitAll()

        assertEquals(1, remote.calls.size)
        assertTrue(store.token in remote.valid, "token tersimpan harus token yang masih sah di server")
        assertFalse(session.needsReconnect.value)
    }

    // --- Pembatalan di tengah jalan ---

    @Test
    fun `pemicu dibatalkan saat permintaan terbang tetap menyimpan token baru`() = runBlocking {
        val store = FakeTokenStore("t-lama", soon)
        val (_, refresher) = newRefresher(store)
        remote.refreshGate = CompletableDeferred()

        val caller = launch { refresher.refreshIfDue() }
        yield()
        assertEquals(listOf("refresh:t-lama"), remote.calls, "permintaan sudah terbang")
        caller.cancel() // layar pemicu ditutup
        remote.refreshGate!!.complete(Unit)
        caller.join()

        // Putar berjalan tak terbatalkan: kalau ia ikut batal, server sudah mencabut token lama tetapi
        // ponsel tidak pernah menerima yang baru.
        assertEquals("rotated-1", store.token)
    }

    @Test
    fun `pengguna keluar selama putar, token pengganti tidak dipakai dan dicabut sebisanya`() = runBlocking {
        val store = FakeTokenStore("t-lama", soon)
        val (session, refresher) = newRefresher(store)
        remote.refreshGate = CompletableDeferred()

        val running = async { refresher.refreshIfDue() }
        yield()
        session.clearLocal() // keluar akun saat permintaan terbang
        remote.refreshGate!!.complete(Unit)

        assertEquals(RefreshOutcome.Superseded, running.await())
        assertNull(store.token, "sesi yang sudah ditutup tidak boleh hidup lagi")
        assertTrue("logout:rotated-1" in remote.calls, "token yatim dicabut di server")
        assertFalse("rotated-1" in remote.valid)
    }
}
