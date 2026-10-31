package com.roziqrizal.rizqflow.ui.recovery

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.sync.NewDeviceRecoveryOutcome
import com.roziqrizal.rizqflow.domain.sync.NewDeviceRestoreFlow
import com.roziqrizal.rizqflow.domain.sync.NewDeviceRestoreResult
import com.roziqrizal.rizqflow.domain.sync.OverwritePreparation
import com.roziqrizal.rizqflow.ui.Notifier
import com.roziqrizal.rizqflow.ui.RizqflowIcons
import com.roziqrizal.rizqflow.ui.theme.spacing
import kotlinx.coroutines.launch

/** Tahap tampilan alur perangkat baru; logika keputusannya ada di [NewDeviceRestoreFlow] (`:domain`). */
private sealed interface NewDeviceStage {
    data object Checking : NewDeviceStage
    data object Choose : NewDeviceStage
    data class Working(val messageRes: Int) : NewDeviceStage

    /** Salinan otomatis sudah dibuat; menunggu konfirmasi eksplisit sebelum menghapus. */
    data class ConfirmOverwrite(val ready: OverwritePreparation.Ready) : NewDeviceStage

    /** Gagal; [canRetrySync] true bila tarik dari server bisa diulang (data lokal mungkin sudah dibuang). */
    data class Failed(val messageRes: Int, val canRetrySync: Boolean) : NewDeviceStage
}

/**
 * Alur perangkat baru setelah kunci akun dipulihkan (sync-desain.md bagian B "Perangkat baru", keputusan pemilik
 * #11). Saat dibuka langsung memanggil [NewDeviceRestoreFlow.decide]: tidak ada salinan di server = tutup diam-diam;
 * ruang kerja murni = ganti otomatis dengan data server; tidak murni = pengguna memilih **Gabung** (tidak menghapus
 * apa pun) atau **Pulihkan** (salinan otomatis berkas database lebih dulu, lalu konfirmasi eksplisit yang menyebut
 * salinan itu, baru data lokal diganti). Gagal tarik dari server bisa diulang dari layar ini.
 *
 * Belum diuji di perangkat atau emulator (hanya lolos kompilasi; logika diuji di `NewDeviceRestoreFlowTest`).
 */
