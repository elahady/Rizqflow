package com.roziqrizal.rizqflow.ui.catat

import androidx.activity.compose.BackHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.allocation.AllocationEngine
import com.roziqrizal.rizqflow.domain.allocation.AllocationResult
import com.roziqrizal.rizqflow.domain.allocation.AllocationRule
import com.roziqrizal.rizqflow.domain.entitlement.Feature
import com.roziqrizal.rizqflow.domain.ledger.AllocationEntry
import com.roziqrizal.rizqflow.domain.ledger.BudgetWarning
import com.roziqrizal.rizqflow.domain.ledger.DuplicateTransactionDetector
import com.roziqrizal.rizqflow.domain.ledger.toDuplicateProbe
import com.roziqrizal.rizqflow.domain.ledger.CatatContext
import com.roziqrizal.rizqflow.domain.ledger.CatatContextLoader
import com.roziqrizal.rizqflow.domain.ledger.CatatDraft
import com.roziqrizal.rizqflow.domain.ledger.CatatIssue
import com.roziqrizal.rizqflow.domain.ledger.CatatMode
import com.roziqrizal.rizqflow.domain.ledger.DenahLoader
import com.roziqrizal.rizqflow.domain.ledger.SafeToSpend
import com.roziqrizal.rizqflow.domain.model.RoomKind
import com.roziqrizal.rizqflow.domain.ledger.INCOME_SOURCES
import com.roziqrizal.rizqflow.domain.ledger.LedgerError
import com.roziqrizal.rizqflow.domain.ledger.LedgerResult
import com.roziqrizal.rizqflow.domain.ledger.OneTimeSplit
import com.roziqrizal.rizqflow.domain.ledger.QuickFavorite
import com.roziqrizal.rizqflow.domain.ledger.TransactionSnapshot
import com.roziqrizal.rizqflow.domain.model.AccountId
import com.roziqrizal.rizqflow.domain.model.CategoryId
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.model.TransactionId
import com.roziqrizal.rizqflow.domain.model.TransactionKind
import com.roziqrizal.rizqflow.domain.role.RiskWarning
import com.roziqrizal.rizqflow.domain.money.Money
import com.roziqrizal.rizqflow.ui.RizqflowIcons
import com.roziqrizal.rizqflow.ui.formatDate
import com.roziqrizal.rizqflow.ui.formatPercent
import com.roziqrizal.rizqflow.ui.formatRupiah
import com.roziqrizal.rizqflow.ui.onboarding.AmountKeypad
import com.roziqrizal.rizqflow.ui.theme.CaslonFamily
import com.roziqrizal.rizqflow.ui.theme.rizqflow
import com.roziqrizal.rizqflow.ui.theme.rizqflowFilterChipColors
import com.roziqrizal.rizqflow.ui.theme.rizqflowSegmentedColors
import com.roziqrizal.rizqflow.ui.theme.spacing
import com.roziqrizal.rizqflow.workspace.AccountWorkspace
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Yang baru saja tersimpan: cukup untuk menyusun pesan dan mengurungkannya. [roomCount]: jumlah ruang
 * yang menerima alirannya. [previous] tidak null bila ini perubahan (bukan catatan baru): keadaan sebelumnya,
 * yang dipulihkan oleh Urungkan.
 */
data class SavedInfo(
    val id: TransactionId,
    val kind: TransactionKind,
    val amount: Money,
    val roomCount: Int,
    val previous: TransactionSnapshot? = null,
    /** Pengeluaran ini juga disimpan sebagai favorit (S13, S24). */
    val favoriteSaved: Boolean = false,
    /** Alasan favorit tidak tersimpan padahal diminta (mis. nama sudah ada, sudah 6 favorit). */
    val favoriteError: LedgerError? = null,
)

/** Hasil pratinjau S07 yang sedang dilihat pengguna. [split] tidak null berarti "Ubah sekali ini" aktif. */
private data class Review(val allocation: AllocationResult, val split: OneTimeSplit?)

