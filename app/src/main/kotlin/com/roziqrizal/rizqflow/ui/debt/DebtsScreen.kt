package com.roziqrizal.rizqflow.ui.debt

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.debt.Debt
import com.roziqrizal.rizqflow.domain.debt.DebtDirection
import com.roziqrizal.rizqflow.domain.debt.DebtDraft
import com.roziqrizal.rizqflow.domain.debt.DebtEdit
import com.roziqrizal.rizqflow.domain.debt.DebtOverview
import com.roziqrizal.rizqflow.domain.debt.DebtPayment
import com.roziqrizal.rizqflow.domain.debt.DebtRow
import com.roziqrizal.rizqflow.domain.debt.DebtStatus
import com.roziqrizal.rizqflow.domain.ledger.CatatContext
import com.roziqrizal.rizqflow.domain.ledger.CatatContextLoader
import com.roziqrizal.rizqflow.domain.ledger.LedgerError
import com.roziqrizal.rizqflow.domain.ledger.LedgerResult
import com.roziqrizal.rizqflow.domain.model.AccountId
import com.roziqrizal.rizqflow.domain.money.Money
import com.roziqrizal.rizqflow.ui.RizqflowIcons
import com.roziqrizal.rizqflow.ui.catat.errorText
import com.roziqrizal.rizqflow.ui.formatDate
import com.roziqrizal.rizqflow.ui.formatRupiah
import com.roziqrizal.rizqflow.ui.recurring.AccountChips
import com.roziqrizal.rizqflow.ui.recurring.DateDialog
import com.roziqrizal.rizqflow.ui.recurring.Label
import com.roziqrizal.rizqflow.ui.recurring.digitsOnly
import com.roziqrizal.rizqflow.ui.theme.rizqflow
import com.roziqrizal.rizqflow.ui.theme.rizqflowSegmentedColors
import com.roziqrizal.rizqflow.ui.theme.spacing
import com.roziqrizal.rizqflow.workspace.AccountWorkspace
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Sheet yang sedang terbuka: tambah, rincian (ubah, riwayat, hapus), atau catat pelunasan. */
private sealed interface DebtSheet {
    data object New : DebtSheet

    data class Detail(val id: String) : DebtSheet

    data class Repay(val id: String) : DebtSheet
}

/**
 * Utang-piutang (Tahap 10, Gratis): ringkasan sisa piutang dan utang, daftar, tambah, ubah, catat
 * pelunasan, dan hapus. Pelunasan yang tercatat diumumkan pemanggil lewat [onRepaid] (pesan dan
 * pembayarannya untuk Urungkan) supaya tetap hidup setelah layar ini ditutup. Perubahan apa pun
 * diteruskan lewat [onChanged]; [refreshKey] yang berubah memuat ulang daftar.
 */
