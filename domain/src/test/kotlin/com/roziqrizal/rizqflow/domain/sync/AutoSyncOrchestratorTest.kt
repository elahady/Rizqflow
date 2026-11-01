@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.model.RoomId
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Langkah palsu: mencatat urutan panggilan dan bisa menahan, menggagalkan, atau melempar. */
private class FakeSteps : AutoSyncSteps {
    val order = mutableListOf<String>()
    val personalQueue = ArrayDeque<ScopeSyncOutcome>()
    var personalDefault: ScopeSyncOutcome = ScopeSyncOutcome.Done(0, 0)
    var hold: CompletableDeferred<Unit>? = null
    var cancelledWhileHeld = false
    var personalThrows: Exception? = null
    var rooms: List<RoomId> = emptyList()
    val roomResults = mutableMapOf<String, ScopeSyncOutcome>()

    val personalCalls: Int get() = order.count { it == "pribadi" }

    override suspend fun personal(): ScopeSyncOutcome {
        order += "pribadi"
        personalThrows?.let { throw it }
        hold?.let { held ->
            try {
                held.await()
            } catch (e: CancellationException) {
                cancelledWhileHeld = true
                throw e
            }
        }
        return personalQueue.removeFirstOrNull() ?: personalDefault
    }

    override suspend fun activeSharedRooms(): List<RoomId> = rooms

    override suspend fun sharedRoom(roomId: RoomId): ScopeSyncOutcome {
        order += roomId.value
        return roomResults[roomId.value] ?: ScopeSyncOutcome.Done(0, 0)
    }
}

private class FakeGate : AutoSyncGate {
    var token: String? = "token-1"
    var entitled = true
    override fun apiToken(): String? = token
    override fun syncEntitled(): Boolean = entitled
}

private class FakeNetwork : AutoSyncNetwork {
    var result = NetworkCheck.OK
    override fun check(): NetworkCheck = result
}

private class Harness(scope: CoroutineScope, scheduler: TestCoroutineScheduler, config: AutoSyncConfig = AutoSyncConfig()) {
    val steps = FakeSteps()
    val gate = FakeGate()
    val network = FakeNetwork()
    val logs = mutableListOf<String>()
    val sync = AutoSyncOrchestrator(
        scope = scope,
        steps = steps,
        gate = gate,
        network = network,
        config = config,
        nowMs = { scheduler.currentTime },
        wallClockMs = { 1_800_000_000_000L + scheduler.currentTime },
        log = { logs += it },
    )
    val phase: AutoSyncPhase get() = sync.state.value.phase
}

private fun TestScope.harness(config: AutoSyncConfig = AutoSyncConfig()) = Harness(backgroundScope, testScheduler, config)

/** Maju [ms] milidetik waktu virtual, lalu jalankan semua yang jatuh tempo tepat di titik itu. */
private fun TestScope.pass(ms: Long) {
    advanceTimeBy(ms)
    runCurrent()
}

private val unreachable = ScopeSyncOutcome.Failed(RemoteError.Unreachable)

/**
 * Sync otomatis tanpa tidur nyata (Tahap 6 sync-desain.md bagian H): pemicu, debounce, jeda minimum, satu penerbangan,
 * backoff, gerbang token/langganan/Wi-Fi, dan upaya latar. Waktu virtual `kotlinx-coroutines-test`.
 */
class AutoSyncOrchestratorTest {
    // ---- Pemicu foreground ----

    @Test
    fun `foreground menjalankan pribadi lalu tiap ruang bersama berurutan`() = runTest {
        val h = harness()
        h.steps.rooms = listOf(RoomId("r1"), RoomId("r2"))

        h.sync.onForeground()
        runCurrent()

        assertEquals(listOf("pribadi", "r1", "r2"), h.steps.order)
        assertEquals(AutoSyncPhase.IDLE, h.phase)
        assertNotNull(h.sync.state.value.lastSyncedAtMs)
    }