/**
 * Layar Catat (S06) dan Pratinjau alokasi (S07). Isian ada di [CatatDraft] yang murni dan disimpan
 * lewat `rememberSaveable`; layar ini hanya menampilkan dan memanggil layanan. Pemasukan melewati
 * S07 dulu (rezeki terlihat mengalir sebelum disimpan); pengeluaran dan transfer langsung simpan.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CatatFlow(
    workspace: AccountWorkspace,
    onClose: () -> Unit,
    onSaved: (SavedInfo) -> Unit,
    /** Bila diisi, layar ini menjadi Detail transaksi (S09): kolom terisi dari transaksi itu, tanpa tab dan tanpa pratinjau. */
    editing: TransactionId? = null,
    onDeleted: (TransactionSnapshot) -> Unit = {},
    /** Membuka paywall Pro untuk fitur tertentu (mis. input suara di catat cerdas); diisi MainHost. */
    onOpenPaywall: (Feature) -> Unit = {},
) {
    val today = remember { LocalDate.now() }
    var loaded by remember { mutableStateOf<CatatContext?>(null) }
    var existing by remember { mutableStateOf<TransactionSnapshot?>(null) }
    LaunchedEffect(workspace, editing) {
        val repos = workspace.repositories
        val snapshot = editing?.let { workspace.ledger.snapshotOf(it) }
        if (editing != null && snapshot == null) {
            onClose() // sudah dihapus di tempat lain
            return@LaunchedEffect
        }
        existing = snapshot
        loaded = CatatContextLoader(repos.accounts, repos.rooms, repos.transactions).load(snapshot?.transaction)
    }
    val context = loaded
    if (context == null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }

    var draft by rememberSaveable(stateSaver = DraftSaver) {
        mutableStateOf(existing?.let { CatatDraft.from(it.transaction) } ?: CatatDraft.start(context, today))
    }
    var confirmingDelete by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf<Review?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<LedgerError?>(null) }
    var warning by remember { mutableStateOf<BudgetWarning?>(null) }
    var riskWarning by remember { mutableStateOf<RiskWarning?>(null) }
    var pickingDate by remember { mutableStateOf(false) }
    var asFavorite by rememberSaveable { mutableStateOf(false) }
    var smartInputOpen by remember { mutableStateOf(false) }
    // Deteksi transaksi ganda hanya untuk simpan baru (bukan Detail); simpan yang ditahan menunggu pilihan pengguna.
    val duplicates = remember(workspace) { DuplicateTransactionDetector(workspace.repositories.transactions, System::currentTimeMillis) }
    var duplicateHold by remember { mutableStateOf<DuplicateHold?>(null) }
    val scope = rememberCoroutineScope()

    // Banner lembut jatah terlampaui: dihitung ulang setiap nominal, ruang, atau tanggal berubah.
    LaunchedEffect(draft.mode, draft.roomId, draft.amount, draft.date) {
        val room = draft.roomId
        warning = if (draft.mode == CatatMode.EXPENSE && draft.amount.isPositive && room != null) {
            workspace.ledger.budgetWarning(room, draft.amount, draft.date, excluding = editing)
        } else {
            null
        }
        // Ruang Trader (Pro): pengeluaran di atas batas risiko per trade diberi tahu, bukan diblokir.
        riskWarning = if (draft.mode == CatatMode.EXPENSE && draft.amount.isPositive && room != null) {
            workspace.roles.riskWarning(room, draft.amount)
        } else {
            null
        }
    }

    // Sisa aman hari ini: hanya untuk pengeluaran baru bertanggal hari ini; dimuat ulang setelah simpan beruntun.
    var safe by remember { mutableStateOf<SafeToSpend?>(null) }
    var safeVersion by remember { mutableIntStateOf(0) }
    val showSafe = editing == null && draft.mode == CatatMode.EXPENSE && draft.date == today
    LaunchedEffect(showSafe, safeVersion) {
        safe = if (showSafe) {
            val repos = workspace.repositories
            DenahLoader(repos.rooms, repos.transactions, repos.accounts).safeToSpend(today)
        } else {
            null
        }
    }

    BackHandler(enabled = !busy) { if (review != null) review = null else onClose() }

    fun change(next: CatatDraft) {
        draft = next
        error = null
    }

    fun saveDirect(keepOpen: Boolean, checked: Boolean = false) {
        if (busy || draft.issue(context) != null) return
        busy = true
        error = null
        scope.launch {
            if (editing == null && !checked) {
                val match = duplicates.findDuplicate(draft.toDuplicateProbe())
                if (match != null) {
                    busy = false
                    duplicateHold = DuplicateHold(match) { saveDirect(keepOpen, checked = true) }
                    return@launch
                }
            }
            val result = if (draft.mode == CatatMode.EXPENSE) {
                workspace.ledger.recordExpense(draft.toExpense())
            } else {
                workspace.ledger.recordTransfer(draft.toTransfer())
            }
            busy = false
            when (result) {
                is LedgerResult.Success -> {
                    val tx = result.value
                    var favoriteError: LedgerError? = null
                    val wantsFavorite = asFavorite && tx.kind == TransactionKind.EXPENSE
                    if (wantsFavorite) {
                        // Nama: catatan bila ada, kalau tidak nama kategorinya.
                        val name = draft.note.trim().ifEmpty { context.categoriesOf(tx.roomId!!).firstOrNull { it.id == tx.categoryId }?.name.orEmpty() }.take(QuickFavorite.NAME_MAX)
                        favoriteError = (workspace.favorites.create(name, tx.amount, tx.roomId!!, tx.categoryId!!, tx.accountId) as? LedgerResult.Failure)?.error
                    }
                    onSaved(SavedInfo(tx.id, tx.kind, tx.amount, 0, favoriteSaved = wantsFavorite && favoriteError == null, favoriteError = favoriteError))
                    asFavorite = false
                    safeVersion++
                    if (keepOpen) change(draft.copy(digits = "", note = "")) else onClose()
                }

                is LedgerResult.Failure -> error = result.error
            }
        }
    }

    fun openReview() {
        if (busy || draft.issue(context) != null) return
        scope.launch { review = Review(workspace.ledger.previewIncome(draft.amount), split = null) }
    }

    fun updateSplit(split: OneTimeSplit?) {
        scope.launch { review = Review(workspace.ledger.previewIncome(draft.amount, split?.toRules()), split) }
    }

    fun confirmIncome(checked: Boolean = false) {
        val current = review ?: return
        if (busy) return
        busy = true
        error = null
        scope.launch {
            if (!checked) {
                val match = duplicates.findDuplicate(draft.toDuplicateProbe())
                if (match != null) {
                    busy = false
                    duplicateHold = DuplicateHold(match) { confirmIncome(checked = true) }
                    return@launch
                }
            }
            val result = workspace.ledger.recordIncome(draft.toIncome(current.split?.toRules()))
            busy = false
            when (result) {
                is LedgerResult.Success -> {
                    val receipt = result.value
                    onSaved(SavedInfo(receipt.transaction.id, TransactionKind.INCOME, receipt.transaction.amount, receipt.allocation.shares.count { it.amount.isPositive }))
                    onClose()
                }

                is LedgerResult.Failure -> error = result.error
            }
        }
    }

    fun saveEdit() {
        val id = editing ?: return
        if (busy || draft.issue(context) != null) return
        busy = true
        error = null
        scope.launch {
            val result = workspace.ledger.updateTransaction(id, draft)
            busy = false
            when (result) {
                is LedgerResult.Success -> {
                    val previous = result.value
                    onSaved(SavedInfo(id, previous.transaction.kind, draft.amount, 0, previous))
                    onClose()
                }

                is LedgerResult.Failure -> error = result.error
            }
        }
    }

    fun deleteIt() {
        val snapshot = existing ?: return
        if (busy) return
        busy = true
        scope.launch {
            workspace.ledger.delete(snapshot.transaction.id)
            busy = false
            onDeleted(snapshot)
            onClose()
        }
    }

    duplicateHold?.let { hold ->
        DuplicateWarningDialog(
            match = hold.match,
            onKeep = {
                duplicateHold = null
                hold.proceed()
            },
            // Batalkan berarti catatan baru ini tidak disimpan dan layar ditutup; data yang sudah ada tidak disentuh.
            onCancel = {
                duplicateHold = null
                onClose()
            },
            onDismiss = { duplicateHold = null },
        )
    }

    val shown = review
    if (shown != null) {
        ReviewScreen(
            context = context,
            draft = draft,
            allocation = shown.allocation,
            split = shown.split,
            busy = busy,
            error = error,
            onBack = { review = null },
            onToggleEdit = { on -> updateSplit(if (on) OneTimeSplit.from(context.rules) else null) },
            onPercent = { index, value -> shown.split?.let { updateSplit(it.set(index, value)) } },
            onStep = { index, delta -> shown.split?.let { updateSplit(it.step(index, delta)) } },
            onConfirm = { confirmIncome() },
        )
        return
    }

    val spacing = MaterialTheme.spacing
    val issue = draft.issue(context)
    val imeVisible = WindowInsets.isImeVisible
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        // Bagian atas bisa digulir; papan angka dan tombol simpan menempel di bawah supaya selalu terjangkau.
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.s5)
                .padding(top = spacing.s3),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose, enabled = !busy) {
                    Icon(RizqflowIcons.Tutup, contentDescription = stringResource(R.string.catat_close))
                }
                Text(
                    stringResource(if (editing != null) R.string.detail_title else R.string.catat_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                // Input cerdas (Tahap 11): hanya untuk catatan baru, tidak untuk Detail (S09).
                if (editing == null) {
                    IconButton(onClick = { smartInputOpen = true }, enabled = !busy) {
                        Icon(RizqflowIcons.Petir, contentDescription = stringResource(R.string.catat_smart_input))
                    }
                }
            }

            if (editing == null) {
                ModeTabs(draft.mode, canTransfer = context.canTransfer, onMode = { change(draft.withMode(it, context)) })
                if (!context.canTransfer && draft.mode == CatatMode.TRANSFER) {
                    Hint(stringResource(R.string.catat_transfer_needs_two))
                }
            } else {
                // Jenis transaksi tidak bisa diganti; hanya ditampilkan.
                Text(
                    stringResource(
                        when (draft.mode) {
                            CatatMode.INCOME -> R.string.catat_tab_income
                            CatatMode.EXPENSE -> R.string.catat_tab_expense
                            CatatMode.TRANSFER -> R.string.catat_tab_transfer
                        },
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = spacing.s3),
                )
            }

            // Nominal besar: satu-satunya hal yang paling penting di layar ini.
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = spacing.s3), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Rp", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = formatRupiah(draft.amount).removePrefix("Rp\u00A0"),
                    style = MaterialTheme.typography.displaySmall.copy(fontFamily = CaslonFamily),
                    color = if (draft.amount.isPositive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            // Sisa aman: setelah nominal diisi di ruang Mencukupi, tampil sisa setelah pengeluaran ini.
            safe?.let { s ->
                val everyday = context.rooms.firstOrNull { it.id == draft.roomId }?.kind == RoomKind.MENCUKUPI
                val after = if (draft.amount.isPositive && everyday) s.remainingAfter(draft.amount) else null
                Text(
                    stringResource(if (after != null) R.string.catat_safe_after else R.string.catat_safe_now, formatRupiah(after ?: s.remaining)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            // Banner dan petunjuk tepat di bawah nominal supaya terlihat tanpa menggulir.
            warning?.let {
                SoftBanner(stringResource(R.string.catat_over_budget, it.room.name, formatRupiah(it.spentAfter), formatRupiah(it.allocated)))
            }
            riskWarning?.let {
                SoftBanner(stringResource(R.string.catat_over_risk, formatRupiah(it.maxRisk), formatRupiah(it.amount)))
            }
            error?.let { Hint(stringResource(errorText(it))) }
            if (issue != null && draft.amount.isPositive) Hint(stringResource(issueText(issue)))

            when (draft.mode) {
                CatatMode.INCOME -> {
                    FieldLabel(R.string.catat_source)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.s2)) {
                        (INCOME_SOURCES + listOfNotNull(draft.source?.takeIf { it.isNotBlank() && it !in INCOME_SOURCES })).forEach { source ->
                            FilterChip(
                                selected = draft.source == source,
                                onClick = { change(draft.withSource(source)) },
                                label = { Text(source) },
                                colors = rizqflowFilterChipColors(),
                            )
                        }
                    }
                    FieldLabel(R.string.catat_account_to)
                    AccountChips(context, selected = draft.accountId, onPick = { change(draft.withAccount(it, context)) })
                }

                CatatMode.EXPENSE -> {
                    if (context.rooms.isEmpty()) {
                        Hint(stringResource(R.string.catat_no_rooms))
                    } else {
                        FieldLabel(R.string.catat_room)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.s2)) {
                            context.rooms.forEach { room ->
                                FilterChip(
                                    selected = draft.roomId == room.id,
                                    onClick = { change(draft.withRoom(room.id, context)) },
                                    label = { Text(room.name) },
                                    colors = rizqflowFilterChipColors(),
                                )
                            }
                        }
                        FieldLabel(R.string.catat_category)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.s2)) {
                            draft.roomId?.let(context::categoriesOf).orEmpty().forEach { category ->
                                FilterChip(
                                    selected = draft.categoryId == category.id,
                                    onClick = { change(draft.withCategory(category.id)) },
                                    label = { Text(category.name) },
                                    colors = rizqflowFilterChipColors(),
                                )
                            }
                        }
                        FieldLabel(R.string.catat_account)
                        AccountChips(context, selected = draft.accountId, onPick = { change(draft.withAccount(it, context)) })
                    }
                }

                CatatMode.TRANSFER -> {
                    FieldLabel(R.string.catat_from)
                    AccountChips(context, selected = draft.accountId, onPick = { change(draft.withAccount(it, context)) })
                    FieldLabel(R.string.catat_to)
                    AccountChips(context, selected = draft.toAccountId, onPick = { change(draft.withToAccount(it, context)) })
                }
            }

            FieldLabel(R.string.catat_date)
            OutlinedButton(onClick = { pickingDate = true }, enabled = !busy) { Text(formatDate(draft.date, today)) }

            OutlinedTextField(
                value = draft.note,
                onValueChange = { change(draft.withNote(it)) },
                label = { Text(stringResource(R.string.catat_note)) },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().padding(top = spacing.s3),
            )

            if (editing != null && draft.mode == CatatMode.INCOME) {
                existing?.let { AllocationSnapshotSection(context, it.entries, draft.amount) }
            }

            // Jadikan favorit: hanya pengeluaran baru. Nominal, ruang, kategori, dan akun ikut tersimpan.
            if (editing == null && draft.mode == CatatMode.EXPENSE) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = spacing.s3),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.s3),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.catat_favorite), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.catat_favorite_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    androidx.compose.material3.Switch(checked = asFavorite, onCheckedChange = { asFavorite = it }, enabled = !busy)
                }
            }
        }

        Column(modifier = Modifier.padding(horizontal = spacing.s5).padding(top = spacing.s2, bottom = spacing.s3)) {
            // Saat papan ketik sistem terbuka (mengisi catatan), papan angka disembunyikan supaya layar tidak sesak.
            if (!imeVisible) AmountKeypad(enabled = !busy, onKey = { change(draft.pressKey(it)) })

            val canSave = issue == null && !busy
            if (editing != null) {
                Button(
                    onClick = ::saveEdit,
                    enabled = canSave,
                    modifier = Modifier.padding(top = spacing.s3).fillMaxWidth().height(52.dp),
                ) { Text(stringResource(R.string.catat_save)) }
                TextButton(onClick = { confirmingDelete = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.detail_delete), color = MaterialTheme.colorScheme.error)
                }
            } else when (draft.mode) {
                CatatMode.INCOME -> Button(
                    onClick = ::openReview,
                    enabled = canSave,
                    modifier = Modifier.padding(top = spacing.s3).fillMaxWidth().height(52.dp),
                ) { Text(stringResource(R.string.catat_next)) }

                CatatMode.EXPENSE -> Row(
                    modifier = Modifier.padding(top = spacing.s3).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.s3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = { saveDirect(false) }, enabled = canSave, modifier = Modifier.weight(1f).height(52.dp)) {
                        Text(stringResource(R.string.catat_save))
                    }
                    OutlinedButton(onClick = { saveDirect(true) }, enabled = canSave, modifier = Modifier.weight(1f).height(52.dp)) {
                        Text(stringResource(R.string.catat_save_more))
                    }
                }

                CatatMode.TRANSFER -> Button(
                    onClick = { saveDirect(false) },
                    enabled = canSave,
                    modifier = Modifier.padding(top = spacing.s3).fillMaxWidth().height(52.dp),
                ) { Text(stringResource(R.string.catat_save)) }
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.detail_delete_title)) },
            text = { Text(stringResource(R.string.detail_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    deleteIt()
                }) { Text(stringResource(R.string.detail_delete_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.catat_date_cancel)) } },
        )
    }

    if (pickingDate) {
        val startMillis = draft.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val todayMillis = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = startMillis,
            // Yang dicatat adalah yang sudah terjadi: tanggal depan tidak bisa dipilih.
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= todayMillis
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { ms ->
                        change(draft.withDate(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()))
                    }
                    pickingDate = false
                }) { Text(stringResource(R.string.catat_date_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text(stringResource(R.string.catat_date_cancel)) } },
        ) { DatePicker(state = pickerState) }
    }

    if (smartInputOpen) {
        SmartInputSheet(
            workspace = workspace,
            catatContext = context,
            today = today,
            onUse = {
                change(it)
                smartInputOpen = false
            },
            onDismiss = { smartInputOpen = false },
            onVoiceLocked = {
                smartInputOpen = false
                onOpenPaywall(Feature.VOICE_INPUT)
            },
        )
    }
}