@Composable
fun DebtsScreen(
    workspace: AccountWorkspace,
    refreshKey: Int,
    onClose: () -> Unit,
    onChanged: () -> Unit,
    onRepaid: (message: String, payment: DebtPayment) -> Unit,
) {
    var overview by remember { mutableStateOf<DebtOverview?>(null) }
    var reminderOn by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf<DebtSheet?>(null) }
    val spacing = MaterialTheme.spacing
    val today = remember { LocalDate.now() }
    BackHandler(onBack = onClose)

    LaunchedEffect(refreshKey) {
        overview = workspace.debts.overview(today)
        reminderOn = workspace.reminder.settings().enabled
    }
    val data = overview

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        TextButton(onClick = onClose, modifier = Modifier.padding(start = spacing.s3, top = spacing.s2)) { Text(stringResource(R.string.onb_back)) }
        Text(
            stringResource(R.string.debts_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(horizontal = spacing.s5).padding(bottom = spacing.s2),
        )
        if (data == null) {
            Box(Modifier.fillMaxSize())
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.s4)
                    .padding(top = spacing.s2, bottom = spacing.s5),
            ) {
                if (data.rows.isEmpty()) {
                    Text(stringResource(R.string.debts_empty), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.debts_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = spacing.s2),
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.s4), modifier = Modifier.fillMaxWidth().padding(bottom = spacing.s3)) {
                        SumTile(stringResource(R.string.debts_sum_receivable), data.receivable, Modifier.weight(1f))
                        SumTile(stringResource(R.string.debts_sum_payable), data.payable, Modifier.weight(1f))
                    }
                    if (!reminderOn && data.rows.any { it.debt.dueDate != null && !it.isSettled }) {
                        Text(
                            stringResource(R.string.bills_reminder_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = spacing.s2),
                        )
                    }
                }
                data.rows.forEach { row ->
                    DebtRowItem(
                        row, today,
                        onOpen = { sheet = DebtSheet.Detail(row.debt.id) },
                        onRepay = { sheet = DebtSheet.Repay(row.debt.id) },
                    )
                    HorizontalDivider()
                }
                Button(
                    onClick = { sheet = DebtSheet.New },
                    modifier = Modifier.padding(top = spacing.s4).fillMaxWidth().height(52.dp),
                ) {
                    Icon(RizqflowIcons.Tambah, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.debts_add), modifier = Modifier.padding(start = spacing.s2))
                }
            }
        }
    }

    sheet?.let { current ->
        val row = data?.rows?.firstOrNull { it.debt.id == (current as? DebtSheet.Detail)?.id || it.debt.id == (current as? DebtSheet.Repay)?.id }
        when (current) {
            DebtSheet.New -> NewDebtSheet(workspace, today, onDismiss = { sheet = null }, onChanged = {
                sheet = null
                onChanged()
            })

            is DebtSheet.Detail -> if (row != null) {
                DetailSheet(workspace, row, today, onDismiss = { sheet = null }, onChanged = onChanged, onClosed = {
                    sheet = null
                    onChanged()
                })
            }

            is DebtSheet.Repay -> if (row != null) {
                RepaySheet(workspace, row, today, onDismiss = { sheet = null }, onRepaid = { message, payment ->
                    sheet = null
                    onRepaid(message, payment)
                })
            }
        }
    }
}

@Composable
private fun SumTile(label: String, amount: Money, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(formatRupiah(amount), style = MaterialTheme.typography.titleMedium)
    }
}

private fun statusLabel(status: DebtStatus): Int = when (status) {
    DebtStatus.OVERDUE -> R.string.debts_status_overdue
    DebtStatus.DUE_TODAY -> R.string.debts_status_today
    DebtStatus.DUE_SOON -> R.string.debts_status_soon
    DebtStatus.OPEN -> R.string.debts_status_open
    DebtStatus.SETTLED -> R.string.debts_status_settled
}

private fun statusIcon(status: DebtStatus): ImageVector = when (status) {
    DebtStatus.OVERDUE -> RizqflowIcons.Peringatan
    DebtStatus.DUE_TODAY, DebtStatus.DUE_SOON -> RizqflowIcons.Jam
    DebtStatus.OPEN -> RizqflowIcons.Lingkaran
    DebtStatus.SETTLED -> RizqflowIcons.Centang
}

private fun directionLabel(direction: DebtDirection): Int =
    if (direction == DebtDirection.LENT) R.string.debts_kind_lent else R.string.debts_kind_borrowed

