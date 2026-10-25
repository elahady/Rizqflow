package com.roziqrizal.rizqflow.ui.ruang

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.ledger.LedgerError
import com.roziqrizal.rizqflow.domain.ledger.LedgerResult
import com.roziqrizal.rizqflow.domain.ledger.NewRoom
import com.roziqrizal.rizqflow.domain.ledger.Room
import com.roziqrizal.rizqflow.domain.ledger.RoomEntry
import com.roziqrizal.rizqflow.domain.ledger.RoomOverview
import com.roziqrizal.rizqflow.domain.ledger.RoomTemplate
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.model.RoomKind
import com.roziqrizal.rizqflow.ui.Notifier
import com.roziqrizal.rizqflow.ui.ROOM_ICON_CHOICES
import com.roziqrizal.rizqflow.ui.RizqflowIcons
import com.roziqrizal.rizqflow.ui.RoomTile
import com.roziqrizal.rizqflow.ui.formatPercent
import com.roziqrizal.rizqflow.ui.roomIcon
import com.roziqrizal.rizqflow.ui.roomIconLabel
import com.roziqrizal.rizqflow.ui.theme.rizqflow
import com.roziqrizal.rizqflow.ui.theme.rizqflowFilterChipColors
import com.roziqrizal.rizqflow.ui.theme.spacing
import com.roziqrizal.rizqflow.workspace.AccountWorkspace
import kotlinx.coroutines.launch

/** Slot warna untuk ruang buatan pengguna: warna 1 sampai 3 milik Tiga hak, ruang lain memakai warna netral. */
private const val EXTRA_ROOM_COLOR_SLOT = 4

