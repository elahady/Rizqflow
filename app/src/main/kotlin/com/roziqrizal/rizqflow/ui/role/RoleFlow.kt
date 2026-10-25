package com.roziqrizal.rizqflow.ui.role

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.allocation.BasisPoints
import com.roziqrizal.rizqflow.domain.ledger.AmountPad
import com.roziqrizal.rizqflow.domain.ledger.LedgerError
import com.roziqrizal.rizqflow.domain.ledger.LedgerResult
import com.roziqrizal.rizqflow.domain.model.AccountId
import com.roziqrizal.rizqflow.domain.model.CategoryId
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.role.DcaPlan
import com.roziqrizal.rizqflow.domain.role.DcaStatus
import com.roziqrizal.rizqflow.domain.role.DcaView
import com.roziqrizal.rizqflow.domain.role.RoleKind
import com.roziqrizal.rizqflow.domain.role.RoleOverview
import com.roziqrizal.rizqflow.domain.role.TraderProfile
import com.roziqrizal.rizqflow.ui.Notifier
import com.roziqrizal.rizqflow.ui.RizqflowIcons
import com.roziqrizal.rizqflow.ui.catat.errorText
import com.roziqrizal.rizqflow.ui.formatPercent
import com.roziqrizal.rizqflow.ui.formatRupiah
import com.roziqrizal.rizqflow.ui.onboarding.AmountKeypad
import com.roziqrizal.rizqflow.ui.theme.rizqflow
import com.roziqrizal.rizqflow.ui.theme.rizqflowFilterChipColors
import com.roziqrizal.rizqflow.ui.theme.spacing
import com.roziqrizal.rizqflow.workspace.AccountWorkspace
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

/** Rentang risiko yang bisa dipilih di layar: 0,25% sampai 10% per trade, kelipatan 0,25%. Domain menerima sampai 50%. */
private const val RISK_MIN_BP = 25
private const val RISK_MAX_BP = 1_000
private const val RISK_STEP_BP = 25
private const val RISK_DEFAULT_BP = 100

private enum class RoleForm { TRADER, INVESTOR }