@Composable
private fun DebtRowItem(row: DebtRow, today: LocalDate, onOpen: () -> Unit, onRepay: () -> Unit) {
    val spacing = MaterialTheme.spacing
    val debt = row.debt
    val status = row.statusOn(today)
    val statusColor = when (status) {
        DebtStatus.OVERDUE, DebtStatus.DUE_TODAY -> MaterialTheme.rizqflow.statusWarning
        DebtStatus.SETTLED -> MaterialTheme.rizqflow.statusGood
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val due = debt.dueDate
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = spacing.s3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s3),
    ) {
        Column(modifier = Modifier.weight(1f).clickable(role = Role.Button, onClick = onOpen)) {
            Text(debt.party, style = MaterialTheme.typography.titleSmall)
            Text(stringResource(directionLabel(debt.direction)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.s1)) {
                Icon(statusIcon(status), contentDescription = null, tint = statusColor, modifier = Modifier.size(16.dp))
                Text(
                    stringResource(R.string.bills_row_meta, stringResource(statusLabel(status)), if (due != null) stringResource(R.string.debts_row_due, formatDate(due, today)) else stringResource(R.string.debts_no_due)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(spacing.s1)) {
            Text(stringResource(R.string.debts_outstanding, formatRupiah(row.outstanding)), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.debts_of, formatRupiah(debt.principal)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!row.isSettled) {
                OutlinedButton(onClick = onRepay, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(if (debt.direction == DebtDirection.LENT) R.string.debts_receive else R.string.debts_pay))
                }
            }
        }
    }
}

private fun debtErrorText(error: LedgerError): Int = when (error) {
    LedgerError.INVALID_PARTY -> R.string.debts_err_party
    LedgerError.INVALID_DUE_DATE -> R.string.debts_err_due
    LedgerError.DEBT_OVERPAID -> R.string.debts_err_overpaid
    LedgerError.AMOUNT_NOT_POSITIVE -> R.string.manage_err_amount
    else -> errorText(error)
}

/** Baris "label + saklar" untuk pilihan ya atau tidak, dengan penjelasan di bawahnya. */
@Composable
private fun SwitchRow(label: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = spacing.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s3),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ---------------------------------------------------------------------------------- tambah

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewDebtSheet(workspace: AccountWorkspace, today: LocalDate, onDismiss: () -> Unit, onChanged: () -> Unit) {
    var context by remember { mutableStateOf<CatatContext?>(null) }
    LaunchedEffect(Unit) {
        context = CatatContextLoader(workspace.repositories.accounts, workspace.repositories.rooms, workspace.repositories.transactions).load()
    }
    val loaded = context ?: return
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        NewDebtForm(workspace, loaded, today, onCancel = onDismiss, onChanged = onChanged)
    }
}