    @Test
    fun `jeda minimum 60 detik antar pemicu foreground`() = runTest {
        val h = harness()
        h.sync.onForeground()
        runCurrent()

        pass(59_999)
        h.sync.onForeground()
        runCurrent()
        assertEquals(1, h.steps.personalCalls, "kurang dari 60 detik sejak run terakhir dimulai: dilewati")

        pass(1)
        h.sync.onForeground()
        runCurrent()
        assertEquals(2, h.steps.personalCalls)
    }

    // ---- Debounce penyimpanan lokal ----

    @Test
    fun `debounce 10 detik, penyimpanan beruntun mengulang hitungan dan menjadi satu run`() = runTest {
        val h = harness()
        h.sync.onForeground()
        runCurrent()
        assertEquals(1, h.steps.personalCalls)

        pass(1_000)
        h.sync.onLocalChange()
        pass(4_000)
        h.sync.onLocalChange()
        pass(9_999)
        assertEquals(1, h.steps.personalCalls, "10 detik dihitung dari penyimpanan TERAKHIR")

        pass(1)
        assertEquals(2, h.steps.personalCalls)

        pass(60_000)
        assertEquals(2, h.steps.personalCalls, "satu run untuk semua penyimpanan beruntun")
    }

    @Test
    fun `penyimpanan saat aplikasi tidak di depan tidak memicu sync`() = runTest {
        val h = harness()

        h.sync.onLocalChange()
        pass(120_000)

        assertEquals(0, h.steps.personalCalls)
    }

    // ---- Satu penerbangan ----

    @Test
    fun `pemicu otomatis bergabung ke run yang berjalan dan perubahan selama run memicu satu run ulang`() = runTest {
        val h = harness()
        val held = CompletableDeferred<Unit>()
        h.steps.hold = held

        h.sync.onForeground()
        runCurrent()
        assertEquals(AutoSyncPhase.SYNCING, h.phase)
        assertEquals(1, h.steps.personalCalls)

        h.sync.resume()
        h.sync.onForeground()
        h.sync.onLocalChange()
        pass(10_000)
        assertEquals(1, h.steps.personalCalls, "tidak ada run kedua selama run pertama berjalan")

        held.complete(Unit)
        runCurrent()
        assertEquals(1, h.steps.personalCalls)
        assertEquals(AutoSyncPhase.IDLE, h.phase)

        pass(10_000)
        assertEquals(2, h.steps.personalCalls, "perubahan yang tiba selama run diulang setelahnya (debounce)")
    }

    @Test
    fun `tombol manual menunggu run otomatis lalu menjalankan run sendiri`() = runTest {
        val h = harness()
        val held = CompletableDeferred<Unit>()
        h.steps.hold = held
        h.sync.onForeground()
        runCurrent()

        val manual = async { h.sync.syncNow() }
        runCurrent()
        assertEquals(1, h.steps.personalCalls, "run manual menunggu, bukan berjalan bersamaan")

        held.complete(Unit)
        runCurrent()

        assertIs<AutoSyncReport.Synced>(manual.await())
        assertEquals(2, h.steps.personalCalls, "hasil tombol adalah run miliknya sendiri")
    }

    // ---- Backoff ----

    @Test
    fun `gagal jaringan dicoba ulang 30 detik, 60, 120 selama di depan lalu berhenti setelah sukses`() = runTest {
        val h = harness()
        h.steps.personalQueue += listOf(unreachable, unreachable, unreachable)

        h.sync.onForeground()
        runCurrent()
        assertEquals(1, h.steps.personalCalls)
        assertEquals(AutoSyncPhase.BACKOFF, h.phase)

        pass(29_999)
        assertEquals(1, h.steps.personalCalls)
        pass(1)
        assertEquals(2, h.steps.personalCalls)

        pass(59_999)
        assertEquals(2, h.steps.personalCalls)
        pass(1)
        assertEquals(3, h.steps.personalCalls)

        pass(119_999)
        assertEquals(3, h.steps.personalCalls)
        pass(1)
        assertEquals(4, h.steps.personalCalls)
        assertEquals(AutoSyncPhase.IDLE, h.phase, "percobaan keempat berhasil")

        pass(3_600_000)
        assertEquals(4, h.steps.personalCalls, "tidak ada percobaan ulang setelah sukses")
    }

