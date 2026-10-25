package com.roziqrizal.rizqflow.sync

import com.roziqrizal.rizqflow.domain.sync.AccountKeyProvider
import com.roziqrizal.rizqflow.domain.sync.EnableRecoveryResult
import com.roziqrizal.rizqflow.domain.sync.RECOVERY_KEY_VERSION
import com.roziqrizal.rizqflow.domain.sync.RecoverKeyResult
import com.roziqrizal.rizqflow.domain.sync.RecoveryCode
import com.roziqrizal.rizqflow.domain.sync.RecoveryKeyLookup
import com.roziqrizal.rizqflow.domain.sync.RecoveryKeyReceipt
import com.roziqrizal.rizqflow.domain.sync.RecoveryKeyRemote
import com.roziqrizal.rizqflow.domain.sync.RecoveryKeyService
import com.roziqrizal.rizqflow.domain.sync.RecoveryStatus
import com.roziqrizal.rizqflow.domain.sync.RecoveryStatusStore
import com.roziqrizal.rizqflow.domain.sync.RemoteCopyCheck
import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import com.roziqrizal.rizqflow.domain.sync.SyncAead
import java.time.Instant
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class FakeKeys(var key: ByteArray) : AccountKeyProvider {
    override fun getOrCreateAccountKey(): SyncAead = error("tidak dipakai")
    override fun exportAccountKeyForRecovery(): ByteArray = key.copyOf()
    override fun importAccountKeyFromRecovery(exportedAccountKey: ByteArray) {
        key = exportedAccountKey.copyOf()
    }
}

private class MemoryStatus(var status: RecoveryStatus = RecoveryStatus.NotConfigured) : RecoveryStatusStore {
    override fun read() = status
    override fun markPending(serverUpdatedAt: String?) {
        status = RecoveryStatus.PendingConfirmation(serverUpdatedAt)
    }

    override fun markConfigured(at: Instant, serverUpdatedAt: String?) {
        status = RecoveryStatus.Configured(at, serverUpdatedAt)
    }

    override fun clear() {
        status = RecoveryStatus.NotConfigured
    }
}

/** Server palsu yang mencatat token tiap panggilan dan bisa menjawab sesuai skrip. */
private class ScriptedRecoveryRemote(private val onPut: (String) -> RemoteResult<RecoveryKeyReceipt>) : RecoveryKeyRemote {
    val putTokens = mutableListOf<String>()
    var stored: RecoveryKeyLookup.Found? = null

    override suspend fun put(apiToken: String, wrappedKey: ByteArray): RemoteResult<RecoveryKeyReceipt> {
        putTokens += apiToken
        val result = onPut(apiToken)
        if (result is RemoteResult.Success) stored = RecoveryKeyLookup.Found(wrappedKey.copyOf(), RECOVERY_KEY_VERSION, "t")
        return result
    }

    var getResult: (String) -> RemoteResult<RecoveryKeyLookup> = { RemoteResult.Success(stored ?: RecoveryKeyLookup.NotFound) }
    val getTokens = mutableListOf<String>()

    override suspend fun get(apiToken: String): RemoteResult<RecoveryKeyLookup> {
        getTokens += apiToken
        return getResult(apiToken)
    }

    override suspend fun delete(apiToken: String): RemoteResult<Unit> = RemoteResult.Success(Unit)
}

private val ok = RemoteResult.Success(RecoveryKeyReceipt(1, "2026-10-25T00:00:00Z"))

class RecoveryFlowTest {
    private val now = Instant.parse("2026-10-25T10:00:00Z")

    // ---- baris menu ----

    @Test
    fun `baris kode pemulihan hanya untuk akun Google asli dengan Sync aktif dan token server`() {
        val s = RecoveryStatus.NotConfigured

        assertEquals(RecoveryRow.Offer, recoveryRow(google = true, demo = false, syncOwned = true, hasToken = true, status = s))
        assertEquals(RecoveryRow.Hidden, recoveryRow(google = false, demo = false, syncOwned = true, hasToken = true, status = s), "akun lokal")
        assertEquals(RecoveryRow.Hidden, recoveryRow(google = true, demo = true, syncOwned = true, hasToken = true, status = s), "demo")
        assertEquals(RecoveryRow.Hidden, recoveryRow(google = true, demo = false, syncOwned = false, hasToken = true, status = s), "tanpa Sync")
        assertEquals(RecoveryRow.Hidden, recoveryRow(google = true, demo = false, syncOwned = true, hasToken = false, status = s), "tanpa token")
    }