@Composable
private fun NewDebtForm(workspace: AccountWorkspace, context: CatatContext, today: LocalDate, onCancel: () -> Unit, onChanged: () -> Unit) {
    val spacing = MaterialTheme.spacing
    var direction by rememberSaveable { mutableStateOf(DebtDirection.LENT) }
    var party by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var move by rememberSaveable { mutableStateOf(true) }
    var accountId by rememberSaveable { mutableStateOf(context.lastAccountId?.value ?: context.accounts.firstOrNull()?.id?.value) }
    var start by rememberSaveable { mutableLongStateOf(today.toEpochDay()) }
    var due by rememberSaveable { mutableLongStateOf(NO_DATE) }
    var collectible by rememberSaveable { mutableStateOf(true) }
    var note by rememberSaveable { mutableStateOf("") }
    var pickingStart by remember { mutableStateOf(false) }
    var pickingDue by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<LedgerError?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val startDate = LocalDate.ofEpochDay(start)
    val dueDate = due.takeIf { it != NO_DATE }?.let(LocalDate::ofEpochDay)
    val amountValue = amount.toLongOrNull() ?: 0L
    val ready = party.isNotBlank() && amountValue > 0 && (!move || accountId != null)

    fun save() {
        busy = true
        scope.launch {
            val draft = DebtDraft(
                direction = direction, party = party, amount = Money.rupiah(amountValue), startDate = startDate, dueDate = dueDate,
                note = note, collectible = collectible, accountId = accountId?.let(::AccountId), recordMovement = move,
            )
            when (val result = workspace.debts.create(draft)) {
                is LedgerResult.Success -> onChanged()
                is LedgerResult.Failure -> error = result.error
            }
            busy = false
        }
    }

    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = spacing.s5).padding(bottom = spacing.s5)) {
        Text(stringResource(R.string.debts_new), style = MaterialTheme.typography.titleLarge)

        val kinds = listOf(DebtDirection.LENT to R.string.debts_kind_lent, DebtDirection.BORROWED to R.string.debts_kind_borrowed)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = spacing.s3)) {
            kinds.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = direction == value,
                    onClick = { direction = value },
                    shape = SegmentedButtonDefaults.itemShape(index, kinds.size),
                    colors = rizqflowSegmentedColors(),
                    label = { Text(stringResource(label), maxLines = 1) },
                )
            }
        }
        Text(
            stringResource(if (direction == DebtDirection.LENT) R.string.debts_kind_lent_hint else R.string.debts_kind_borrowed_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.s1),
        )

        OutlinedTextField(
            value = party,
            onValueChange = {
                party = it.take(Debt.PARTY_MAX)
                error = null
            },
            label = { Text(stringResource(R.string.debts_party)) },
            placeholder = { Text(stringResource(R.string.debts_party_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth().padding(top = spacing.s3),
        )
        OutlinedTextField(
            value = amount,
            onValueChange = {
                amount = digitsOnly(it)
                error = null
            },
            label = { Text(stringResource(R.string.manage_amount)) },
            prefix = { Text("Rp ") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().padding(top = spacing.s3),
        )

        Label(R.string.debts_start)
        OutlinedButton(onClick = { pickingStart = true }, enabled = !busy) { Text(formatDate(startDate, today)) }

        Label(R.string.debts_due)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.s2)) {
            OutlinedButton(onClick = { pickingDue = true }, enabled = !busy) {
                Text(if (dueDate != null) formatDate(dueDate, today) else stringResource(R.string.debts_no_due))
            }
            if (dueDate != null) TextButton(onClick = { due = NO_DATE }) { Text(stringResource(R.string.recurring_end_clear)) }
        }

        SwitchRow(
            label = stringResource(R.string.debts_move),
            hint = stringResource(if (direction == DebtDirection.LENT) R.string.debts_move_lent_hint else R.string.debts_move_borrowed_hint),
            checked = move,
            onChange = { move = it },
        )
        if (move) {
            Label(R.string.catat_account)
            AccountChips(context, accountId) { accountId = it }
        }

        if (direction == DebtDirection.LENT) {
            SwitchRow(stringResource(R.string.debts_collectible), stringResource(R.string.debts_collectible_hint), collectible) { collectible = it }
        }

        OutlinedTextField(
            value = note,
            onValueChange = {
                note = it
                error = null
            },
            label = { Text(stringResource(R.string.catat_note)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth().padding(top = spacing.s4),
        )

        error?.let {
            Text(stringResource(debtErrorText(it)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = spacing.s3))
        }

        Button(onClick = ::save, enabled = ready && !busy, modifier = Modifier.padding(top = spacing.s5).fillMaxWidth().height(52.dp)) {
            Text(stringResource(R.string.manage_save))
        }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.catat_date_cancel)) }
    }

    if (pickingStart) {
        DateDialog(
            initial = startDate,
            minimum = today.minusYears(BACK_YEARS),
            onPicked = {
                start = it.toEpochDay()
                if (due != NO_DATE && due < start) due = NO_DATE
                error = null
            },
            onDismiss = { pickingStart = false },
        )
    }
    if (pickingDue) {
        DateDialog(
            initial = dueDate ?: startDate,
            minimum = startDate,
            onPicked = {
                due = it.toEpochDay()
                error = null
            },
            onDismiss = { pickingDue = false },
        )
    }
}

// ---------------------------------------------------------------------------------- rincian

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailSheet(workspace: AccountWorkspace, row: DebtRow, today: LocalDate, onDismiss: () -> Unit, onChanged: () -> Unit, onClosed: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        DetailForm(workspace, row, today, onCancel = onDismiss, onChanged = onChanged, onClosed = onClosed)
    }
}