/**
 * S10 Daftar ruang: ruang aktif berurutan menurut prioritas (geser naik atau turun), pintasan ke
 * Aturan alokasi, tambah ruang lewat sheet, dan ruang yang diarsipkan. Status per ruang (Berjalan,
 * Terpenuhi, Perlu perhatian) menunggu Tahap 4. [refreshKey] berubah setiap ada perubahan dari luar.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun RuangScreen(
    workspace: AccountWorkspace,
    refreshKey: Int,
    notifier: Notifier,
    onOpenRules: () -> Unit,
    onChanged: () -> Unit,
    /** Mengetuk baris ruang membuka Detail ruang (S11). */
    onOpenRoom: (RoomId) -> Unit = {},
    /** Batas ruang gratis tercapai: membuka paywall Pro (S21) alih-alih pesan saja. */
    onOpenPaywall: () -> Unit = {},
) {
    var overview by remember { mutableStateOf<RoomOverview?>(null) }
    var localVersion by remember { mutableIntStateOf(0) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val spacing = MaterialTheme.spacing
    val context = LocalContext.current
    val resources = LocalResources.current

    LaunchedEffect(refreshKey, localVersion) { overview = workspace.rules.overview() }

    fun changed() {
        localVersion++
        onChanged()
    }

    val current = overview
    if (current == null) {
        Box(Modifier.fillMaxSize())
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.s4)
            .padding(top = spacing.s4, bottom = 96.dp),
    ) {
        Text(stringResource(R.string.room_title), style = MaterialTheme.typography.headlineMedium)

        if (current.active.isEmpty()) {
            Text(
                stringResource(R.string.room_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.s3),
            )
            FilledTonalButton(
                onClick = {
                    scope.launch {
                        when (val result = workspace.rules.applyTemplate(RoomTemplate.TIGA_HAK)) {
                            is LedgerResult.Success -> changed()
                            is LedgerResult.Failure -> notifier.show(roomErrorText(context, result.error))
                        }
                    }
                },
                modifier = Modifier.padding(top = spacing.s3).fillMaxWidth().height(48.dp),
            ) { Text(stringResource(R.string.room_apply_template)) }
        }

        // Ruang aktif: urutan adalah prioritas sisa pembulatan dan urutan di Denah.
        Column(modifier = Modifier.padding(top = spacing.s3), verticalArrangement = Arrangement.spacedBy(spacing.s1)) {
            current.active.forEachIndexed { index, entry ->
                RoomRow(
                    entry = entry,
                    canMoveUp = index > 0,
                    canMoveDown = index < current.active.lastIndex,
                    onOpen = { onOpenRoom(entry.room.id) },
                    onMove = { delta -> scope.launch { workspace.rules.moveRoom(entry.room.id, delta); changed() } },
                    onArchive = {
                        scope.launch {
                            val snapshot = (workspace.rules.archiveWithUndo(entry.room.id) as? LedgerResult.Success)?.value ?: return@launch
                            changed()
                            val undo = notifier.show(resources.getString(R.string.room_archived, entry.room.name), resources.getString(R.string.action_undo))
                            if (undo) {
                                workspace.rules.undoArchive(snapshot)
                                changed()
                            }
                        }
                    },
                )
            }
        }

        if (current.active.isNotEmpty()) {
            // Pembagian rezeki: pintasan ke S12 dengan ringkasan persentase.
            Row(
                modifier = Modifier
                    .padding(top = spacing.s4)
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onOpenRules)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp))
                    .padding(spacing.s3),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.s3),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.room_split_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        current.active.joinToString("  ") { "${it.room.name} ${formatPercent(it.shareBp)}" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(RizqflowIcons.PanahKanan, contentDescription = null)
            }

            if (!current.isBalanced) {
                Row(
                    modifier = Modifier
                        .padding(top = spacing.s3)
                        .fillMaxWidth()
                        .background(MaterialTheme.rizqflow.statusWarning.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
                        .padding(spacing.s3)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    horizontalArrangement = Arrangement.spacedBy(spacing.s3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(RizqflowIcons.Peringatan, contentDescription = null, tint = MaterialTheme.rizqflow.statusWarning)
                    Text(
                        stringResource(R.string.room_unbalanced, formatPercent(current.totalBp)),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpenRules) { Text(stringResource(R.string.room_unbalanced_action)) }
                }
            }
        }

        Button(
            onClick = {
                if (current.canAdd) adding = true else onOpenPaywall()
            },
            modifier = Modifier.padding(top = spacing.s4).fillMaxWidth().height(52.dp),
        ) {
            Icon(RizqflowIcons.Tambah, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.room_add), modifier = Modifier.padding(start = spacing.s2))
        }
        current.roomLimit?.let {
            Text(
                stringResource(R.string.room_limit, current.active.size, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.s2).align(Alignment.CenterHorizontally),
            )
        }

        if (current.archived.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .padding(top = spacing.s5)
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { showArchived = !showArchived }
                    .padding(vertical = spacing.s2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.room_archived_section, current.archived.size), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(if (showArchived) RizqflowIcons.PanahAtas else RizqflowIcons.PanahBawah, contentDescription = null)
            }
            if (showArchived) {
                current.archived.forEach { room ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.s3),
                        modifier = Modifier.fillMaxWidth().padding(vertical = spacing.s1),
                    ) {
                        RoomTile(room.iconKey, room.colorSlot, size = 36)
                        Text(room.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = {
                            scope.launch {
                                when (val result = workspace.rules.restoreRoom(room.id)) {
                                    is LedgerResult.Success -> changed()
                                    is LedgerResult.Failure -> notifier.show(roomErrorText(context, result.error))
                                }
                            }
                        }) { Text(stringResource(R.string.room_restore)) }
                    }
                }
            }
        }
    }

    if (adding) {
        ModalBottomSheet(onDismissRequest = { adding = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            AddRoomSheet(
                onCancel = { adding = false },
                onAdd = { command ->
                    val result = workspace.rules.addRoom(command)
                    if (result is LedgerResult.Success) {
                        adding = false
                        changed()
                        scope.launch {
                            if (notifier.show(resources.getString(R.string.room_added, command.name), resources.getString(R.string.room_added_action))) onOpenRules()
                        }
                    }
                    (result as? LedgerResult.Failure)?.error
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------------- baris ruang

@Composable
private fun RoomRow(
    entry: RoomEntry,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    onArchive: () -> Unit,
    onOpen: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    var menuOpen by remember { mutableStateOf(false) }
    val room = entry.room
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = spacing.s1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    ) {
        RoomTile(room.iconKey, room.colorSlot, size = 44)
        Column(modifier = Modifier.weight(1f).clickable(role = Role.Button, onClick = onOpen)) {
            Text(room.name, style = MaterialTheme.typography.titleSmall)
            Text(
                "${stringResource(kindLabel(room.kind))} · ${formatPercent(entry.shareBp)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
            Icon(RizqflowIcons.PanahAtas, contentDescription = stringResource(R.string.room_move_up, room.name))
        }
        IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
            Icon(RizqflowIcons.PanahBawah, contentDescription = stringResource(R.string.room_move_down, room.name))
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(RizqflowIcons.Lainnya, contentDescription = stringResource(R.string.room_more, room.name))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.room_archive)) },
                    onClick = {
                        menuOpen = false
                        onArchive()
                    },
                )
            }
        }
    }
}