    @Test
    fun `status penanda dipetakan ke ajakan, belum selesai, dan sudah diatur`() {
        fun row(status: RecoveryStatus) = recoveryRow(google = true, demo = false, syncOwned = true, hasToken = true, status = status)

        assertEquals(RecoveryRow.Offer, row(RecoveryStatus.NotConfigured))
        assertEquals(RecoveryRow.Pending, row(RecoveryStatus.PendingConfirmation()))
        assertEquals(RecoveryRow.Configured(now), row(RecoveryStatus.Configured(now)))
    }

    @Test
    fun `Pulihkan tersedia tanpa menunggu Sync, tetapi tidak untuk akun lokal, demo, atau tanpa token`() {
        assertTrue(restoreAvailable(google = true, demo = false, hasToken = true))
        assertFalse(restoreAvailable(google = false, demo = false, hasToken = true))
        assertFalse(restoreAvailable(google = true, demo = true, hasToken = true))
        assertFalse(restoreAvailable(google = true, demo = false, hasToken = false))
    }

    @Test
    fun `tawaran di perangkat baru hanya bila ada salinan di server dan penanda lokal belum ada`() {
        fun offer(status: RecoveryStatus, copy: RemoteCopyCheck, demo: Boolean = false, token: Boolean = true) =
            shouldOfferRestoreOnNewDevice(google = true, demo = demo, hasToken = token, status = status, copy = copy)

        assertTrue(offer(RecoveryStatus.NotConfigured, RemoteCopyCheck.EXISTS))
        assertFalse(offer(RecoveryStatus.NotConfigured, RemoteCopyCheck.NONE))
        assertFalse(offer(RecoveryStatus.NotConfigured, RemoteCopyCheck.UNKNOWN))
        assertFalse(offer(RecoveryStatus.Configured(now), RemoteCopyCheck.EXISTS), "perangkat ini sudah mengatur")
        assertFalse(offer(RecoveryStatus.PendingConfirmation(), RemoteCopyCheck.EXISTS))
        assertFalse(offer(RecoveryStatus.NotConfigured, RemoteCopyCheck.EXISTS, demo = true))
        assertFalse(offer(RecoveryStatus.NotConfigured, RemoteCopyCheck.EXISTS, token = false))
    }

    // ---- penahan kode di memori ----

    @Test
    fun `penahan mengembalikan kode hanya untuk akun pemiliknya dan kosong setelah clear`() {
        val holder = RecoveryCodeHolder()
        val store = FakeTokenStore(token = "t")
        val remote = ScriptedRecoveryRemote { ok }
        val service = RecoveryKeyService(FakeKeys("kunci-a".toByteArray()), remote, store, MemoryStatus(), clock = { now })
        val display = assertIs<EnableRecoveryResult.Ready>(runBlocking { service.enableRecovery() }).display

        holder.hold("a@example.com", display)

        assertSame(display, holder.peek("a@example.com"))
        assertNull(holder.peek("b@example.com"), "kode akun A tidak boleh tampil untuk akun B")
        holder.clear()
        assertNull(holder.peek("a@example.com"))
    }

    @Test
    fun `penahan tidak membocorkan kata di toString`() {
        val holder = RecoveryCodeHolder()
        val service = RecoveryKeyService(FakeKeys("k".toByteArray()), ScriptedRecoveryRemote { ok }, FakeTokenStore(token = "t"), MemoryStatus())
        val display = assertIs<EnableRecoveryResult.Ready>(runBlocking { service.enableRecovery() }).display
        holder.hold("a", display)

        assertEquals("RecoveryCodeHolder(disembunyikan)", holder.toString())
    }

    // ---- ulang sekali bila 401 karena token diputar; 401 nyata lewat ServerSession ----

    @Test
    fun `401 karena token diputar di tengah jalan diulang sekali dengan token baru dan sesi tidak berakhir`() = runBlocking<Unit> {
        val store = FakeTokenStore(token = "lama")
        val session = ServerSession(store)
        val remote = ScriptedRecoveryRemote { token ->
            if (token == "lama") {
                store.token = "baru" // pemutar menyimpan token baru selagi permintaan lama terbang
                RemoteResult.Failure(RemoteError.Unauthorized)
            } else {
                ok
            }
        }
        val service = RecoveryKeyService(FakeKeys("kunci".toByteArray()), session.guard(remote), store, MemoryStatus(), clock = { now })

        val result = service.enableRecoveryRetrying(session, replaceExistingCopy = true)

        assertIs<EnableRecoveryResult.Ready>(result)
        assertEquals(listOf("lama", "baru"), remote.putTokens)
        assertEquals("baru", store.token)
        assertFalse(session.needsReconnect.value)
    }

