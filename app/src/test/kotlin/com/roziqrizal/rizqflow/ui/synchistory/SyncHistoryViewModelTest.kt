package com.roziqrizal.rizqflow.ui.synchistory

import com.roziqrizal.rizqflow.domain.sync.FullResyncOutcome
import com.roziqrizal.rizqflow.domain.sync.LocalSyncHistory
import com.roziqrizal.rizqflow.domain.sync.PersonalSyncResult
import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import com.roziqrizal.rizqflow.domain.sync.ScopeSyncStatus
import com.roziqrizal.rizqflow.domain.sync.ServerSyncDevice
import com.roziqrizal.rizqflow.domain.sync.ServerSyncHistory
import com.roziqrizal.rizqflow.domain.sync.SyncCompactionInfo
import com.roziqrizal.rizqflow.domain.sync.SyncHealth
import com.roziqrizal.rizqflow.domain.sync.SyncHistoryRemote
import com.roziqrizal.rizqflow.domain.sync.SyncIssue
import com.roziqrizal.rizqflow.domain.sync.SyncIssueKind
import com.roziqrizal.rizqflow.domain.sync.SyncIssuePolicy
import com.roziqrizal.rizqflow.domain.sync.SyncIssueRow
import com.roziqrizal.rizqflow.domain.sync.SyncQuotaUsage
import com.roziqrizal.rizqflow.domain.sync.SyncScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SyncHistoryViewModel] dengan pengganti sederhana (bukan mock jaringan): bagian lokal selalu tampil, bagian server
 * menyusul atau gagal dengan lembut, penilaian status, dan alur "sinkron ulang penuh".
 */
class SyncHistoryViewModelTest {
    private val now = 10_000_000L

    private fun local(dirty: Int = 0, issues: List<SyncIssue> = emptyList()): LocalSyncHistory {
        val rows = issues.map { SyncIssueRow(it, SyncIssuePolicy.status(it, now)) }
        return LocalSyncHistory(
            scopes = listOf(ScopeSyncStatus(SyncScope.Personal, null, dirty, 12)),
            issues = rows,
            issueTotal = rows.size,
            summary = SyncIssuePolicy.summarize(issues, now),
        )
    }

    private fun serverHistory(rows: Long = 10) = ServerSyncHistory(
        devices = listOf(
            ServerSyncDevice("hp-ini", 7, null, null, 33, true),
            ServerSyncDevice("hp-lain", 3, null, null, 30, false),
        ),
        quota = SyncQuotaUsage(rows, 1_000, 250_000, 157_286_400),
        compaction = SyncCompactionInfo(null, 0, 0),
    )

    private class FakeRemote(var result: RemoteResult<ServerSyncHistory>) : SyncHistoryRemote {
        val tokens = mutableListOf<String>()

        override suspend fun history(apiToken: String): RemoteResult<ServerSyncHistory> {
            tokens += apiToken
            return result
        }
    }

    private fun viewModel(
        remote: SyncHistoryRemote,
        token: String? = "token-hp",
        localHistory: () -> LocalSyncHistory = { local() },
        resync: suspend () -> FullResyncOutcome = { error("tidak dipanggil") },
    ) = SyncHistoryViewModel(
        readLocal = { localHistory() },
        remote = remote,
        tokenProvider = { token },
        deviceId = "hp-ini",
        runFullResync = resync,
    )

    private fun missingRef(attempts: Int = 0, at: Long = now - 1_000) =
        SyncIssue(SyncScope.Personal, "transaction", "t-1", SyncIssueKind.DEFERRED_MISSING_REF, serverId = 5, attempts = attempts, createdAtMillis = at, id = 1)

    @Test
    fun `memuat bagian lokal dan server, memilah perangkat ini dari perangkat lain`() = runBlocking {
        val remote = FakeRemote(RemoteResult.Success(serverHistory()))
        val vm = viewModel(remote)

        vm.load()

        val state = vm.state.value
        assertEquals(false, state.loading)
        assertNotNull(state.local)
        assertEquals("hp-ini", state.thisDevice?.deviceId)
        assertEquals(listOf("hp-lain"), state.otherDevices.map { it.deviceId })
        assertNull(state.serverFailure)
        assertEquals(SyncHealth.CALM, state.health)
        assertEquals(listOf("token-hp"), remote.tokens)
    }

    @Test
    fun `tanpa token bagian lokal tetap tampil dan server ditandai sesi berakhir tanpa memanggil jaringan`() = runBlocking {
        val remote = FakeRemote(RemoteResult.Success(serverHistory()))
        val vm = viewModel(remote, token = null, localHistory = { local(dirty = 2) })

        vm.load()

        val state = vm.state.value
        assertEquals(SyncHistoryFailure.SessionEnded, state.serverFailure)
        assertNull(state.server)
        assertEquals(2, state.local?.totalDirty)
        assertEquals(SyncHealth.WAITING, state.health)
        assertTrue(remote.tokens.isEmpty())
    }