internal fun kindLabel(kind: RoomKind): Int = when (kind) {
    RoomKind.MENUNAIKAN -> R.string.kind_menunaikan
    RoomKind.MENUMBUHKAN -> R.string.kind_menumbuhkan
    RoomKind.MENCUKUPI -> R.string.kind_mencukupi
}

private fun kindHint(kind: RoomKind): Int = when (kind) {
    RoomKind.MENUNAIKAN -> R.string.kind_menunaikan_hint
    RoomKind.MENUMBUHKAN -> R.string.kind_menumbuhkan_hint
    RoomKind.MENCUKUPI -> R.string.kind_mencukupi_hint
}

internal fun roomErrorText(context: Context, error: LedgerError): String = context.getString(
    when (error) {
        LedgerError.ROOM_LIMIT_REACHED -> R.string.room_err_limit
        LedgerError.NAME_TAKEN -> R.string.room_err_taken
        LedgerError.INVALID_NAME -> R.string.room_err_name
        LedgerError.FEATURE_LOCKED -> R.string.rules_pro_note
        else -> R.string.catat_error_generic
    },
)

// ---------------------------------------------------------------------------------- sheet tambah ruang

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddRoomSheet(onCancel: () -> Unit, onAdd: suspend (NewRoom) -> LedgerError?) {
    val spacing = MaterialTheme.spacing
    var name by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(RoomKind.MENCUKUPI) }
    var icon by rememberSaveable { mutableStateOf(ROOM_ICON_CHOICES.first()) }
    var error by remember { mutableStateOf<LedgerError?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.s5)
            .padding(bottom = spacing.s5),
    ) {
        Text(stringResource(R.string.room_new_title), style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it.take(Room.NAME_MAX)
                error = null
            },
            label = { Text(stringResource(R.string.room_new_name)) },
            placeholder = { Text(stringResource(R.string.room_new_name_hint)) },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(roomErrorText(context, it)) } },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth().padding(top = spacing.s3),
        )

        Text(stringResource(R.string.room_new_kind), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = spacing.s3, bottom = spacing.s1))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.s2)) {
            RoomKind.entries.forEach { option ->
                FilterChip(selected = kind == option, onClick = { kind = option }, label = { Text(stringResource(kindLabel(option))) }, colors = rizqflowFilterChipColors())
            }
        }
        Text(
            stringResource(kindHint(kind)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.s1),
        )

        Text(stringResource(R.string.room_new_icon), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = spacing.s3, bottom = spacing.s1))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.s2)) {
            ROOM_ICON_CHOICES.forEach { key ->
                FilterChip(
                    selected = icon == key,
                    onClick = { icon = key },
                    label = { Icon(roomIcon(key), contentDescription = stringResource(roomIconLabel(key)), modifier = Modifier.size(20.dp)) },
                    colors = rizqflowFilterChipColors(),
                )
            }
        }

        Button(
            onClick = {
                busy = true
                scope.launch {
                    error = onAdd(NewRoom(name, kind, icon, EXTRA_ROOM_COLOR_SLOT))
                    busy = false
                }
            },
            enabled = name.isNotBlank() && !busy,
            modifier = Modifier.padding(top = spacing.s5).fillMaxWidth().height(52.dp),
        ) { Text(stringResource(R.string.room_new_confirm)) }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.catat_date_cancel)) }
    }
}
