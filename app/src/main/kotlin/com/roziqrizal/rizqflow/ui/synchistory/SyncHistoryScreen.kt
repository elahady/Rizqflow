package com.roziqrizal.rizqflow.ui.synchistory

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.sync.LocalSyncHistory
import com.roziqrizal.rizqflow.domain.sync.ScopeSyncStatus
import com.roziqrizal.rizqflow.domain.sync.ServerSyncDevice
import com.roziqrizal.rizqflow.domain.sync.ServerSyncHistory
import com.roziqrizal.rizqflow.domain.sync.SyncHealth
import com.roziqrizal.rizqflow.domain.sync.SyncIssueKind
import com.roziqrizal.rizqflow.domain.sync.SyncIssueRow
import com.roziqrizal.rizqflow.domain.sync.SyncIssueStatus
import com.roziqrizal.rizqflow.domain.sync.SyncQuotaLevel
import com.roziqrizal.rizqflow.domain.sync.SyncQuotaUsage
import com.roziqrizal.rizqflow.domain.sync.SyncRecordType
import com.roziqrizal.rizqflow.domain.sync.SyncScope
import com.roziqrizal.rizqflow.ui.RizqflowIcons
import com.roziqrizal.rizqflow.ui.theme.spacing
import java.text.NumberFormat
import java.time.Instant
import kotlinx.coroutines.launch

/**
 * Layar "Riwayat sinkron" (Tahap 9, menu Lainnya dekat Rizqflow Sync). Logikanya ada di [SyncHistoryViewModel]; layar
 * ini hanya menampilkan keadaannya. Aksesibilitas: setiap status = ikon plus teks (tidak pernah hanya warna), judul
 * bagian ditandai sebagai judul, dan nada pesan lembut: tidak ada yang hilang, tidak ada yang mendesak.
 */
@Composable
fun SyncHistoryScreen(
    viewModel: SyncHistoryViewModel,
    onClose: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val spacing = MaterialTheme.spacing
    var confirmResync by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.load() }
    BackHandler { onClose() }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        TextButton(
            onClick = onClose,
            modifier = Modifier.padding(start = spacing.s3, top = spacing.s2),
        ) { Text(stringResource(R.string.onb_back)) }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.s5)
                .padding(bottom = spacing.s5),
        ) {
            Text(stringResource(R.string.synchist_title), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.synchist_intro),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = spacing.s3),
            )

            if (state.loading && state.local == null) {
                Row(modifier = Modifier.padding(top = spacing.s4), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = spacing.s3))
                    Text(stringResource(R.string.synchist_loading), style = MaterialTheme.typography.bodyMedium)
                }
            }

            state.local?.let { local ->
                HealthBanner(state.health)
                ScopesSection(local)
                IssuesSection(local)
            }

            ServerSection(state, onRetry = { scope.launch { viewModel.load() } })

            ResyncSection(
                resync = state.resync,
                enabled = state.serverFailure != SyncHistoryFailure.SessionEnded,
                onStart = { confirmResync = true },
                onDismiss = viewModel::dismissResyncResult,
            )
        }
    }

    if (confirmResync) {
        AlertDialog(
            onDismissRequest = { confirmResync = false },
            title = { Text(stringResource(R.string.synchist_resync_confirm_title)) },
            text = { Text(stringResource(R.string.synchist_resync_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmResync = false
                    scope.launch { viewModel.fullResync() }
                }) { Text(stringResource(R.string.synchist_resync_confirm_action)) }
            },
            dismissButton = { TextButton(onClick = { confirmResync = false }) { Text(stringResource(R.string.synchist_resync_cancel)) } },
        )
    }
}

/** Spanduk status keseluruhan: ikon plus judul plus satu kalimat penenang. */
@Composable
private fun HealthBanner(health: SyncHealth) {
    val spacing = MaterialTheme.spacing
    val (icon, title, sub) = when (health) {
        SyncHealth.CALM -> Triple(RizqflowIcons.Centang, R.string.synchist_health_calm, R.string.synchist_health_calm_sub)
        SyncHealth.WAITING -> Triple(RizqflowIcons.Jam, R.string.synchist_health_waiting, R.string.synchist_health_waiting_sub)
        SyncHealth.NEEDS_ATTENTION -> Triple(RizqflowIcons.Peringatan, R.string.synchist_health_attention, R.string.synchist_health_attention_sub)
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = spacing.s4),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(modifier = Modifier.padding(spacing.s4), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(end = spacing.s3).size(24.dp))
            Column {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(sub), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(top = MaterialTheme.spacing.s5).semantics { heading() },
    )
}