@Composable
private fun DetailForm(workspace: AccountWorkspace, row: DebtRow, today: LocalDate, onCancel: () -> Unit, onChanged: () -> Unit, onClosed: () -> Unit) {
    val spacing = MaterialTheme.spacing
    val debt = row.debt
    var party by rememberSaveable(debt.id) { mutableStateOf(debt.party) }
    var due by rememberSaveable(debt.id) { mutableLongStateOf(debt.dueDate?.toEpochDay() ?: NO_DATE) }
    var collectible by rememberSaveable(debt.id) { mutableStateOf(debt.collectible) }
    var note by rememberSaveable(debt.id) { mutableStateOf(debt.note.orEmpty()) }
    var pickingDue by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var deletingPayment by remember { mutableStateOf<DebtPayment?>(null) }
    var error by remember { mutableStateOf<LedgerError?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dueDate = due.takeIf { it != NO_DATE }?.let(LocalDate::ofEpochDay)

    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = spacing.s5).padding(bottom = spacing.s5)) {
        Text(debt.party, style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.debts_detail_meta, stringResource(directionLabel(debt.direction)), formatDate(debt.startDate, today)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val accountName = row.accountName
        Text(
            if (accountName != null) stringResource(R.string.debts_from_account, accountName) else stringResource(R.string.debts_no_movement),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.debts_progress, formatRupiah(row.repaid), formatRupiah(debt.principal)),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = spacing.s3),
        )

        Label(R.string.debts_history)
        if (row.payments.isEmpty()) {
            Text(stringResource(R.string.debts_history_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        row.payments.forEach { payment ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.bills_row_meta, formatDate(payment.paidOn, today), formatRupiah(payment.amount)),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { deletingPayment = payment }, enabled = !busy) {
                    Text(stringResource(R.string.debts_delete_confirm), color = MaterialTheme.colorScheme.error)
                }
            }
        }

        OutlinedTextField(
            value = party,
            onValueChange = {
                party = it.take(Debt.PARTY_MAX)
                error = null
            },
            label = { Text(stringResource(R.string.debts_party)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth().padding(top = spacing.s4),
        )
        Label(R.string.debts_due)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.s2)) {
            OutlinedButton(onClick = { pickingDue = true }, enabled = !busy) {
                Text(if (dueDate != null) formatDate(dueDate, today) else stringResource(R.string.debts_no_due))
            }
            if (dueDate != null) TextButton(onClick = { due = NO_DATE }) { Text(stringResource(R.string.recurring_end_clear)) }
        }
        if (debt.direction == DebtDirection.LENT) {
            SwitchRow(stringResource(R.string.debts_collectible), stringResource(R.string.debts_collectible_hint), collectible) { collectible = it }
        }
        OutlinedTextField(
            value = note,
            onValueChange = {
                note = it
                error = null
            },
            label = { Text(stringResource(R.string.catat_note)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth().padding(top = spacing.s4),
        )

        error?.let {
            Text(stringResource(debtErrorText(it)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = spacing.s3))
        }

        Button(
            onClick = {
                busy = true
                scope.launch {
                    when (val result = workspace.debts.update(debt.id, DebtEdit(party, dueDate, note, collectible))) {
                        is LedgerResult.Success -> onClosed()
                        is LedgerResult.Failure -> error = result.error
                    }
                    busy = false
                }
            },
            enabled = party.isNotBlank() && !busy,
            modifier = Modifier.padding(top = spacing.s5).fillMaxWidth().height(52.dp),
        ) { Text(stringResource(R.string.manage_save)) }
        TextButton(onClick = { confirmingDelete = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.debts_delete), color = MaterialTheme.colorScheme.error)
        }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.catat_date_cancel)) }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.debts_delete_title)) },
            text = { Text(stringResource(R.string.debts_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    scope.launch {
                        workspace.debts.delete(debt.id)
                        onClosed()
                    }
                }) { Text(stringResource(R.string.debts_delete_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.catat_date_cancel)) } },
        )
    }
    deletingPayment?.let { payment ->
        AlertDialog(
            onDismissRequest = { deletingPayment = null },
            title = { Text(stringResource(R.string.debts_history_delete_title)) },
            text = { Text(stringResource(R.string.debts_history_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    deletingPayment = null
                    scope.launch {
                        workspace.debts.removePayment(payment.id)
                        onChanged()
                    }
                }) { Text(stringResource(R.string.debts_delete_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletingPayment = null }) { Text(stringResource(R.string.catat_date_cancel)) } },
        )
    }
    if (pickingDue) {
        DateDialog(
            initial = dueDate ?: debt.startDate,
            minimum = debt.startDate,
            onPicked = {
                due = it.toEpochDay()
                error = null
            },
            onDismiss = { pickingDue = false },
        )
    }
}