    @Test
    fun `jeda berhenti bertambah di 15 menit`() = runTest {
        val h = harness()
        h.steps.personalDefault = unreachable

        h.sync.onForeground()
        runCurrent()
        // Jeda: 30, 60, 120, 240, 480, 900, 900 detik -> percobaan ke-8 di detik 2730.
        pass(2_729_999)
        assertEquals(7, h.steps.personalCalls)
        pass(1)
        assertEquals(8, h.steps.personalCalls)
        pass(899_999)
        assertEquals(8, h.steps.personalCalls)
        pass(1)
        assertEquals(9, h.steps.personalCalls)
    }

    @Test
    fun `penyimpanan lokal saat dalam jeda tidak mengirim request sampai jeda habis`() = runTest {
        val h = harness()
        h.steps.personalQueue += unreachable

        h.sync.onForeground()
        runCurrent()
        pass(5_000)
        h.sync.onLocalChange()
        pass(10_000)

        assertEquals(1, h.steps.personalCalls, "debounce jatuh tempo di detik 15, jeda baru habis di detik 30")
        assertEquals(AutoSyncPhase.BACKOFF, h.phase)

        pass(15_000)
        assertEquals(2, h.steps.personalCalls)
        assertEquals(AutoSyncPhase.IDLE, h.phase)
    }

    @Test
    fun `429 mengikuti Retry-After`() = runTest {
        val h = harness()
        h.steps.personalQueue += ScopeSyncOutcome.Failed(RemoteError.Unexpected(429, retryAfterSeconds = 120))

        h.sync.onForeground()
        runCurrent()
        pass(119_999)
        assertEquals(1, h.steps.personalCalls)
        pass(1)
        assertEquals(2, h.steps.personalCalls)
    }

    @Test
    fun `sync manual mengabaikan jeda dan saklar hanya Wi-Fi lalu mereset jeda`() = runTest {
        val h = harness()
        h.steps.personalQueue += unreachable
        h.sync.onForeground()
        runCurrent()
        assertEquals(AutoSyncPhase.BACKOFF, h.phase)
        h.network.result = NetworkCheck.WIFI_ONLY_BLOCKED

        val report = h.sync.syncNow()

        assertIs<AutoSyncReport.Synced>(report)
        assertEquals(2, h.steps.personalCalls)
        assertEquals(AutoSyncPhase.IDLE, h.phase)

        // Jeda sudah direset: kegagalan berikutnya mulai lagi dari 30 detik, bukan 60.
        h.steps.personalQueue += unreachable
        h.sync.syncNow()
        h.network.result = NetworkCheck.OK
        pass(30_000)
        assertEquals(4, h.steps.personalCalls)
    }

    @Test
    fun `kegagalan satu ruang bersama karena jaringan menghentikan sisanya dan memberi jeda`() = runTest {
        val h = harness()
        h.steps.rooms = listOf(RoomId("r1"), RoomId("r2"))
        h.steps.roomResults["r1"] = unreachable

        h.sync.onForeground()
        runCurrent()

        assertEquals(listOf("pribadi", "r1"), h.steps.order)
        assertEquals(AutoSyncPhase.BACKOFF, h.phase)
    }

    // ---- 401 dan 403 ----

    @Test
    fun `401 menghentikan sync otomatis sampai token berganti`() = runTest {
        val h = harness()
        h.steps.personalQueue += ScopeSyncOutcome.Failed(RemoteError.Unauthorized)

        h.sync.onForeground()
        runCurrent()
        assertEquals(AutoSyncPhase.NEEDS_RECONNECT, h.phase)

        pass(1_200_000)
        h.sync.onForeground()
        h.sync.onLocalChange()
        pass(20_000)
        assertEquals(1, h.steps.personalCalls, "tidak ada percobaan ulang dan tidak ada run dengan token yang sama")

        h.gate.token = "token-2"
        h.sync.onForeground()
        runCurrent()
        assertEquals(2, h.steps.personalCalls)
        assertEquals(AutoSyncPhase.IDLE, h.phase)
    }