/**
 * Sistem per peran (Pro): Trader (batas risiko per trade) dan Investor (jadwal DCA) untuk satu ruang.
 * Jalan masuk: kartu Peran di Detail ruang (S11). Peran tidak mengubah cara uang dicatat; semua tetap
 * pengeluaran biasa di ruang itu, jadi layar ini hanya menyimpan pengaturan dan menampilkan status.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoleFlow(
    workspace: AccountWorkspace,
    roomId: RoomId,
    notifier: Notifier,
    onClose: () -> Unit,
    onOpenPaywall: () -> Unit,
    /** Ada transaksi baru (DCA dicatat): daftar dan Detail ruang perlu memuat ulang. */
    onDataChanged: () -> Unit,
) {
    val today = remember { LocalDate.now() }
    var overview by remember { mutableStateOf<RoleOverview?>(null) }
    var version by remember { mutableIntStateOf(0) }
    var form by rememberSaveable { mutableStateOf<RoleForm?>(null) }
    var confirmingRemove by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val spacing = MaterialTheme.spacing

    LaunchedEffect(roomId, version) { overview = workspace.roles.overview(roomId, today) }
    BackHandler(onBack = onClose)

    val data = overview
    if (data == null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }

    fun changed() {
        version++
    }

    fun pick(target: RoleForm) {
        if (data.entitled) form = target else onOpenPaywall()
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        TextButton(onClick = onClose, modifier = Modifier.padding(start = spacing.s3, top = spacing.s2)) { Text(stringResource(R.string.onb_back)) }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.s5)
                .padding(bottom = spacing.s5),
        ) {
            Text(stringResource(R.string.role_title), style = MaterialTheme.typography.headlineMedium)
            Text(data.room.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (data.kind != null && !data.entitled) {
                LockedCard(onOpenPaywall)
            }

            when (data.kind) {
                null -> {
                    Text(
                        stringResource(R.string.role_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = spacing.s3),
                    )
                    PickCard(R.string.role_pick_trader_title, R.string.role_pick_trader_body, RizqflowIcons.PanahAtas, showPro = !data.entitled) { pick(RoleForm.TRADER) }
                    PickCard(R.string.role_pick_investor_title, R.string.role_pick_investor_body, RizqflowIcons.Tunas, showPro = !data.entitled) { pick(RoleForm.INVESTOR) }
                }

                RoleKind.TRADER -> data.trader?.let { trader ->
                    TraderCard(trader, onEdit = { form = RoleForm.TRADER })
                }

                RoleKind.INVESTOR -> data.dca?.let { dca ->
                    DcaCard(
                        view = dca,
                        canRecord = data.entitled && !busy,
                        onEdit = { form = RoleForm.INVESTOR },
                        onRecord = {
                            busy = true
                            scope.launch {
                                when (val result = workspace.roles.recordDca(roomId, LocalDate.now())) {
                                    is LedgerResult.Success -> {
                                        // Muat ulang dulu: notifier.show menunggu snackbar hilang.
                                        onDataChanged()
                                        changed()
                                        notifier.show(resources.getString(R.string.role_dca_recorded, formatRupiah(dca.plan.amount)))
                                    }

                                    is LedgerResult.Failure -> {
                                        busy = false
                                        notifier.show(resources.getString(roleErrorText(result.error)))
                                    }
                                }
                                busy = false
                            }
                        },
                    )
                }
            }

            if (data.kind != null) {
                TextButton(onClick = { confirmingRemove = true }, modifier = Modifier.fillMaxWidth().padding(top = spacing.s3)) {
                    Text(stringResource(R.string.role_remove), color = MaterialTheme.colorScheme.error)
                }
            }
            Text(
                stringResource(R.string.role_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.s4),
            )
        }
    }

    when (form) {
        RoleForm.TRADER -> ModalBottomSheet(onDismissRequest = { form = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            TraderForm(workspace, roomId, existing = data.trader, onCancel = { form = null }, onSaved = { form = null; changed() })
        }

        RoleForm.INVESTOR -> ModalBottomSheet(onDismissRequest = { form = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            DcaForm(workspace, roomId, existing = data.dca?.plan, onCancel = { form = null }, onSaved = { form = null; changed() })
        }

        null -> Unit
    }

    if (confirmingRemove) {
        AlertDialog(
            onDismissRequest = { confirmingRemove = false },
            title = { Text(stringResource(R.string.role_remove_title, data.room.name)) },
            text = { Text(stringResource(R.string.role_remove_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingRemove = false
                    scope.launch {
                        workspace.roles.remove(roomId)
                        changed()
                        notifier.show(resources.getString(R.string.role_removed))
                    }
                }) { Text(stringResource(R.string.role_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingRemove = false }) { Text(stringResource(R.string.catat_date_cancel)) } },
        )
    }
}

internal fun roleErrorText(error: LedgerError): Int = when (error) {
    LedgerError.FEATURE_LOCKED -> R.string.role_locked_note
    LedgerError.ROLE_CONFLICT -> R.string.role_err_conflict
    LedgerError.INVALID_ROLE_SETTINGS -> R.string.role_err_invalid
    else -> errorText(error)
}

// ---------------------------------------------------------------------------------- kartu

@Composable
private fun LockedCard(onOpenPaywall: () -> Unit) {
    val spacing = MaterialTheme.spacing
    Column(
        modifier = Modifier
            .padding(top = spacing.s4)
            .fillMaxWidth()
            .background(MaterialTheme.rizqflow.statusWarning.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
            .padding(spacing.s3),
    ) {
        Text(stringResource(R.string.role_locked_note), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onOpenPaywall) { Text(stringResource(R.string.role_open_pro)) }
    }
}

@Composable
private fun PickCard(title: Int, body: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, showPro: Boolean, onClick: () -> Unit) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier = Modifier
            .padding(top = spacing.s4)
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(16.dp))
            .padding(spacing.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s3),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.s2)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                if (showPro) Text(stringResource(R.string.role_pro_badge), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = spacing.s1))
        }
        Icon(RizqflowIcons.PanahKanan, contentDescription = null)
    }
}

