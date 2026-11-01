package com.roziqrizal.rizqflow.ui.autosync

import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.sync.AutoSyncPhase
import com.roziqrizal.rizqflow.domain.sync.AutoSyncReport
import com.roziqrizal.rizqflow.domain.sync.AutoSyncState
import com.roziqrizal.rizqflow.domain.sync.NetworkKind
import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.sync.networkKindOf
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pesan status sync yang lembut, kabar tombol manual, dan pembukaan paywall Sync (`SyncGate`) untuk 403. */
class AutoSyncMessagesTest {
    @Test
    fun `setiap fase punya pesan sendiri dan push yang menunggu kode pemulihan punya ajakan sendiri`() {
        fun res(phase: AutoSyncPhase, waits: Boolean = false) = AutoSyncState(phase, pushWaitsForRecovery = waits).messageRes()

        assertEquals(R.string.sync_status_idle, res(AutoSyncPhase.IDLE))
        assertEquals(R.string.sync_status_push_waits, res(AutoSyncPhase.IDLE, waits = true))
        assertEquals(R.string.sync_status_syncing, res(AutoSyncPhase.SYNCING))
        assertEquals(R.string.sync_status_backoff, res(AutoSyncPhase.BACKOFF))
        assertEquals(R.string.sync_status_offline, res(AutoSyncPhase.OFFLINE))
        assertEquals(R.string.sync_status_wifi_only, res(AutoSyncPhase.WIFI_ONLY))
        assertEquals(R.string.sync_status_reconnect, res(AutoSyncPhase.NEEDS_RECONNECT))
        assertEquals(R.string.sync_status_subscription, res(AutoSyncPhase.NEEDS_SUBSCRIPTION))
    }

    @Test
    fun `tombol manual yang berhasil memberi kabar selesai, atau ajakan kode pemulihan bila push tertahan`() {
        assertEquals(
            ManualSyncFeedback.Message(R.string.sync_manual_done),
            AutoSyncReport.Synced(pushed = 1, applied = 1, pushWaitsForRecovery = false).feedback(),
        )
        assertEquals(
            ManualSyncFeedback.Message(R.string.sync_status_push_waits),
            AutoSyncReport.Synced(pushed = 0, applied = 0, pushWaitsForRecovery = true).feedback(),
        )
    }

    @Test
    fun `butuh langganan Sync membuka paywall Sync, bukan pesan gagal`() {
        assertEquals(ManualSyncFeedback.OpenSyncPaywall, AutoSyncReport.NeedsSubscription.feedback())
        assertEquals(ManualSyncFeedback.OpenSyncPaywall, AutoSyncReport.Failed(RemoteError.SyncRequired).feedback())
    }

    @Test
    fun `sesi berakhir mengajak sambung ulang, gangguan lain memberi pesan lembut`() {
        assertEquals(ManualSyncFeedback.Message(R.string.sync_status_reconnect), AutoSyncReport.NotSignedIn.feedback())
        assertEquals(ManualSyncFeedback.Message(R.string.sync_status_reconnect), AutoSyncReport.Failed(RemoteError.Unauthorized).feedback())
        assertEquals(ManualSyncFeedback.Message(R.string.sync_manual_failed), AutoSyncReport.Failed(RemoteError.Unreachable).feedback())
        assertEquals(ManualSyncFeedback.Message(R.string.sync_manual_failed), AutoSyncReport.Failed(RemoteError.Unexpected(503)).feedback())
    }

    @Test
    fun `jaringan dinilai tidak berbayar hanya bila ada internet dan Android menandainya tidak berbayar`() {
        assertEquals(NetworkKind.NONE, networkKindOf(hasInternet = false, notMetered = true))
        assertEquals(NetworkKind.UNMETERED, networkKindOf(hasInternet = true, notMetered = true))
        assertEquals(NetworkKind.METERED, networkKindOf(hasInternet = true, notMetered = false))
    }
}
