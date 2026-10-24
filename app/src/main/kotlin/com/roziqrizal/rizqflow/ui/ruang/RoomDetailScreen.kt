package com.roziqrizal.rizqflow.ui.ruang

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.ledger.ArchiveUndo
import com.roziqrizal.rizqflow.domain.ledger.CategorySpend
import com.roziqrizal.rizqflow.domain.ledger.LedgerResult
import com.roziqrizal.rizqflow.domain.ledger.RoomDetail
import com.roziqrizal.rizqflow.domain.ledger.RoomDetailLoader
import com.roziqrizal.rizqflow.domain.ledger.RoomTransaction
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.model.RoomKind
import com.roziqrizal.rizqflow.domain.model.TransactionId
import com.roziqrizal.rizqflow.domain.role.RoleKind
import com.roziqrizal.rizqflow.domain.role.RoleOverview
import com.roziqrizal.rizqflow.ui.Notifier
import com.roziqrizal.rizqflow.ui.RizqflowIcons
import com.roziqrizal.rizqflow.ui.RoomTile
import com.roziqrizal.rizqflow.ui.denah.statusIcon
import com.roziqrizal.rizqflow.ui.denah.statusLabel
import com.roziqrizal.rizqflow.ui.denah.statusTint
import com.roziqrizal.rizqflow.ui.formatDate
import com.roziqrizal.rizqflow.ui.formatMonth
import com.roziqrizal.rizqflow.ui.formatPercent
import com.roziqrizal.rizqflow.ui.formatRupiah
import com.roziqrizal.rizqflow.ui.theme.CappedFontScale
import com.roziqrizal.rizqflow.ui.theme.HERO_MAX_FONT_SCALE
import com.roziqrizal.rizqflow.ui.theme.rizqflow
import com.roziqrizal.rizqflow.ui.theme.spacing
import com.roziqrizal.rizqflow.workspace.AccountWorkspace
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

/**
 * S11 Detail ruang: cincin progres, terpakai dibanding jatah, status menurut tipe ruang, pos-pos, dan
 * pengeluaran terbaru bulan itu. Ketuk transaksi membuka Detail transaksi; **Atur aturan** membuka
 * Aturan alokasi. Ruang terarsip tetap bisa dilihat tetapi tanpa tombol ubah. Kartu Zakat untuk ruang
 * Memberi menunggu Tahap 5.
 */
