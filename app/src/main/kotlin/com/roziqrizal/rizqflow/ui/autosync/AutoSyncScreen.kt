package com.roziqrizal.rizqflow.ui.autosync

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.sync.AutoSyncPhase
import com.roziqrizal.rizqflow.ui.Notifier
import com.roziqrizal.rizqflow.ui.RizqflowIcons
import com.roziqrizal.rizqflow.ui.theme.spacing
import com.roziqrizal.rizqflow.workspace.AccountWorkspace
import kotlinx.coroutines.launch

/**
 * "Sinkron otomatis" (menu Lainnya, Tahap 6): status sync yang lembut, saklar "hanya Wi-Fi" (keputusan pemilik #15), dan
 * tombol "Sinkronkan sekarang" (mengabaikan jeda dan saklar). Logika ada di `AutoSyncOrchestrator`; layar ini hanya
 * menampilkan `state`-nya. Server yang menolak karena belum berlangganan (403) membuka paywall varian Sync lewat
 * [onOpenSyncPaywall] (`SyncGate`), bukan pesan gagal.
 */
@Composable
fun AutoSyncScreen(
    workspace: AccountWorkspace,
    notifier: Notifier,
    onOpenSyncPaywall: () -> Unit,
    onClose: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val scope = rememberCoroutineScope()
    val state by workspace.syncOrchestrator.state.collectAsState()
    var wifiOnly by remember(workspace) { mutableStateOf(workspace.autoSyncSettings.wifiOnly) }
    var manualBusy by remember { mutableStateOf(false) }
    val resources = androidx.compose.ui.platform.LocalResources.current

    BackHandler(onBack = onClose)

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        TextButton(onClick = onClose, modifier = Modifier.padding(start = spacing.s3, top = spacing.s2)) { Text(stringResource(R.string.onb_back)) }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.s5)
                .padding(bottom = spacing.s5),
        ) {
            Text(stringResource(R.string.sync_auto_title), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.sync_auto_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.s2),
            )

            // Status: ikon plus teks, bukan hanya warna.
            Row(
                modifier = Modifier
                    .padding(top = spacing.s5)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(16.dp))
                    .padding(spacing.s4),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    when (state.phase) {
                        AutoSyncPhase.IDLE -> if (state.pushWaitsForRecovery) RizqflowIcons.Lingkaran else RizqflowIcons.Centang
                        AutoSyncPhase.SYNCING -> RizqflowIcons.Transfer
                        AutoSyncPhase.NEEDS_RECONNECT, AutoSyncPhase.NEEDS_SUBSCRIPTION -> RizqflowIcons.Peringatan
                        AutoSyncPhase.BACKOFF, AutoSyncPhase.OFFLINE, AutoSyncPhase.WIFI_ONLY -> RizqflowIcons.Jam
                    },
                    contentDescription = null,
                    modifier = Modifier.padding(end = spacing.s3).size(20.dp),
                )
                Column {
                    Text(stringResource(state.messageRes()), style = MaterialTheme.typography.bodyMedium)
                    val last = state.lastSyncedAtMs
                    Text(
                        if (last == null) {
                            stringResource(R.string.sync_last_never)
                        } else {
                            stringResource(
                                R.string.sync_last_at,
                                // Jam dimundurkan setelah sync: jangan tampilkan "dalam sekian menit" (waktu masa depan).
                                System.currentTimeMillis().let { now ->
                                    DateUtils.getRelativeTimeSpanString(minOf(last, now), now, DateUtils.MINUTE_IN_MILLIS).toString()
                                },
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = spacing.s1),
                    )
                }
            }
            if (state.phase == AutoSyncPhase.NEEDS_SUBSCRIPTION) {
                OutlinedButton(onClick = onOpenSyncPaywall, modifier = Modifier.padding(top = spacing.s3)) {
                    Text(stringResource(R.string.sync_subscribe))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = spacing.s5),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.sync_wifi_only_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).padding(end = spacing.s3),
                )
                Switch(
                    checked = wifiOnly,
                    onCheckedChange = {
                        wifiOnly = it
                        workspace.autoSyncSettings.wifiOnly = it
                    },
                )
            }
            Text(
                stringResource(R.string.sync_wifi_only_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.s1),
            )

            Button(
                onClick = {
                    manualBusy = true
                    scope.launch {
                        val feedback = workspace.syncOrchestrator.syncNow().feedback()
                        manualBusy = false
                        when (feedback) {
                            is ManualSyncFeedback.Message -> notifier.show(resources.getString(feedback.res))
                            ManualSyncFeedback.OpenSyncPaywall -> onOpenSyncPaywall()
                        }
                    }
                },
                enabled = !manualBusy && state.phase != AutoSyncPhase.SYNCING,
                modifier = Modifier.padding(top = spacing.s5).fillMaxWidth(),
            ) {
                Text(stringResource(if (manualBusy || state.phase == AutoSyncPhase.SYNCING) R.string.sync_now_busy else R.string.sync_now))
            }
        }
    }
}