    @Test
    fun `401 nyata tidak diulang, tidak menampilkan kode, dan mengakhiri sesi lewat ServerSession`() = runBlocking<Unit> {
        val store = FakeTokenStore(token = "t")
        val session = ServerSession(store)
        val remote = ScriptedRecoveryRemote { RemoteResult.Failure(RemoteError.Unauthorized) }
        val status = MemoryStatus()
        val service = RecoveryKeyService(FakeKeys("kunci".toByteArray()), session.guard(remote), store, status, clock = { now })

        val result = service.enableRecoveryRetrying(session, replaceExistingCopy = true)

        assertEquals(EnableRecoveryResult.Failed(RemoteError.Unauthorized), result)
        assertEquals(listOf("t"), remote.putTokens)
        assertNull(store.token)
        assertTrue(session.needsReconnect.value)
        assertEquals(RecoveryStatus.NotConfigured, status.status)
    }

    @Test
    fun `pulihkan juga diulang sekali bila 401 karena token diputar`() = runBlocking<Unit> {
        val source = FakeTokenStore(token = "t")
        val remoteA = ScriptedRecoveryRemote { ok }
        val display = assertIs<EnableRecoveryResult.Ready>(
            RecoveryKeyService(FakeKeys("kunci-a".toByteArray()), remoteA, source, MemoryStatus()).enableRecovery(),
        ).display

        val store = FakeTokenStore(token = "lama")
        val session = ServerSession(store)
        val remoteB = ScriptedRecoveryRemote { ok }
        remoteB.getResult = { token ->
            if (token == "lama") {
                store.token = "baru"
                RemoteResult.Failure(RemoteError.Unauthorized)
            } else {
                RemoteResult.Success(remoteA.stored!!)
            }
        }
        val keysB = FakeKeys("kunci-b".toByteArray())
        val service = RecoveryKeyService(keysB, session.guard(remoteB), store, MemoryStatus(), clock = { now })

        val result = service.recoverKeyRetrying(session, display.words)

        assertEquals(RecoverKeyResult.Recovered, result)
        assertEquals(listOf("lama", "baru"), remoteB.getTokens)
        assertContentEquals("kunci-a".toByteArray(), keysB.key)
    }

    @Test
    fun `pulihkan dengan kode salah tidak diulang dan kunci lokal utuh`() = runBlocking<Unit> {
        val store = FakeTokenStore(token = "t")
        val session = ServerSession(store)
        val remote = ScriptedRecoveryRemote { ok }
        RecoveryKeyService(FakeKeys("kunci-a".toByteArray()), remote, store, MemoryStatus()).enableRecovery()
        val getsBefore = remote.getTokens.size
        val keysB = FakeKeys("kunci-b".toByteArray())
        val service = RecoveryKeyService(keysB, session.guard(remote), store, MemoryStatus(), clock = { now })

        val result = service.recoverKeyRetrying(session, RecoveryCode.generate().words)

        assertEquals(RecoverKeyResult.WrongCode, result)
        assertContentEquals("kunci-b".toByteArray(), keysB.key)
        assertEquals(getsBefore + 1, remote.getTokens.size, "tepat satu GET, tanpa ulang")
    }

    // ---- pembungkus 401 untuk remote kode pemulihan ----

    @Test
    fun `401 dari put, get, dan delete kode pemulihan masing-masing mengakhiri sesi lewat pembungkus`() = runBlocking<Unit> {
        class Unauthorized : RecoveryKeyRemote {
            override suspend fun put(apiToken: String, wrappedKey: ByteArray): RemoteResult<RecoveryKeyReceipt> = RemoteResult.Failure(RemoteError.Unauthorized)
            override suspend fun get(apiToken: String): RemoteResult<RecoveryKeyLookup> = RemoteResult.Failure(RemoteError.Unauthorized)
            override suspend fun delete(apiToken: String): RemoteResult<Unit> = RemoteResult.Failure(RemoteError.Unauthorized)
        }
        val calls = listOf<suspend (RecoveryKeyRemote) -> Unit>(
            { it.put("t", ByteArray(1)) },
            { it.get("t") },
            { it.delete("t") },
        )
        for (call in calls) {
            val store = FakeTokenStore(token = "t")
            val session = ServerSession(store)

            call(session.guard(Unauthorized()))

            assertNull(store.token)
            assertTrue(session.needsReconnect.value)
        }
    }