    @Test
    fun `403 pada cakupan pribadi menghentikan sync dan menandai ajakan berlangganan`() = runTest {
        val h = harness()
        h.steps.personalQueue += ScopeSyncOutcome.Failed(RemoteError.SyncRequired)

        h.sync.onForeground()
        runCurrent()
        assertEquals(AutoSyncPhase.NEEDS_SUBSCRIPTION, h.phase)

        pass(120_000)
        h.sync.onForeground()
        pass(20_000)
        assertEquals(1, h.steps.personalCalls, "berhenti sampai resume atau tombol manual")

        h.sync.resume()
        runCurrent()
        assertEquals(2, h.steps.personalCalls)
        assertEquals(AutoSyncPhase.IDLE, h.phase)
    }

    @Test
    fun `tombol manual setelah 403 mencoba lagi dan melaporkan butuh langganan bila masih ditolak`() = runTest {
        val h = harness()
        h.steps.personalDefault = ScopeSyncOutcome.Failed(RemoteError.SyncRequired)

        val report = h.sync.syncNow()

        assertEquals(AutoSyncReport.NeedsSubscription, report)
        assertEquals(AutoSyncPhase.NEEDS_SUBSCRIPTION, h.phase)
        assertEquals(1, h.steps.personalCalls)
    }

    @Test
    fun `403 pada satu ruang bersama hanya melewati ruang itu`() = runTest {
        val h = harness()
        h.steps.rooms = listOf(RoomId("r1"), RoomId("r2"), RoomId("r3"))
        h.steps.roomResults["r2"] = ScopeSyncOutcome.Failed(RemoteError.SyncRequired)

        val report = h.sync.syncNow()

        assertEquals(listOf("pribadi", "r1", "r2", "r3"), h.steps.order)
        assertEquals(1, assertIs<AutoSyncReport.Synced>(report).roomsSkipped)
        assertEquals(AutoSyncPhase.IDLE, h.phase, "bukan berarti langganan habis")
    }

    // ---- Gerbang ----

    @Test
    fun `tanpa token server tidak ada request sama sekali`() = runTest {
        val h = harness()
        h.gate.token = null

        h.sync.onForeground()
        h.sync.onLocalChange()
        runCurrent()
        pass(20_000)
        val report = h.sync.syncNow()

        assertEquals(0, h.steps.order.size)
        assertEquals(AutoSyncReport.NotSignedIn, report)
        assertEquals(AutoSyncPhase.NEEDS_RECONNECT, h.phase)
    }

    @Test
    fun `tanpa langganan Sync tidak ada request sama sekali`() = runTest {
        val h = harness()
        h.gate.entitled = false

        h.sync.onForeground()
        h.sync.onLocalChange()
        runCurrent()
        pass(20_000)
        val report = h.sync.syncNow()

        assertEquals(0, h.steps.order.size)
        assertEquals(AutoSyncReport.NeedsSubscription, report)
        assertEquals(AutoSyncPhase.NEEDS_SUBSCRIPTION, h.phase)
    }

    @Test
    fun `saklar hanya Wi-Fi menahan sync otomatis di jaringan berbayar`() = runTest {
        val h = harness()
        h.network.result = NetworkCheck.WIFI_ONLY_BLOCKED

        h.sync.onForeground()
        runCurrent()
        assertEquals(0, h.steps.personalCalls)
        assertEquals(AutoSyncPhase.WIFI_ONLY, h.phase)

        h.network.result = NetworkCheck.OK
        h.sync.onForeground()
        runCurrent()
        assertEquals(1, h.steps.personalCalls, "pemicu yang ditahan jaringan tidak menghabiskan jeda minimum")
    }

    @Test
    fun `tanpa jaringan fasenya offline dan tidak ada request`() = runTest {
        val h = harness()
        h.network.result = NetworkCheck.NO_NETWORK

        h.sync.onForeground()
        runCurrent()

        assertEquals(0, h.steps.personalCalls)
        assertEquals(AutoSyncPhase.OFFLINE, h.phase)
    }

    // ---- Latar ----

