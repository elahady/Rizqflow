package com.roziqrizal.rizqflow.ui.recovery

import android.text.format.DateUtils
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.sync.PreRestoreCopyInfo
import com.roziqrizal.rizqflow.domain.sync.PreRestoreRevertFlow
import com.roziqrizal.rizqflow.domain.sync.RevertEligibility
import com.roziqrizal.rizqflow.domain.sync.RevertPreparation
import com.roziqrizal.rizqflow.domain.sync.RevertResult
import com.roziqrizal.rizqflow.ui.RizqflowIcons
import com.roziqrizal.rizqflow.ui.theme.spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tahap tampilan; logika kelayakan dan urutan ada di [PreRestoreRevertFlow] (`:domain`). */
private sealed interface RevertStage {
    data object Checking : RevertStage

    /** Salinan layak; tawarkan tombol. */
    data class Offer(val copy: PreRestoreCopyInfo) : RevertStage

    data class Working(val messageRes: Int) : RevertStage

    /** Salinan darurat sudah dibuat dan kunci dipegang; menunggu konfirmasi eksplisit. */
    data class Confirm(val ready: RevertPreparation.Ready) : RevertStage

    /** Tidak ada yang berubah; [messageRes] menjelaskan kenapa. */
    data class Failed(val messageRes: Int) : RevertStage
}

/**
 * "Kembalikan data sebelum pemulihan" (menu Lainnya, hanya tampil bila salinan `.prerestore` ada). Alurnya: periksa salinan,
 * buat salinan darurat dari keadaan SEKARANG, minta konfirmasi eksplisit yang jujur (termasuk bahwa server tidak ikut
 * berubah), baru serahkan isi salinan ke [onReplaceDatabase] (jalur penimpa yang sama dengan pemulihan cadangan; ruang kerja
 * ditutup dan dibuka ulang oleh `WorkspaceHost`). Seluruh urutan dan penanganan galat ada di `PreRestoreRevertFlow`.
 *
 * Belum diuji di perangkat atau emulator (hanya lolos kompilasi; logika diuji di `PreRestoreRevertFlowTest`).
 */
@Composable
fun PreRestoreRevertScreen(
    flow: PreRestoreRevertFlow,
    onReplaceDatabase: (ByteArray) -> Unit,
    onReverted: () -> Unit,
    onDone: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var stage by remember { mutableStateOf<RevertStage>(RevertStage.Checking) }
    val busy = stage is RevertStage.Checking || stage is RevertStage.Working

    // Kunci pakai salinan dilepas bila layar ditutup selagi menunggu konfirmasi (back, atau ruang kerja berganti).
    var pending by remember { mutableStateOf<RevertPreparation.Ready?>(null) }
    DisposableEffect(Unit) {
        onDispose { pending?.let(flow::cancel) }
    }

    // Back selagi bekerja diabaikan (jangan tutup di tengah menyiapkan atau menyerahkan).
    BackHandler { if (!busy) onDone() }

    LaunchedEffect(Unit) {
        when (val e = withContext(Dispatchers.IO) { flow.eligibility() }) {
            RevertEligibility.NoCopy -> onDone()
            is RevertEligibility.Unusable -> stage = RevertStage.Failed(e.messageRes())
            is RevertEligibility.Available -> stage = RevertStage.Offer(e.copy)
        }
    }

    fun prepare(copy: PreRestoreCopyInfo) {
        stage = RevertStage.Working(R.string.prerestore_working_prepare)
        scope.launch {
            val preparation = withContext(Dispatchers.IO) { flow.prepare() }
            if (preparation is RevertPreparation.Ready) {
                pending = preparation
                stage = RevertStage.Confirm(preparation)
            } else {
                stage = RevertStage.Failed(preparation.failureRes() ?: R.string.prerestore_gone)
            }
        }
    }

    fun confirm(ready: RevertPreparation.Ready) {
        stage = RevertStage.Working(R.string.prerestore_working_apply)
        scope.launch {
            val result = flow.revert(ready) { bytes -> onReplaceDatabase(bytes) }
            pending = null
            if (result is RevertResult.Reverted) {
                // Ruang kerja sedang diganti; pemanggil menutup layar ini dan menyampaikan kabarnya dari lingkupnya sendiri.
                onReverted()
            } else {
                stage = RevertStage.Failed(result.messageRes())
            }
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
            Text(stringResource(R.string.prerestore_title), style = MaterialTheme.typography.headlineMedium)
            when (val current = stage) {
                RevertStage.Checking -> Progress(stringResource(R.string.prerestore_checking))
                is RevertStage.Working -> Progress(stringResource(current.messageRes))
                is RevertStage.Offer -> {
                    Text(
                        stringResource(R.string.prerestore_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = spacing.s3),
                    )
                    Text(
                        stringResource(R.string.prerestore_copy_info, copyDate(current.copy), Formatter.formatShortFileSize(context, current.copy.sizeBytes)),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = spacing.s4),
                    )
                    Button(
                        onClick = { prepare(current.copy) },
                        modifier = Modifier.padding(top = spacing.s4).fillMaxWidth().height(52.dp),
                    ) { Text(stringResource(R.string.prerestore_action)) }
                    TextButton(onClick = onDone, modifier = Modifier.padding(top = spacing.s3)) { Text(stringResource(R.string.newdevice_later)) }
                }
                is RevertStage.Confirm -> {
                    // Dialog di bawah menampilkan keputusan; latar tetap tawaran supaya tidak kosong saat dialog ditutup.
                    Text(
                        stringResource(R.string.prerestore_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = spacing.s3),
                    )
                }
                is RevertStage.Failed -> {
                    Row(modifier = Modifier.padding(top = spacing.s4).semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
                        Icon(RizqflowIcons.Peringatan, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text(stringResource(current.messageRes), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = spacing.s2))
                    }
                    TextButton(onClick = onDone, modifier = Modifier.padding(top = spacing.s4)) { Text(stringResource(R.string.onb_back)) }
                }
            }
        }
    }

    (stage as? RevertStage.Confirm)?.let { confirmStage ->
        val ready = confirmStage.ready
        AlertDialog(
            onDismissRequest = {
                flow.cancel(ready)
                pending = null
                stage = RevertStage.Offer(ready.copy)
            },
            icon = { Icon(RizqflowIcons.Peringatan, contentDescription = null) },
            title = { Text(stringResource(R.string.prerestore_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.prerestore_confirm_body,
                        copyDate(ready.copy),
                        Formatter.formatShortFileSize(context, ready.copy.sizeBytes),
                        Formatter.formatShortFileSize(context, ready.emergencyBackup.sizeBytes),
                    ),
                )
            },
            confirmButton = { TextButton(onClick = { confirm(ready) }) { Text(stringResource(R.string.prerestore_confirm_action)) } },
            dismissButton = {
                TextButton(onClick = {
                    flow.cancel(ready)
                    pending = null
                    stage = RevertStage.Offer(ready.copy)
                }) { Text(stringResource(R.string.catat_date_cancel)) }
            },
        )
    }
}

@Composable
private fun copyDate(copy: PreRestoreCopyInfo): String =
    DateUtils.formatDateTime(
        LocalContext.current,
        copy.createdAtMillis,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR,
    )

@Composable
private fun Progress(message: String) {
    val spacing = MaterialTheme.spacing
    Row(modifier = Modifier.padding(top = spacing.s5).semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = spacing.s3))
    }
}
