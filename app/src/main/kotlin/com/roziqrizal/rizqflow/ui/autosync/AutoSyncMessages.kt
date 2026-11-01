package com.roziqrizal.rizqflow.ui.autosync

import androidx.annotation.StringRes
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.sync.AutoSyncPhase
import com.roziqrizal.rizqflow.domain.sync.AutoSyncReport
import com.roziqrizal.rizqflow.domain.sync.AutoSyncState
import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.ui.paywall.opensSyncPaywall

/**
 * Pesan status sync yang lembut ("tenang, bukan panik"): tidak pernah menyebut galat teknis, selalu menegaskan data aman
 * di ponsel bila sync belum jalan, dan selalu memberi jalan berikutnya. Status juga ditandai dengan ikon di layarnya,
 * tidak hanya warna.
 */
@StringRes
fun AutoSyncState.messageRes(): Int = when (phase) {
    AutoSyncPhase.IDLE -> if (pushWaitsForRecovery) R.string.sync_status_push_waits else R.string.sync_status_idle
    AutoSyncPhase.SYNCING -> R.string.sync_status_syncing
    AutoSyncPhase.BACKOFF -> R.string.sync_status_backoff
    AutoSyncPhase.OFFLINE -> R.string.sync_status_offline
    AutoSyncPhase.WIFI_ONLY -> R.string.sync_status_wifi_only
    AutoSyncPhase.NEEDS_RECONNECT -> R.string.sync_status_reconnect
    AutoSyncPhase.NEEDS_SUBSCRIPTION -> R.string.sync_status_subscription
}

/** Apa yang dilakukan layar setelah tombol "Sinkronkan sekarang": satu kabar, atau membuka ajakan berlangganan. */
sealed interface ManualSyncFeedback {
    data class Message(@StringRes val res: Int) : ManualSyncFeedback

    /** `SyncGate`: server menjawab 403 atau paket Sync tidak dimiliki; buka paywall varian Sync, bukan pesan gagal. */
    data object OpenSyncPaywall : ManualSyncFeedback
}

fun AutoSyncReport.feedback(): ManualSyncFeedback = when (this) {
    is AutoSyncReport.Synced ->
        ManualSyncFeedback.Message(if (pushWaitsForRecovery) R.string.sync_status_push_waits else R.string.sync_manual_done)

    AutoSyncReport.NeedsSubscription -> ManualSyncFeedback.OpenSyncPaywall
    AutoSyncReport.NotSignedIn -> ManualSyncFeedback.Message(R.string.sync_status_reconnect)
    is AutoSyncReport.Failed -> when {
        error.opensSyncPaywall() -> ManualSyncFeedback.OpenSyncPaywall
        error == RemoteError.Unauthorized -> ManualSyncFeedback.Message(R.string.sync_status_reconnect)
        else -> ManualSyncFeedback.Message(R.string.sync_manual_failed)
    }
}