    @Test
    fun `masuk latar tanpa perubahan belum terkirim tidak menjalankan sync`() = runTest {
        val h = harness()
        h.sync.onForeground()
        runCurrent()

        h.sync.onBackground()
        runCurrent()

        assertEquals(1, h.steps.personalCalls)
    }

    @Test
    fun `masuk latar dengan perubahan menjalankan satu upaya dan membatalkan timer debounce`() = runTest {
        val h = harness()
        h.sync.onForeground()
        runCurrent()
        h.sync.onLocalChange()

        h.sync.onBackground()
        runCurrent()
        assertEquals(2, h.steps.personalCalls)

        pass(120_000)
        assertEquals(2, h.steps.personalCalls, "timer debounce dibatalkan, tidak ada sync di latar selain satu upaya itu")
    }

    @Test
    fun `upaya latar dibatasi sekitar 5 detik lalu dilepas`() = runTest {
        val h = harness()
        h.sync.onForeground()
        runCurrent()
        h.sync.onLocalChange()
        h.steps.hold = CompletableDeferred() // tidak pernah selesai: server menggantung

        h.sync.onBackground()
        runCurrent()
        assertEquals(2, h.steps.personalCalls)
        assertEquals(AutoSyncPhase.SYNCING, h.phase)

        pass(4_999)
        assertTrue(!h.steps.cancelledWhileHeld)
        pass(1)
        assertTrue(h.steps.cancelledWhileHeld, "upaya latar dibatalkan di batas 5 detik")
        assertEquals(AutoSyncPhase.IDLE, h.phase, "tidak boleh tersangkut di SYNCING")

        // Kunci run terlepas: pemicu berikutnya berjalan normal, dan perubahan yang belum terkirim tidak dilupakan.
        h.steps.hold = null
        pass(60_000)
        h.sync.onForeground()
        runCurrent()
        assertEquals(3, h.steps.personalCalls)
    }

    @Test
    fun `upaya latar tunduk pada jeda`() = runTest {
        val h = harness()
        h.steps.personalQueue += unreachable
        h.sync.onForeground()
        runCurrent()
        assertEquals(1, h.steps.personalCalls)

        h.sync.onBackground()
        runCurrent()

        assertEquals(1, h.steps.personalCalls, "sedang dalam jeda: tidak ada upaya latar")
    }

    // ---- Ketahanan ----

    @Test
    fun `langkah yang melempar tidak menjatuhkan aplikasi dan memberi jeda`() = runTest {
        val h = harness()
        h.steps.personalThrows = IllegalStateException("bug")

        h.sync.onForeground()
        runCurrent()

        assertEquals(AutoSyncPhase.BACKOFF, h.phase)
        assertTrue(h.logs.any { "IllegalStateException" in it })
    }

    @Test
    fun `token hilang di tengah jalan dilaporkan tanpa jeda dan tanpa percobaan ulang`() = runTest {
        val h = harness()
        h.steps.personalQueue += ScopeSyncOutcome.NotSignedIn

        h.sync.onForeground()
        runCurrent()
        pass(3_600_000)

        assertEquals(AutoSyncPhase.NEEDS_RECONNECT, h.phase)
        assertEquals(1, h.steps.personalCalls)
    }

    @Test
    fun `push yang menunggu kode pemulihan tampil di state dan hilang setelah dikonfigurasi`() = runTest {
        val h = harness()
        h.steps.personalQueue += ScopeSyncOutcome.Done(pushed = 0, applied = 3, pushWaitsForRecovery = true)
        h.sync.onForeground()
        runCurrent()
        assertTrue(h.sync.state.value.pushWaitsForRecovery)

        pass(60_000)
        h.sync.onForeground()
        runCurrent()
        assertTrue(!h.sync.state.value.pushWaitsForRecovery)
    }

    @Test
    fun `push tertahan gerbang pemulihan tidak dianggap perubahan belum terkirim untuk upaya latar`() = runTest {
        val h = harness()
        h.steps.personalQueue += ScopeSyncOutcome.Done(pushed = 0, applied = 0, pushWaitsForRecovery = true)
        h.sync.onForeground()
        runCurrent()

        h.sync.onBackground()
        runCurrent()

        assertEquals(1, h.steps.personalCalls)
    }
}