@Composable
fun RoomDetailScreen(
    workspace: AccountWorkspace,
    roomId: RoomId,
    month: YearMonth,
    refreshKey: Int,
    notifier: Notifier,
    onClose: () -> Unit,
    onOpenTransaction: (TransactionId) -> Unit,
    onOpenRules: () -> Unit,
    onArchived: (ArchiveUndo) -> Unit,
    /** Ruang bertipe Menunaikan saja: membuka modul Memberi (S14 sampai S17). */
    onOpenZakat: (RoomId) -> Unit = {},
    /** Sistem per peran (Pro): membuka layar Peran untuk ruang ini. */
    onOpenRole: (RoomId) -> Unit = {},
    /** Ruang yang belum diarsipkan: membuka Ruang Keluarga (Tahap 9) untuk ruang ini. */
    onOpenFamily: (RoomId) -> Unit = {},
) {
    val today = remember { LocalDate.now() }
    var detail by remember { mutableStateOf<RoomDetail?>(null) }
    var role by remember { mutableStateOf<RoleOverview?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var confirmingArchive by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val spacing = MaterialTheme.spacing

    LaunchedEffect(roomId, month, refreshKey) {
        val repos = workspace.repositories
        detail = RoomDetailLoader(repos.rooms, repos.transactions).load(roomId, month, today)
        role = workspace.roles.overview(roomId, today)
        loaded = true
    }
    BackHandler(onBack = onClose)

    val data = detail
    if (data == null) {
        // Ruang yang tidak ada (mis. terhapus di tempat lain) menutup layar ini.
        if (loaded) LaunchedEffect(Unit) { onClose() }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }
    val room = data.card.room

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = spacing.s3, end = spacing.s5, top = spacing.s2)) {
            TextButton(onClick = onClose) { Text(stringResource(R.string.onb_back)) }
            Text(
                formatMonth(data.month),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(end = spacing.s2),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.s5)
                .padding(bottom = spacing.s5),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.s3)) {
                RoomTile(room.iconKey, room.colorSlot, size = 44)
                Column {
                    Text(room.name, style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(kindLabel(room.kind)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (room.archived) {
                Text(stringResource(R.string.room_detail_archived_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = spacing.s3))
            }

            Summary(data)

            if (room.kind == RoomKind.MENUNAIKAN) {
                Row(
                    modifier = Modifier
                        .padding(top = spacing.s4)
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { onOpenZakat(room.id) }
                        .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp))
                        .padding(spacing.s3),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.s3),
                ) {
                    Icon(RizqflowIcons.Bulan, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.room_detail_zakat_title), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.room_detail_zakat_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(RizqflowIcons.PanahKanan, contentDescription = null)
                }
            }

            // Ruang Memberi punya modul Zakat sendiri; ruang lain bisa memasang peran (Trader/Investor).
            if (room.kind != RoomKind.MENUNAIKAN && (!room.archived || role?.kind != null)) {
                Row(
                    modifier = Modifier
                        .padding(top = spacing.s4)
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { onOpenRole(room.id) }
                        .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp))
                        .padding(spacing.s3),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.s3),
                ) {
                    Icon(if (role?.kind == RoleKind.INVESTOR) RizqflowIcons.Tunas else RizqflowIcons.PanahAtas, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.room_detail_role_title), style = MaterialTheme.typography.titleSmall)
                        Text(roleSummary(role), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(RizqflowIcons.PanahKanan, contentDescription = null)
                }
            }

            if (!room.archived) {
                Row(
                    modifier = Modifier
                        .padding(top = spacing.s4)
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { onOpenFamily(room.id) }
                        .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp))
                        .padding(spacing.s3),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.s3),
                ) {
                    Icon(RizqflowIcons.Rumah, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.room_detail_family_title), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.room_detail_family_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(RizqflowIcons.PanahKanan, contentDescription = null)
                }
            }

            Text(stringResource(R.string.room_detail_pos), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = spacing.s5, bottom = spacing.s2))
            if (data.categories.isEmpty()) {
                Text(stringResource(R.string.room_detail_pos_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            data.categories.forEach { PosRow(it, room.colorSlot) }

            Text(stringResource(R.string.room_detail_recent), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = spacing.s5, bottom = spacing.s1))
            if (data.recent.isEmpty()) {
                Text(stringResource(R.string.room_detail_recent_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            data.recent.forEach { row ->
                RecentRow(row, today) { onOpenTransaction(row.transaction.id) }
                HorizontalDivider()
            }

            if (!room.archived) {
                OutlinedButton(onClick = onOpenRules, modifier = Modifier.padding(top = spacing.s5).fillMaxWidth().height(52.dp)) {
                    Text(stringResource(R.string.room_detail_rules))
                }
                TextButton(onClick = { confirmingArchive = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.room_detail_archive), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (confirmingArchive) {
        AlertDialog(
            onDismissRequest = { confirmingArchive = false },
            title = { Text(stringResource(R.string.room_detail_archive_title, room.name)) },
            text = { Text(stringResource(R.string.room_detail_archive_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingArchive = false
                    scope.launch {
                        when (val result = workspace.rules.archiveWithUndo(room.id)) {
                            is LedgerResult.Success -> {
                                onArchived(result.value)
                                onClose()
                            }

                            is LedgerResult.Failure -> notifier.show(roomErrorText(context, result.error))
                        }
                    }
                }) { Text(stringResource(R.string.room_archive), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingArchive = false }) { Text(stringResource(R.string.catat_date_cancel)) } },
        )
    }
}

/** Satu baris ringkasan peran ruang untuk kartu Peran: kosong, Trader dengan batasnya, atau Investor dengan jadwalnya. */
@Composable
private fun roleSummary(role: RoleOverview?): String {
    role?.trader?.let { return stringResource(R.string.room_detail_role_sub_trader, formatRupiah(it.maxRiskPerTrade)) }
    role?.dca?.let { return stringResource(R.string.room_detail_role_sub_investor, formatRupiah(it.plan.amount), it.plan.dayOfMonth) }
    return stringResource(R.string.room_detail_role_sub_none)
}

/** Cincin progres besar, terpakai dari jatah, status ikon plus teks, dan penjelasan singkat arti statusnya. */
@Composable
private fun Summary(data: RoomDetail) {
    val spacing = MaterialTheme.spacing
    val card = data.card
    val color = MaterialTheme.rizqflow.room(card.room.colorSlot)
    val progress = card.progressBp
    val percentText = progress?.let { formatPercent(it / 100 * 100) } ?: "—"
    val summary = if (progress == null) {
        stringResource(R.string.room_detail_no_allocation)
    } else {
        stringResource(R.string.room_detail_used, formatRupiah(card.spent)) + " " + stringResource(R.string.room_detail_of, formatRupiah(card.allocated))
    }
    Column(modifier = Modifier.fillMaxWidth().padding(top = spacing.s4), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$percentText. $summary" }) {
            CircularProgressIndicator(
                progress = { (progress ?: 0).coerceAtMost(10_000) / 10_000f },
                modifier = Modifier.size(150.dp),
                color = color,
                trackColor = color.copy(alpha = 0.2f),
                strokeWidth = 14.dp,
            )
            CappedFontScale(HERO_MAX_FONT_SCALE) { Text(percentText, style = MaterialTheme.typography.headlineMedium) }
        }
        Text(summary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = spacing.s3))
        if (card.isOver) {
            Text(stringResource(R.string.denah_over, formatRupiah(-card.remaining.minor)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (progress != null) {
            Text(stringResource(R.string.denah_remaining, formatRupiah(card.remaining)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.s1), modifier = Modifier.padding(top = spacing.s2)) {
            Icon(statusIcon(card.status), contentDescription = null, tint = statusTint(card.status), modifier = Modifier.size(18.dp))
            Text(stringResource(statusLabel(card.status)), style = MaterialTheme.typography.titleSmall)
        }
        Text(
            stringResource(if (card.room.kind == RoomKind.MENCUKUPI) R.string.room_hint_mencukupi else R.string.room_hint_target),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.s1),
        )
    }
}

@Composable
private fun PosRow(pos: CategorySpend, colorSlot: Int) {
    val spacing = MaterialTheme.spacing
    val progress = pos.progressBp
    val percentText = progress?.let { formatPercent(it / 100 * 100) } ?: "—"
    val description = "${pos.category.name}, ${formatRupiah(pos.spent)}, $percentText"
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = spacing.s2).semantics(mergeDescendants = true) { contentDescription = description }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(pos.category.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(formatRupiah(pos.spent), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(percentText, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = spacing.s3))
        }
        val color = MaterialTheme.rizqflow.room(colorSlot)
        LinearProgressIndicator(
            progress = { (progress ?: 0).coerceAtMost(10_000) / 10_000f },
            modifier = Modifier.padding(top = spacing.s1).fillMaxWidth().height(8.dp),
            color = color,
            trackColor = color.copy(alpha = 0.2f),
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}

@Composable
private fun RecentRow(row: RoomTransaction, today: LocalDate, onClick: () -> Unit) {
    val spacing = MaterialTheme.spacing
    val tx = row.transaction
    Row(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = spacing.s3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s3),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(tx.note ?: row.categoryName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${formatDate(tx.occurredOn, today)} · ${row.categoryName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text("−" + formatRupiah(tx.amount).removePrefix("Rp "), style = MaterialTheme.typography.titleSmall)
        Icon(RizqflowIcons.PanahKanan, contentDescription = null, modifier = Modifier.size(18.dp))
    }
}