// ---------------------------------------------------------------------------------- pelunasan

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepaySheet(workspace: AccountWorkspace, row: DebtRow, today: LocalDate, onDismiss: () -> Unit, onRepaid: (String, DebtPayment) -> Unit) {
    var context by remember { mutableStateOf<CatatContext?>(null) }
    LaunchedEffect(Unit) {
        context = CatatContextLoader(workspace.repositories.accounts, workspace.repositories.rooms, workspace.repositories.transactions).load()
    }
    val loaded = context ?: return
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        RepayForm(workspace, row, loaded, today, onCancel = onDismiss, onRepaid = onRepaid)
    }
}

@Composable
private fun RepayForm(workspace: AccountWorkspace, row: DebtRow, context: CatatContext, today: LocalDate, onCancel: () -> Unit, onRepaid: (String, DebtPayment) -> Unit) {
    val spacing = MaterialTheme.spacing
    val resources = LocalResources.current
    val debt = row.debt
    var amount by rememberSaveable(debt.id) { mutableStateOf(row.outstanding.minor.toString()) }
    var move by rememberSaveable(debt.id) { mutableStateOf(debt.accountId != null) }
    var accountId by rememberSaveable(debt.id) { mutableStateOf(debt.accountId?.value ?: context.lastAccountId?.value ?: context.accounts.firstOrNull()?.id?.value) }
    var error by remember { mutableStateOf<LedgerError?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val amountValue = amount.toLongOrNull() ?: 0L
    val lent = debt.direction == DebtDirection.LENT

    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = spacing.s5).padding(bottom = spacing.s5)) {
        Text(
            stringResource(if (lent) R.string.debts_repay_title_lent else R.string.debts_repay_title_borrowed, debt.party),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            stringResource(R.string.debts_outstanding, formatRupiah(row.outstanding)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = amount,
            onValueChange = {
                amount = digitsOnly(it)
                error = null
            },
            label = { Text(stringResource(R.string.manage_amount)) },
            prefix = { Text("Rp ") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().padding(top = spacing.s3),
        )
        TextButton(onClick = { amount = row.outstanding.minor.toString() }) { Text(stringResource(R.string.debts_repay_full)) }

        SwitchRow(
            label = stringResource(R.string.debts_move_short),
            hint = stringResource(if (lent) R.string.debts_repay_move_lent_hint else R.string.debts_repay_move_borrowed_hint),
            checked = move,
            onChange = { move = it },
        )
        if (move) {
            Label(R.string.catat_account)
            AccountChips(context, accountId) { accountId = it }
        }

        error?.let {
            Text(stringResource(debtErrorText(it)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = spacing.s3))
        }

        Button(
            onClick = {
                busy = true
                scope.launch {
                    val paid = Money.rupiah(amountValue)
                    when (val result = workspace.debts.repay(debt.id, paid, today, if (move) accountId?.let(::AccountId) else null)) {
                        is LedgerResult.Success -> {
                            val repayment = result.value
                            val message = if (repayment.settled) {
                                resources.getString(R.string.debts_repaid_settled, formatRupiah(paid), debt.party)
                            } else {
                                resources.getString(R.string.debts_repaid, formatRupiah(paid), debt.party, formatRupiah(row.outstanding - paid))
                            }
                            onRepaid(message, repayment.payment)
                        }

                        is LedgerResult.Failure -> error = result.error
                    }
                    busy = false
                }
            },
            enabled = amountValue > 0 && (!move || accountId != null) && !busy,
            modifier = Modifier.padding(top = spacing.s5).fillMaxWidth().height(52.dp),
        ) { Text(stringResource(R.string.debts_repay_button)) }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.catat_date_cancel)) }
    }
}

private const val NO_DATE = Long.MIN_VALUE
private const val BACK_YEARS = 5L