    @Test
    fun `jawaban selain 401 dari remote kode pemulihan tidak mencabut token`() = runBlocking<Unit> {
        class Failing(val error: RemoteError) : RecoveryKeyRemote {
            override suspend fun put(apiToken: String, wrappedKey: ByteArray): RemoteResult<RecoveryKeyReceipt> = RemoteResult.Failure(error)
            override suspend fun get(apiToken: String): RemoteResult<RecoveryKeyLookup> = RemoteResult.Failure(error)
            override suspend fun delete(apiToken: String): RemoteResult<Unit> = RemoteResult.Failure(error)
        }
        for (error in listOf(RemoteError.Unreachable, RemoteError.SyncRequired, RemoteError.Rejected("x"), RemoteError.Unexpected(500))) {
            val store = FakeTokenStore(token = "t")
            val session = ServerSession(store)

            val guarded = session.guard(Failing(error))
            guarded.put("t", ByteArray(1))
            guarded.get("t")
            guarded.delete("t")

            assertEquals("t", store.token, "untuk $error")
            assertFalse(session.needsReconnect.value)
        }
    }

    @Test
    fun `pembungkus meneruskan jawaban sukses dan NotFound apa adanya`() = runBlocking<Unit> {
        val store = FakeTokenStore(token = "t")
        val session = ServerSession(store)
        val remote = ScriptedRecoveryRemote { ok }
        val guarded = session.guard(remote)

        assertEquals(RemoteResult.Success(RecoveryKeyLookup.NotFound), guarded.get("t"))
        assertEquals(ok, guarded.put("t", ByteArray(3)))
        assertIs<RemoteResult.Success<RecoveryKeyLookup>>(guarded.get("t"))
    }

    // ---- penulisan penanda di luar thread utama ----

    private val noRemote = ScriptedRecoveryRemote { ok }

    /** Penanda yang mencatat thread tempat ditulis (commit() SharedPreferences sungguhan menyentuh disk). */
    private class ThreadRecordingStatus : RecoveryStatusStore {
        var status: RecoveryStatus = RecoveryStatus.NotConfigured
        var writeThread: String? = null
        override fun read() = status
        override fun markPending(serverUpdatedAt: String?) {
            writeThread = Thread.currentThread().name
            status = RecoveryStatus.PendingConfirmation(serverUpdatedAt)
        }

        override fun markConfigured(at: Instant, serverUpdatedAt: String?) {
            writeThread = Thread.currentThread().name
            status = RecoveryStatus.Configured(at, serverUpdatedAt)
        }

        override fun clear() {
            writeThread = Thread.currentThread().name
            status = RecoveryStatus.NotConfigured
        }
    }

    private fun serviceWith(status: RecoveryStatusStore) =
        RecoveryKeyService(FakeKeys("k".toByteArray()), noRemote, FakeTokenStore(token = "t"), status, clock = { now })

    @Test
    fun `confirmOffMain menulis penanda di thread pengirim, bukan thread pemanggil`() {
        val status = ThreadRecordingStatus()
        status.markPending("u")
        val display = assertIs<EnableRecoveryResult.Ready>(runBlocking { serviceWith(MemoryStatus()).enableRecovery() }).display
        val challenge = display.newChallenge()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "penulis-penanda") }
        try {
            val dispatcher = executor.asCoroutineDispatcher()
            val caller = Thread.currentThread().name

            val ok = runBlocking { serviceWith(status).confirmOffMain(challenge, challenge.positions.associateWith { display.words[it - 1] }, dispatcher) }

            assertTrue(ok)
            assertTrue(status.writeThread!!.startsWith("penulis-penanda"), "thread: ${status.writeThread}") // mode debug coroutine menambah "@coroutine#N"
            assertTrue(!status.writeThread!!.startsWith(caller), "thread: ${status.writeThread} vs $caller")
            assertEquals(RecoveryStatus.Configured(now, "u"), status.status)
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun `forgetStatusDurably menulis di thread pengirim dan tetap berjalan walau pemanggil sudah dibatalkan`() {
        val status = ThreadRecordingStatus()
        status.status = RecoveryStatus.Configured(now, "u")
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "penulis-penanda") }
        try {
            val dispatcher = executor.asCoroutineDispatcher()

            runBlocking {
                val job = launch {
                    cancel() // pemanggil sudah dibatalkan (layar hilang) sebelum pembersihan
                    serviceWith(status).forgetStatusDurably(dispatcher)
                }
                job.join()
            }

            assertEquals(RecoveryStatus.NotConfigured, status.status, "pembersihan penanda tidak boleh terlewat")
            assertTrue(status.writeThread!!.startsWith("penulis-penanda"), "thread: ${status.writeThread}") // mode debug coroutine menambah "@coroutine#N"
        } finally {
            executor.shutdown()
        }
    }
}