    @Test
    fun `server tidak terjangkau dan galat lain dipetakan dengan lembut`() = runBlocking {
        val remote = FakeRemote(RemoteResult.Failure(RemoteError.Unreachable))
        val vm = viewModel(remote)

        vm.load()
        assertEquals(SyncHistoryFailure.Offline, vm.state.value.serverFailure)
        assertNotNull(vm.state.value.local, "bagian lokal tidak ikut hilang")

        remote.result = RemoteResult.Failure(RemoteError.Unexpected(500))
        vm.load()
        assertEquals(SyncHistoryFailure.Other, vm.state.value.serverFailure)

        remote.result = RemoteResult.Failure(RemoteError.Unauthorized)
        vm.load()
        assertEquals(SyncHistoryFailure.SessionEnded, vm.state.value.serverFailure)

        remote.result = RemoteResult.Success(serverHistory())
        vm.load()
        assertNull(vm.state.value.serverFailure, "muat ulang yang berhasil menghapus galat sebelumnya")
    }

    @Test
    fun `gagal permanen dan kuota penuh dinilai butuh perhatian`() = runBlocking {
        val permanent = missingRef(attempts = SyncIssuePolicy.MAX_ATTEMPTS)
        val vm = viewModel(FakeRemote(RemoteResult.Success(serverHistory())), localHistory = { local(issues = listOf(permanent)) })
        vm.load()
        assertEquals(SyncHealth.NEEDS_ATTENTION, vm.state.value.health)

        val full = viewModel(FakeRemote(RemoteResult.Success(serverHistory(rows = 250_000))))
        full.load()
        assertEquals(SyncHealth.NEEDS_ATTENTION, full.state.value.health)
    }

    @Test
    fun `sinkron ulang penuh sukses menampilkan jumlah baris dan memuat ulang riwayat`() = runBlocking {
        var dirty = 3
        val outcome = FullResyncOutcome(PersonalSyncResult.Success(pushed = 0, applied = 8, skipped = 0, pushGated = false), emptyMap())
        val vm = viewModel(
            FakeRemote(RemoteResult.Success(serverHistory())),
            localHistory = { local(dirty = dirty) },
            resync = {
                dirty = 0
                outcome
            },
        )
        vm.load()
        assertEquals(SyncHealth.WAITING, vm.state.value.health)

        vm.fullResync()

        assertEquals(ResyncState.Done(applied = 8), vm.state.value.resync)
        assertEquals(SyncHealth.CALM, vm.state.value.health, "layar dimuat ulang sesudah sinkron ulang")
    }

    @Test
    fun `sinkron ulang yang gagal memetakan alasan dan tidak menyentuh bagian lokal`() = runBlocking {
        suspend fun failWith(personal: PersonalSyncResult): ResyncState {
            val vm = viewModel(FakeRemote(RemoteResult.Success(serverHistory())), resync = { FullResyncOutcome(personal, emptyMap()) })
            vm.load()
            vm.fullResync()
            assertNotNull(vm.state.value.local)
            return vm.state.value.resync
        }

        assertEquals(ResyncState.Failed(SyncHistoryFailure.Offline), failWith(PersonalSyncResult.Failed(RemoteError.Unreachable)))
        assertEquals(ResyncState.Failed(SyncHistoryFailure.SessionEnded), failWith(PersonalSyncResult.Failed(RemoteError.Unauthorized)))
        assertEquals(ResyncState.Failed(SyncHistoryFailure.SessionEnded), failWith(PersonalSyncResult.NotSignedIn))
        assertEquals(ResyncState.Failed(SyncHistoryFailure.Other), failWith(PersonalSyncResult.Failed(RemoteError.Unexpected(500))))
    }

    @Test
    fun `galat tak terduga saat sinkron ulang menjadi pesan lembut, bukan crash`() = runBlocking {
        val vm = viewModel(FakeRemote(RemoteResult.Success(serverHistory())), resync = { throw IllegalStateException("rusak") })
        vm.load()

        vm.fullResync()

        assertEquals(ResyncState.Failed(SyncHistoryFailure.Other), vm.state.value.resync)
    }

    @Test
    fun `sinkron ulang tidak jalan dua kali bersamaan`() = runBlocking {
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val vm = viewModel(
            FakeRemote(RemoteResult.Success(serverHistory())),
            resync = {
                calls++
                gate.await()
                FullResyncOutcome(PersonalSyncResult.Success(0, 0, 0, false), emptyMap())
            },
        )
        vm.load()

        val first = launch { vm.fullResync() }
        yield()
        assertEquals(ResyncState.Running, vm.state.value.resync)
        vm.fullResync() // dipanggil lagi selagi berjalan: diabaikan
        gate.complete(Unit)
        first.join()

        assertEquals(1, calls)
        assertIs<ResyncState.Done>(vm.state.value.resync)
    }

    @Test
    fun `pesan hasil sinkron ulang bisa ditutup, tetapi tidak menutup yang sedang berjalan`() = runBlocking {
        val vm = viewModel(
            FakeRemote(RemoteResult.Success(serverHistory())),
            resync = { FullResyncOutcome(PersonalSyncResult.Success(0, 0, 0, false), emptyMap()) },
        )
        vm.load()

        vm.fullResync()
        assertEquals(ResyncState.Done(applied = 0), vm.state.value.resync)
        vm.dismissResyncResult()

        assertEquals(ResyncState.Idle, vm.state.value.resync)
    }
}