@Composable
private fun TraderCard(trader: TraderProfile, onEdit: () -> Unit) {
    val spacing = MaterialTheme.spacing
    Column(
        modifier = Modifier
            .padding(top = spacing.s4)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(16.dp))
            .padding(spacing.s4),
    ) {
        Text(stringResource(R.string.role_trader_limit_label), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(formatRupiah(trader.maxRiskPerTrade), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = spacing.s1))
        Text(
            stringResource(R.string.role_trader_limit_hint, formatPercent(trader.riskPerTrade.value), formatRupiah(trader.capital)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.role_trader_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.s3),
        )
        OutlinedButton(onClick = onEdit, modifier = Modifier.padding(top = spacing.s3).fillMaxWidth().height(48.dp)) { Text(stringResource(R.string.role_edit)) }
    }
}

@Composable
private fun DcaCard(view: DcaView, canRecord: Boolean, onEdit: () -> Unit, onRecord: () -> Unit) {
    val spacing = MaterialTheme.spacing
    val plan = view.plan
    Column(
        modifier = Modifier
            .padding(top = spacing.s4)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(16.dp))
            .padding(spacing.s4),
    ) {
        when (val status = view.status) {
            is DcaStatus.Done -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.s2)) {
                    Icon(RizqflowIcons.Centang, contentDescription = null, tint = MaterialTheme.rizqflow.statusGood)
                    Text(stringResource(R.string.role_dca_done_title), style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    stringResource(R.string.role_dca_done_body, formatRupiah(status.invested), view.categoryName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is DcaStatus.Due -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.s2)) {
                    Icon(RizqflowIcons.Jam, contentDescription = null, tint = MaterialTheme.rizqflow.statusWarning)
                    Text(stringResource(R.string.role_dca_due_title, status.dueOn.dayOfMonth), style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    stringResource(R.string.role_dca_due_body, formatRupiah(plan.amount), view.accountName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onRecord, enabled = canRecord, modifier = Modifier.padding(top = spacing.s3).fillMaxWidth().height(48.dp)) {
                    Text(stringResource(R.string.role_dca_record))
                }
            }

            is DcaStatus.Upcoming -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.s2)) {
                    Icon(RizqflowIcons.Jam, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.role_dca_upcoming_title, status.dueOn.dayOfMonth), style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    stringResource(R.string.role_dca_upcoming_body, formatRupiah(plan.amount), view.accountName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRecord, enabled = canRecord, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.role_dca_record)) }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = spacing.s3))
        DetailRow(R.string.role_dca_amount, formatRupiah(plan.amount))
        DetailRow(R.string.role_dca_day, plan.dayOfMonth.toString())
        DetailRow(R.string.role_dca_account, view.accountName)
        DetailRow(R.string.role_dca_category, view.categoryName)
        Text(
            stringResource(R.string.role_dca_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.s3),
        )
        OutlinedButton(onClick = onEdit, modifier = Modifier.padding(top = spacing.s3).fillMaxWidth().height(48.dp)) { Text(stringResource(R.string.role_edit)) }
    }
}

@Composable
private fun DetailRow(label: Int, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.s1), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

// ---------------------------------------------------------------------------------- formulir