// ---------------------------------------------------------------------------------- bagian kecil

@Composable
private fun ModeTabs(mode: CatatMode, canTransfer: Boolean, onMode: (CatatMode) -> Unit) {
    val items = listOf(
        CatatMode.INCOME to R.string.catat_tab_income,
        CatatMode.EXPENSE to R.string.catat_tab_expense,
        CatatMode.TRANSFER to R.string.catat_tab_transfer,
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = MaterialTheme.spacing.s2)) {
        items.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = mode == value,
                onClick = { onMode(value) },
                shape = SegmentedButtonDefaults.itemShape(index, items.size),
                colors = rizqflowSegmentedColors(),
                // Dengan satu akun, Transfer nonaktif; penjelasannya tampil di bawah saat tab ini dipilih.
                enabled = value != CatatMode.TRANSFER || canTransfer,
                label = { Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@Composable
private fun FieldLabel(label: Int) {
    Text(
        text = stringResource(label),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = MaterialTheme.spacing.s4, bottom = MaterialTheme.spacing.s1),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountChips(context: CatatContext, selected: AccountId?, onPick: (AccountId) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.s2)) {
        context.accounts.forEach { account ->
            FilterChip(
                selected = selected == account.id,
                onClick = { onPick(account.id) },
                label = { Text(account.name) },
                colors = rizqflowFilterChipColors(),
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(top = MaterialTheme.spacing.s3)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Banner lembut (bukan merah): jatah atau batas risiko terlampaui, tetapi tetap bisa disimpan. */
@Composable
private fun SoftBanner(text: String) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier = Modifier
            .padding(top = spacing.s3)
            .fillMaxWidth()
            .background(MaterialTheme.rizqflow.statusWarning.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
            .padding(spacing.s3)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(spacing.s3),
    ) {
        Icon(RizqflowIcons.Peringatan, contentDescription = null, tint = MaterialTheme.rizqflow.statusWarning)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun issueText(issue: CatatIssue): Int = when (issue) {
    CatatIssue.NO_AMOUNT -> R.string.catat_issue_amount
    CatatIssue.NO_ACCOUNT -> R.string.catat_issue_account
    CatatIssue.NO_ROOM -> R.string.catat_no_rooms
    CatatIssue.NO_CATEGORY -> R.string.catat_issue_category
    CatatIssue.TRANSFER_NEEDS_TWO_ACCOUNTS -> R.string.catat_transfer_needs_two
    CatatIssue.SAME_ACCOUNT -> R.string.catat_issue_same_account
}

internal fun errorText(error: LedgerError): Int = when (error) {
    LedgerError.ACCOUNT_ARCHIVED, LedgerError.ACCOUNT_NOT_FOUND -> R.string.catat_error_account
    LedgerError.ROOM_ARCHIVED, LedgerError.ROOM_NOT_FOUND, LedgerError.CATEGORY_NOT_IN_ROOM -> R.string.catat_error_room
    LedgerError.NOTE_TOO_LONG, LedgerError.SOURCE_TOO_LONG -> R.string.catat_error_note
    else -> R.string.catat_error_generic
}

// ---------------------------------------------------------------------------------- penyimpanan isian

/** Menyimpan [CatatDraft] agar selamat dari layar diputar; nilai kosong ditulis sebagai teks kosong. */
private val DraftSaver = Saver<CatatDraft, List<String>>(
    save = {
        listOf(
            it.mode.name, it.digits, it.source.orEmpty(), it.accountId?.value.orEmpty(), it.toAccountId?.value.orEmpty(),
            it.roomId?.value.orEmpty(), it.categoryId?.value.orEmpty(), it.date.toEpochDay().toString(), it.note,
        )
    },
    restore = {
        CatatDraft(
            mode = CatatMode.valueOf(it[0]),
            digits = it[1],
            source = it[2].ifEmpty { null },
            accountId = it[3].ifEmpty { null }?.let(::AccountId),
            toAccountId = it[4].ifEmpty { null }?.let(::AccountId),
            roomId = it[5].ifEmpty { null }?.let(::RoomId),
            categoryId = it[6].ifEmpty { null }?.let(::CategoryId),
            date = LocalDate.ofEpochDay(it[7].toLong()),
            note = it[8],
        )
    },
)

/**
 * "Dialirkan saat itu" di Detail pemasukan: persentase dari potret saat pemasukan dicatat, dengan jumlah
 * yang mengikuti nominal yang sedang diisi. Ruang dan persentasenya tidak bisa diubah dari sini.
 */
@Composable
private fun AllocationSnapshotSection(context: CatatContext, entries: List<AllocationEntry>, amount: Money) {
    if (entries.isEmpty()) return
    val spacing = MaterialTheme.spacing
    val allocation = if (amount.isPositive) {
        AllocationEngine.allocate(amount, entries.map { AllocationRule(it.roomId, it.share) })
    } else {
        null
    }
    FieldLabel(R.string.detail_allocated)
    entries.forEachIndexed { i, entry ->
        val room = context.rooms.firstOrNull { it.id == entry.roomId }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = spacing.s1),
            horizontalArrangement = Arrangement.spacedBy(spacing.s3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(room?.name.orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(formatPercent(entry.share.value), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatRupiah(allocation?.shares?.getOrNull(i)?.amount ?: entry.amount), style = MaterialTheme.typography.titleSmall)
        }
    }
    Text(
        stringResource(R.string.detail_allocated_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = spacing.s1),
    )
}