/** Status satu baris: ikon dekoratif plus teks yang membawa maknanya. */
@Composable
private fun StatusLine(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(end = MaterialTheme.spacing.s2).size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun InfoCard(content: @Composable () -> Unit) {
    val spacing = MaterialTheme.spacing
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = spacing.s3),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(spacing.s4), verticalArrangement = Arrangement.spacedBy(spacing.s1)) { content() }
    }
}

// ---- Cakupan ----

@Composable
private fun ScopesSection(local: LocalSyncHistory) {
    SectionTitle(stringResource(R.string.synchist_scopes_title))
    local.scopes.forEach { ScopeCard(it) }
}

@Composable
private fun ScopeCard(status: ScopeSyncStatus) {
    val title = when (status.scope) {
        SyncScope.Personal -> stringResource(R.string.synchist_scope_personal)
        is SyncScope.SharedRoom -> status.roomName?.let { stringResource(R.string.synchist_scope_room, it) }
            ?: stringResource(R.string.synchist_scope_room_unnamed)
    }
    InfoCard {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (status.dirtyCount == 0) {
            StatusLine(RizqflowIcons.Centang, stringResource(R.string.synchist_scope_status_calm))
            Text(stringResource(R.string.synchist_scope_dirty_none), style = MaterialTheme.typography.bodySmall)
        } else {
            StatusLine(RizqflowIcons.Jam, stringResource(R.string.synchist_scope_status_waiting))
            Text(
                pluralStringResource(R.plurals.synchist_scope_dirty, status.dirtyCount, status.dirtyCount),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

// ---- Server ----

@Composable
private fun ServerSection(state: SyncHistoryUiState, onRetry: () -> Unit) {
    val spacing = MaterialTheme.spacing
    state.serverFailure?.let { failure ->
        if (state.local != null) {
            StatusLine(
                RizqflowIcons.Jam,
                stringResource(serverFailureText(failure)),
                modifier = Modifier.padding(top = spacing.s5),
            )
            if (failure != SyncHistoryFailure.SessionEnded) {
                OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = spacing.s2)) {
                    Text(stringResource(R.string.synchist_retry))
                }
            }
        }
    }
    val server = state.server ?: return
    SectionTitle(stringResource(R.string.synchist_device_title))
    ThisDeviceCard(state.thisDevice)
    if (state.otherDevices.isNotEmpty()) {
        SectionTitle(stringResource(R.string.synchist_other_devices_title))
        state.otherDevices.forEach { OtherDeviceCard(it) }
    }
    SectionTitle(stringResource(R.string.synchist_quota_title))
    QuotaCard(server.quota)
    SectionTitle(stringResource(R.string.synchist_compaction_title))
    CompactionCard(server)
}

@Composable
private fun ThisDeviceCard(device: ServerSyncDevice?) {
    InfoCard {
        if (device == null) {
            Text(stringResource(R.string.synchist_device_not_seen), style = MaterialTheme.typography.bodyMedium)
        } else {
            DeviceTimes(device)
            Text(
                pluralStringResource(R.plurals.synchist_device_rows, device.rows.toPluralCount(), device.rows.toPluralCount()),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(stringResource(R.string.synchist_device_note), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun OtherDeviceCard(device: ServerSyncDevice) {
    InfoCard {
        Text(stringResource(R.string.synchist_other_device, shortDeviceId(device.deviceId)), style = MaterialTheme.typography.titleMedium)
        if (device.active) {
            StatusLine(RizqflowIcons.Centang, stringResource(R.string.synchist_device_active))
        } else {
            StatusLine(RizqflowIcons.Jam, stringResource(R.string.synchist_device_inactive))
        }
        DeviceTimes(device)
    }
}

@Composable
private fun DeviceTimes(device: ServerSyncDevice) {
    Text(
        device.lastPushAt?.let { stringResource(R.string.synchist_device_last_push, formatSyncTime(it)) }
            ?: stringResource(R.string.synchist_device_never_push),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        device.lastPullAt?.let { stringResource(R.string.synchist_device_last_pull, formatSyncTime(it)) }
            ?: stringResource(R.string.synchist_device_never_pull),
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun QuotaCard(quota: SyncQuotaUsage) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    InfoCard {
        Text(
            stringResource(R.string.synchist_quota_rows, numbers.format(quota.rows), numbers.format(quota.maxRows)),
            style = MaterialTheme.typography.bodyMedium,
        )
        LinearProgressIndicator(progress = { quota.rowsPercent / 100f }, modifier = Modifier.fillMaxWidth())
        Text(
            stringResource(R.string.synchist_quota_bytes, formatSyncBytes(quota.bytes), formatSyncBytes(quota.maxBytes)),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = MaterialTheme.spacing.s2),
        )
        LinearProgressIndicator(progress = { quota.bytesPercent / 100f }, modifier = Modifier.fillMaxWidth())
        val (icon, text) = when (quota.level) {
            SyncQuotaLevel.COMFORTABLE -> RizqflowIcons.Centang to R.string.synchist_quota_comfortable
            SyncQuotaLevel.NEARING -> RizqflowIcons.Jam to R.string.synchist_quota_nearing
            SyncQuotaLevel.FULL -> RizqflowIcons.Peringatan to R.string.synchist_quota_full
        }
        StatusLine(icon, stringResource(text), modifier = Modifier.padding(top = MaterialTheme.spacing.s2))
    }
}

@Composable
private fun CompactionCard(server: ServerSyncHistory) {
    InfoCard {
        val lastRun = server.compaction.lastRunAt
        if (lastRun == null) {
            Text(stringResource(R.string.synchist_compaction_never), style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(
                stringResource(
                    R.string.synchist_compaction_last,
                    formatSyncDate(lastRun),
                    server.compaction.removedVersions,
                    server.compaction.removedTombstones,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

// ---- Masalah sinkron ----

@Composable
private fun IssuesSection(local: LocalSyncHistory) {
    val spacing = MaterialTheme.spacing
    SectionTitle(stringResource(R.string.synchist_issues_title))
    if (local.issueTotal == 0) {
        InfoCard {
            StatusLine(RizqflowIcons.Centang, stringResource(R.string.synchist_issues_none))
        }
        return
    }
    Text(
        stringResource(
            R.string.synchist_issues_summary,
            local.summary.retrying,
            local.summary.permanentlyFailed.size,
            local.summary.other,
        ),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = spacing.s2),
    )
    if (local.summary.permanentlyFailed.isNotEmpty()) {
        Text(
            stringResource(R.string.synchist_permanent_note),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = spacing.s2),
        )
    }
    local.issues.forEach { IssueCard(it) }
    val hidden = local.issueTotal - local.issues.size
    if (hidden > 0) {
        Text(
            stringResource(R.string.synchist_issues_more, hidden),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = spacing.s3),
        )
    }
}

@Composable
private fun IssueCard(row: SyncIssueRow) {
    val (icon, statusText) = when (row.status) {
        SyncIssueStatus.PERMANENTLY_FAILED -> RizqflowIcons.Peringatan to R.string.synchist_issue_status_permanent
        SyncIssueStatus.RETRYING -> RizqflowIcons.Jam to R.string.synchist_issue_status_retrying
        SyncIssueStatus.OTHER -> RizqflowIcons.Lingkaran to R.string.synchist_issue_status_other
    }
    InfoCard {
        Text(stringResource(issueKindText(row.issue.kind)), style = MaterialTheme.typography.titleMedium)
        StatusLine(icon, stringResource(statusText))
        Text(
            stringResource(
                R.string.synchist_issue_meta,
                stringResource(recordTypeText(row.issue.recordType)),
                formatSyncDate(Instant.ofEpochMilli(row.issue.createdAtMillis)),
                pluralStringResource(R.plurals.synchist_issue_tries, row.issue.attempts, row.issue.attempts),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// ---- Sinkron ulang penuh ----

@Composable
private fun ResyncSection(
    resync: ResyncState,
    enabled: Boolean,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    SectionTitle(stringResource(R.string.synchist_resync_title))
    Text(
        stringResource(R.string.synchist_resync_sub),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = spacing.s2),
    )
    Button(
        onClick = onStart,
        enabled = enabled && resync != ResyncState.Running,
        modifier = Modifier.fillMaxWidth().padding(top = spacing.s3),
    ) {
        if (resync == ResyncState.Running) {
            CircularProgressIndicator(modifier = Modifier.padding(end = spacing.s3).size(18.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.synchist_resync_running))
        } else {
            Text(stringResource(R.string.synchist_resync_action))
        }
    }
    when (resync) {
        ResyncState.Idle, ResyncState.Running -> Unit

        is ResyncState.Done -> ResultMessage(
            icon = RizqflowIcons.Centang,
            text = if (resync.applied == 0) {
                stringResource(R.string.synchist_resync_done_none)
            } else {
                pluralStringResource(R.plurals.synchist_resync_done, resync.applied, resync.applied)
            },
            onDismiss = onDismiss,
        )

        is ResyncState.Failed -> ResultMessage(
            icon = RizqflowIcons.Jam,
            text = stringResource(resyncFailureText(resync.reason)),
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun ResultMessage(icon: ImageVector, text: String, onDismiss: () -> Unit) {
    val spacing = MaterialTheme.spacing
    StatusLine(icon, text, modifier = Modifier.padding(top = spacing.s3))
    OutlinedButton(onClick = onDismiss, modifier = Modifier.padding(top = spacing.s2)) {
        Text(stringResource(R.string.synchist_dismiss))
    }
}

// ---- Pemetaan teks ----

/** Hitungan untuk `plurals`: aman dipotong ke Int. */
private fun Long.toPluralCount(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

private fun serverFailureText(failure: SyncHistoryFailure): Int = when (failure) {
    SyncHistoryFailure.SessionEnded -> R.string.synchist_server_failure_session
    SyncHistoryFailure.Offline -> R.string.synchist_server_failure_offline
    SyncHistoryFailure.Other -> R.string.synchist_server_failure_other
}

private fun resyncFailureText(failure: SyncHistoryFailure): Int = when (failure) {
    SyncHistoryFailure.SessionEnded -> R.string.synchist_resync_failed_session
    SyncHistoryFailure.Offline -> R.string.synchist_resync_failed_offline
    SyncHistoryFailure.Other -> R.string.synchist_resync_failed_other
}

internal fun issueKindText(kind: SyncIssueKind): Int = when (kind) {
    SyncIssueKind.DEFERRED_MISSING_REF -> R.string.synchist_kind_missing_ref
    SyncIssueKind.DEFERRED_FK_RESTRICT -> R.string.synchist_kind_fk_restrict
    SyncIssueKind.DEFERRED_KEY_MISSING -> R.string.synchist_kind_key_missing
    SyncIssueKind.UNSUPPORTED_VERSION -> R.string.synchist_kind_unsupported_version
    SyncIssueKind.TAMPERED_OR_CORRUPT -> R.string.synchist_kind_tampered
    SyncIssueKind.OVERWRITTEN -> R.string.synchist_kind_overwritten
    SyncIssueKind.UNAUTHORIZED_AUTHOR -> R.string.synchist_kind_unauthorized_author
}

/** Nama jenis data untuk pengguna dari nama kabel (`record_type`); jenis yang tidak dikenal menjadi "Data lainnya". */
internal fun recordTypeText(wire: String): Int = when (SyncRecordType.fromWire(wire)) {
    SyncRecordType.ROOM -> R.string.synchist_type_room
    SyncRecordType.ACCOUNT -> R.string.synchist_type_account
    SyncRecordType.CATEGORY -> R.string.synchist_type_category
    SyncRecordType.ALLOCATION_CONFIG -> R.string.synchist_type_allocation_config
    SyncRecordType.TRANSACTION -> R.string.synchist_type_transaction
    SyncRecordType.DEBT -> R.string.synchist_type_debt
    SyncRecordType.DEBT_PAYMENT -> R.string.synchist_type_debt_payment
    SyncRecordType.BILL -> R.string.synchist_type_bill
    SyncRecordType.RECURRING_RULE -> R.string.synchist_type_recurring_rule
    SyncRecordType.ZAKAT_PROFILE -> R.string.synchist_type_zakat_profile
    SyncRecordType.WEALTH_ITEM -> R.string.synchist_type_wealth_item
    SyncRecordType.WEALTH_CHECK -> R.string.synchist_type_wealth_check
    SyncRecordType.ZAKAT_PAYMENT -> R.string.synchist_type_zakat_payment
    SyncRecordType.GOLD_PRICE -> R.string.synchist_type_gold_price
    SyncRecordType.TRADER_PROFILE -> R.string.synchist_type_trader_profile
    SyncRecordType.DCA_PLAN -> R.string.synchist_type_dca_plan
    SyncRecordType.QUICK_FAVORITE -> R.string.synchist_type_quick_favorite
    SyncRecordType.DAY_CHECK -> R.string.synchist_type_day_check
    null -> R.string.synchist_type_other
}