/** Penggeser dengan tombol kurang dan tambah 48dp di kedua sisi, seperti Aturan alokasi. */
@Composable
private fun StepSlider(
    value: Int,
    range: IntRange,
    step: Int,
    lessLabel: String,
    moreLabel: String,
    description: String,
    onChange: (Int) -> Unit,
) {
    val spacing = MaterialTheme.spacing
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = { onChange((value - step).coerceAtLeast(range.first)) },
            enabled = value > range.first,
            modifier = Modifier.size(48.dp).semantics { contentDescription = lessLabel },
            contentPadding = PaddingValues(0.dp),
        ) { Text("−") }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(((it / step).roundToInt() * step).coerceIn(range.first, range.last)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = ((range.last - range.first) / step - 1).coerceAtLeast(0),
            colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest),
            modifier = Modifier.weight(1f).padding(horizontal = spacing.s2).semantics { contentDescription = description },
        )
        OutlinedButton(
            onClick = { onChange((value + step).coerceAtMost(range.last)) },
            enabled = value < range.last,
            modifier = Modifier.size(48.dp).semantics { contentDescription = moreLabel },
            contentPadding = PaddingValues(0.dp),
        ) { Text("+") }
    }
}

@Composable
private fun TraderForm(workspace: AccountWorkspace, roomId: RoomId, existing: TraderProfile?, onCancel: () -> Unit, onSaved: () -> Unit) {
    val spacing = MaterialTheme.spacing
    var digits by rememberSaveable { mutableStateOf(existing?.capital?.minor?.toString().orEmpty()) }
    var riskBp by rememberSaveable { mutableIntStateOf((existing?.riskPerTrade?.value ?: RISK_DEFAULT_BP).coerceIn(RISK_MIN_BP, RISK_MAX_BP)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<LedgerError?>(null) }
    val scope = rememberCoroutineScope()

    val capital = AmountPad.toRupiah(digits)
    val preview = if (capital.isPositive) TraderProfile(roomId, capital, BasisPoints(riskBp)).maxRiskPerTrade else null

    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = spacing.s5).padding(bottom = spacing.s4)) {
        Text(stringResource(R.string.role_trader_form_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = spacing.s3))

        Text(stringResource(R.string.role_trader_capital), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = spacing.s3))
        Text(formatRupiah(capital), style = MaterialTheme.typography.displaySmall)
        Text(stringResource(R.string.role_trader_capital_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Row(modifier = Modifier.fillMaxWidth().padding(top = spacing.s4), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.role_trader_risk), style = MaterialTheme.typography.titleSmall)
            Text(formatPercent(riskBp), style = MaterialTheme.typography.titleMedium)
        }
        StepSlider(
            value = riskBp,
            range = RISK_MIN_BP..RISK_MAX_BP,
            step = RISK_STEP_BP,
            lessLabel = stringResource(R.string.role_risk_less),
            moreLabel = stringResource(R.string.role_risk_more),
            description = stringResource(R.string.role_trader_risk),
            onChange = { riskBp = it; error = null },
        )
        Text(
            preview?.let { stringResource(R.string.role_trader_preview, formatRupiah(it)) } ?: "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        AmountKeypad(enabled = !busy, onKey = { digits = AmountPad.apply(digits, it); error = null }, modifier = Modifier.padding(top = spacing.s3))

        error?.let { Text(stringResource(roleErrorText(it)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = spacing.s2)) }
        Button(
            onClick = {
                busy = true
                scope.launch {
                    val result = workspace.roles.setTrader(roomId, capital, BasisPoints(riskBp))
                    busy = false
                    when (result) {
                        is LedgerResult.Success -> onSaved()
                        is LedgerResult.Failure -> error = result.error
                    }
                }
            },
            enabled = !busy && capital.isPositive,
            modifier = Modifier.padding(top = spacing.s3).fillMaxWidth().height(52.dp),
        ) { Text(stringResource(R.string.role_save)) }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.catat_date_cancel)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DcaForm(workspace: AccountWorkspace, roomId: RoomId, existing: DcaPlan?, onCancel: () -> Unit, onSaved: () -> Unit) {
    val spacing = MaterialTheme.spacing
    var accounts by remember { mutableStateOf<List<com.roziqrizal.rizqflow.domain.ledger.Account>>(emptyList()) }
    var categories by remember { mutableStateOf<List<com.roziqrizal.rizqflow.domain.ledger.Category>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var digits by rememberSaveable { mutableStateOf(existing?.amount?.minor?.toString().orEmpty()) }
    var day by rememberSaveable { mutableIntStateOf(existing?.dayOfMonth ?: 1) }
    var accountId by rememberSaveable { mutableStateOf(existing?.accountId?.value) }
    var categoryId by rememberSaveable { mutableStateOf(existing?.categoryId?.value) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<LedgerError?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(roomId) {
        val repos = workspace.repositories
        accounts = repos.accounts.activeAccounts()
        // Kategori sistem (Tak terlacak) tidak boleh dipakai DCA; RoleService juga menolaknya.
        categories = repos.rooms.categories(roomId).filter { !it.isSystem }
        if (accountId == null) accountId = accounts.firstOrNull()?.id?.value
        if (categoryId == null) categoryId = (categories.firstOrNull { it.name == INVESTMENT_NAME } ?: categories.firstOrNull())?.id?.value
        loaded = true
    }

    val amount = AmountPad.toRupiah(digits)
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = spacing.s5).padding(bottom = spacing.s4)) {
        Text(stringResource(R.string.role_dca_form_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = spacing.s3))

        Text(stringResource(R.string.role_dca_form_amount), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = spacing.s3))
        Text(formatRupiah(amount), style = MaterialTheme.typography.displaySmall)

        Row(modifier = Modifier.fillMaxWidth().padding(top = spacing.s4), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.role_dca_form_day), style = MaterialTheme.typography.titleSmall)
            Text(day.toString(), style = MaterialTheme.typography.titleMedium)
        }
        StepSlider(
            value = day,
            range = 1..DcaPlan.MAX_DAY,
            step = 1,
            lessLabel = stringResource(R.string.role_dca_day_less),
            moreLabel = stringResource(R.string.role_dca_day_more),
            description = stringResource(R.string.role_dca_day),
            onChange = { day = it; error = null },
        )

        Text(stringResource(R.string.role_dca_account), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = spacing.s3, bottom = spacing.s1))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.s2)) {
            accounts.forEach { account ->
                FilterChip(selected = accountId == account.id.value, onClick = { accountId = account.id.value }, label = { Text(account.name) }, colors = rizqflowFilterChipColors())
            }
        }

        Text(stringResource(R.string.role_dca_category), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = spacing.s3, bottom = spacing.s1))
        if (loaded && categories.isEmpty()) {
            Text(stringResource(R.string.role_dca_no_category), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.s2)) {
            categories.forEach { category ->
                FilterChip(selected = categoryId == category.id.value, onClick = { categoryId = category.id.value }, label = { Text(category.name) }, colors = rizqflowFilterChipColors())
            }
        }

        AmountKeypad(enabled = !busy, onKey = { digits = AmountPad.apply(digits, it); error = null }, modifier = Modifier.padding(top = spacing.s3))

        error?.let { Text(stringResource(roleErrorText(it)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = spacing.s2)) }
        Button(
            onClick = {
                val account = accountId ?: return@Button
                val category = categoryId ?: return@Button
                busy = true
                scope.launch {
                    val result = workspace.roles.setDca(roomId, amount, day, AccountId(account), CategoryId(category))
                    busy = false
                    when (result) {
                        is LedgerResult.Success -> onSaved()
                        is LedgerResult.Failure -> error = result.error
                    }
                }
            },
            enabled = !busy && amount.isPositive && accountId != null && categoryId != null,
            modifier = Modifier.padding(top = spacing.s3).fillMaxWidth().height(52.dp),
        ) { Text(stringResource(R.string.role_save)) }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.catat_date_cancel)) }
    }
}

/** Nama kategori bawaan pola Tiga hak yang dipilih lebih dulu untuk DCA bila ada. */
private const val INVESTMENT_NAME = "Investasi"