@Composable
fun NewDeviceRestoreScreen(
    flow: NewDeviceRestoreFlow,
    notifier: Notifier,
    onDone: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val context = LocalContext.current
    var stage by remember { mutableStateOf<NewDeviceStage>(NewDeviceStage.Checking) }
    val busy = stage is NewDeviceStage.Checking || stage is NewDeviceStage.Working

    // Back selagi bekerja diabaikan (jangan tutup di tengah hapus atau tarik); selain itu sama dengan "Nanti saja".
    BackHandler { if (!busy) onDone() }

    fun finish(result: NewDeviceRestoreResult, doneRes: Int, failedRes: Int) {
        when (result) {
            is NewDeviceRestoreResult.Synced -> scope.launch {
                notifier.show(resources.getString(doneRes))
                onDone()
            }
            is NewDeviceRestoreResult.SyncFailed -> stage = NewDeviceStage.Failed(failedRes, canRetrySync = true)
            NewDeviceRestoreResult.NoLongerPure -> stage = NewDeviceStage.Choose
        }
    }

    fun perform(messageRes: Int, doneRes: Int, failedRes: Int, block: suspend () -> NewDeviceRestoreResult) {
        stage = NewDeviceStage.Working(messageRes)
        scope.launch { finish(block(), doneRes, failedRes) }
    }

    LaunchedEffect(Unit) {
        when (flow.decide()) {
            NewDeviceRecoveryOutcome.NothingToRecover -> onDone()
            NewDeviceRecoveryOutcome.AskUser -> stage = NewDeviceStage.Choose
            NewDeviceRecoveryOutcome.AutoReplace -> perform(R.string.newdevice_working_sync, R.string.newdevice_done_restore, R.string.newdevice_sync_failed_after_wipe) { flow.autoReplace() }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.s5)
                .padding(top = spacing.s5, bottom = spacing.s5),
        ) {
            Text(stringResource(R.string.newdevice_title), style = MaterialTheme.typography.headlineMedium)
            when (val current = stage) {
                NewDeviceStage.Checking -> Progress(stringResource(R.string.newdevice_checking))
                is NewDeviceStage.Working -> Progress(stringResource(current.messageRes))
                NewDeviceStage.Choose, is NewDeviceStage.ConfirmOverwrite -> {
                    Text(
                        stringResource(R.string.newdevice_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = spacing.s3),
                    )
                    Text(stringResource(R.string.newdevice_join_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = spacing.s5))
                    Text(stringResource(R.string.newdevice_join_body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = spacing.s1))
                    Button(
                        onClick = { perform(R.string.newdevice_working_sync, R.string.newdevice_done_join, R.string.newdevice_sync_failed) { flow.join() } },
                        modifier = Modifier.padding(top = spacing.s3).fillMaxWidth().height(52.dp),
                    ) { Text(stringResource(R.string.newdevice_join_action)) }
                    Text(stringResource(R.string.newdevice_overwrite_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = spacing.s5))
                    Text(stringResource(R.string.newdevice_overwrite_body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = spacing.s1))
                    OutlinedButton(
                        onClick = {
                            stage = NewDeviceStage.Working(R.string.newdevice_working_backup)
                            scope.launch {
                                stage = when (val prepared = flow.prepareOverwrite()) {
                                    is OverwritePreparation.Ready -> NewDeviceStage.ConfirmOverwrite(prepared)
                                    OverwritePreparation.BackupFailed -> NewDeviceStage.Failed(R.string.newdevice_backup_failed, canRetrySync = false)
                                }
                            }
                        },
                        modifier = Modifier.padding(top = spacing.s3).fillMaxWidth().height(52.dp),
                    ) { Text(stringResource(R.string.newdevice_overwrite_action)) }
                    TextButton(onClick = onDone, modifier = Modifier.padding(top = spacing.s4)) { Text(stringResource(R.string.newdevice_later)) }
                }
                is NewDeviceStage.Failed -> {
                    Row(modifier = Modifier.padding(top = spacing.s4).semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
                        Icon(RizqflowIcons.Peringatan, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text(stringResource(current.messageRes), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = spacing.s2))
                    }
                    if (current.canRetrySync) {
                        Button(
                            onClick = { perform(R.string.newdevice_working_sync, R.string.newdevice_done_restore, current.messageRes) { flow.retrySync() } },
                            modifier = Modifier.padding(top = spacing.s5).fillMaxWidth().height(52.dp),
                        ) { Text(stringResource(R.string.newdevice_retry)) }
                    } else {
                        Button(
                            onClick = { stage = NewDeviceStage.Choose },
                            modifier = Modifier.padding(top = spacing.s5).fillMaxWidth().height(52.dp),
                        ) { Text(stringResource(R.string.newdevice_back_to_choice)) }
                    }
                    TextButton(onClick = onDone, modifier = Modifier.padding(top = spacing.s3)) { Text(stringResource(R.string.newdevice_later)) }
                }
            }
        }
    }

    (stage as? NewDeviceStage.ConfirmOverwrite)?.let { confirm ->
        val size = Formatter.formatShortFileSize(context, confirm.ready.backup.sizeBytes)
        AlertDialog(
            onDismissRequest = { stage = NewDeviceStage.Choose },
            icon = { Icon(RizqflowIcons.Peringatan, contentDescription = null) },
            title = { Text(stringResource(R.string.newdevice_confirm_title)) },
            text = { Text(stringResource(R.string.newdevice_confirm_body, size)) },
            confirmButton = {
                TextButton(onClick = {
                    perform(R.string.newdevice_working_sync, R.string.newdevice_done_restore, R.string.newdevice_sync_failed_after_wipe) { flow.overwrite(confirm.ready) }
                }) { Text(stringResource(R.string.newdevice_confirm_action)) }
            },
            dismissButton = { TextButton(onClick = { stage = NewDeviceStage.Choose }) { Text(stringResource(R.string.catat_date_cancel)) } },
        )
    }
}

@Composable
private fun Progress(message: String) {
    val spacing = MaterialTheme.spacing
    Row(modifier = Modifier.padding(top = spacing.s5).semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = spacing.s3))
    }
}
