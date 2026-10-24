package com.roziqrizal.rizqflow.ui.zakat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.calendar.UmmAlQuraCalendar
import com.roziqrizal.rizqflow.domain.ledger.Account
import com.roziqrizal.rizqflow.domain.ledger.LedgerError
import com.roziqrizal.rizqflow.domain.ledger.LedgerResult
import com.roziqrizal.rizqflow.domain.model.AccountId
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.model.TransactionId
import com.roziqrizal.rizqflow.domain.money.Money
import com.roziqrizal.rizqflow.domain.zakat.GivingOverview
import com.roziqrizal.rizqflow.domain.zakat.GivingStatus
import com.roziqrizal.rizqflow.domain.zakat.HaulStatus
import com.roziqrizal.rizqflow.domain.zakat.PercentageGivingStrategy
import com.roziqrizal.rizqflow.domain.zakat.ZakatHaulHijriStrategy
import com.roziqrizal.rizqflow.domain.zakat.ZakatProfile
import com.roziqrizal.rizqflow.ui.Notifier
import com.roziqrizal.rizqflow.ui.RizqflowIcons
import com.roziqrizal.rizqflow.ui.formatDate
import com.roziqrizal.rizqflow.ui.formatRupiah
import com.roziqrizal.rizqflow.ui.onboarding.AmountKeypad
import com.roziqrizal.rizqflow.ui.theme.rizqflow
import com.roziqrizal.rizqflow.ui.theme.rizqflowFilterChipColors
import com.roziqrizal.rizqflow.ui.theme.spacing
import com.roziqrizal.rizqflow.workspace.AccountWorkspace
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Modul Memberi (S14, S15, S17): status ruang lewat mode aktifnya (zakat mal atau persentase
 * donasi), profil harta, dan tunaikan zakat. Jalan masuk: kartu Zakat di Detail ruang (S11).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZakatFlow(
    workspace: AccountWorkspace,
    roomId: RoomId,
    notifier: Notifier,
    onClose: () -> Unit,
    onOpenTransaction: (TransactionId) -> Unit,
    /** Profil harta tambahan butuh Pro (multi-profil). */
    onOpenPaywall: () -> Unit = {},
) {
    val today = remember { LocalDate.now() }
    var overview by remember { mutableStateOf<GivingOverview?>(null) }
    var version by remember { mutableIntStateOf(0) }
    var wealthOpen by rememberSaveable { mutableStateOf(false) }
    var payOpen by rememberSaveable { mutableStateOf(false) }
    // Profil harta yang sedang dilihat; kosong berarti profil pertama.
    var profileId by rememberSaveable { mutableStateOf<String?>(null) }
    // "add" atau "rename" saat dialog nama profil terbuka.
    var profileDialog by rememberSaveable { mutableStateOf<String?>(null) }
    var profileName by rememberSaveable { mutableStateOf("") }
    var profileError by remember { mutableStateOf<LedgerError?>(null) }
    var archiving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val spacing = MaterialTheme.spacing
    val hijriMonths = stringArrayResource(R.array.hijri_months).toList()

    LaunchedEffect(roomId, version, profileId) { overview = workspace.zakat.overview(roomId, today, profileId) }
    BackHandler(onBack = onClose)

    val data = overview
    if (data == null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }

    fun changed() {
        version++
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
            Text(stringResource(R.string.zakat_title), style = MaterialTheme.typography.headlineMedium)

            if (data.status !is GivingStatus.Percentage && data.profiles.isNotEmpty()) {
                ProfileBar(
                    overview = data,
                    onSelect = { profileId = it },
                    onAdd = {
                        if (data.canAddProfile) {
                            profileName = ""
                            profileError = null
                            profileDialog = "add"
                        } else {
                            onOpenPaywall()
                        }
                    },
                    onRename = {
                        profileName = data.profile?.name.orEmpty()
                        profileError = null
                        profileDialog = "rename"
                    },
                    onArchive = { archiving = true },
                )
            }

            when (val status = data.status) {
                is GivingStatus.NeedsInput -> SetupCard(first = data.neverSetUp, onFill = { wealthOpen = true })

                is GivingStatus.Percentage -> PercentageCard(status.target)

                is GivingStatus.Zakat -> ZakatCard(
                    status = status,
                    goldPrice = data.goldPrice,
                    onUpdateWealth = { wealthOpen = true },
                    onPay = { payOpen = true },
                )
            }

            if (data.status is GivingStatus.Zakat) {
                Text(stringResource(R.string.zakat_history_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = spacing.s5, bottom = spacing.s2))
                if (data.payments.isEmpty()) {
                    Text(stringResource(R.string.zakat_history_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    data.payments.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = spacing.s2),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text(formatDate(row.payment.day, today), style = MaterialTheme.typography.bodyLarge)
                                Text(UmmAlQuraCalendar().toHijri(row.payment.day).format(hijriMonths), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(formatRupiah(row.amount), style = MaterialTheme.typography.titleSmall)
                        }
                        HorizontalDivider()
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(top = spacing.s4, bottom = spacing.s3))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.zakat_donation_toggle), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.zakat_donation_toggle_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = data.status is GivingStatus.Percentage,
                    onCheckedChange = { donation ->
                        scope.launch {
                            val mode = if (donation) PercentageGivingStrategy.ID else ZakatHaulHijriStrategy.ID
                            workspace.zakat.setGivingMode(roomId, mode)
                            changed()
                        }
                    },
                )
            }
            Text(
                stringResource(R.string.zakat_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.s4),
            )
        }
    }

    if (wealthOpen) {
        ModalBottomSheet(onDismissRequest = { wealthOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            WealthScreen(workspace = workspace, overview = data, profileId = data.profile?.id, onSaved = { wealthOpen = false; changed() }, onCancel = { wealthOpen = false })
        }
    }
    val dialog = profileDialog
    if (dialog != null) {
        val adding = dialog == "add"
        AlertDialog(
            onDismissRequest = { profileDialog = null },
            title = { Text(stringResource(if (adding) R.string.zakat_profile_add_title else R.string.zakat_profile_rename_title)) },
            text = {
                OutlinedTextField(
                    value = profileName,
                    onValueChange = {
                        profileName = it.take(ZakatProfile.NAME_MAX)
                        profileError = null
                    },
                    label = { Text(stringResource(R.string.zakat_profile_name)) },
                    placeholder = { Text(stringResource(R.string.zakat_profile_name_hint)) },
                    singleLine = true,
                    isError = profileError != null,
                    supportingText = profileError?.let { error -> { Text(stringResource(profileErrorText(error))) } },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = profileName.isNotBlank(),
                    onClick = {
                        scope.launch {
                            if (adding) {
                                when (val result = workspace.zakat.addProfile(profileName)) {
                                    is LedgerResult.Success -> {
                                        profileId = result.value.id
                                        profileDialog = null
                                        changed()
                                    }

                                    is LedgerResult.Failure -> profileError = result.error
                                }
                            } else {
                                val current = data.profile ?: return@launch
                                when (val result = workspace.zakat.renameProfile(current.id, profileName)) {
                                    is LedgerResult.Success -> {
                                        profileDialog = null
                                        changed()
                                    }

                                    is LedgerResult.Failure -> profileError = result.error
                                }
                            }
                        }
                    },
                ) { Text(stringResource(R.string.zakat_profile_save)) }
            },
            dismissButton = { TextButton(onClick = { profileDialog = null }) { Text(stringResource(R.string.catat_date_cancel)) } },
        )
    }
    if (archiving) {
        val current = data.profile
        AlertDialog(
            onDismissRequest = { archiving = false },
            title = { Text(stringResource(R.string.zakat_profile_archive_title, current?.name.orEmpty())) },
            text = { Text(stringResource(R.string.zakat_profile_archive_body)) },
            confirmButton = {
                TextButton(onClick = {
                    archiving = false
                    val target = current ?: return@TextButton
                    scope.launch {
                        when (val result = workspace.zakat.archiveProfile(target.id)) {
                            is LedgerResult.Success -> {
                                profileId = null
                                changed()
                            }

                            is LedgerResult.Failure -> notifier.show(context.getString(profileErrorText(result.error)))
                        }
                    }
                }) { Text(stringResource(R.string.zakat_profile_archive), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { archiving = false }) { Text(stringResource(R.string.catat_date_cancel)) } },
        )
    }
    val zakatStatus = data.status
    if (payOpen && zakatStatus is GivingStatus.Zakat) {
        ModalBottomSheet(onDismissRequest = { payOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            PayZakatSheet(
                workspace = workspace,
                roomId = roomId,
                status = zakatStatus,
                profileId = data.profile?.id,
                // Dengan beberapa profil, nama profil ikut di catatan supaya transaksinya bisa dibedakan.
                note = data.profile?.name?.takeIf { data.profiles.size > 1 },
                onCancel = { payOpen = false },
                onPaid = {
                    payOpen = false
                    changed()
                    onOpenTransaction(it)
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------------- profil harta (Pro)

/** Pemilih profil harta: satu chip per profil, tombol tambah (Pro), dan ubah nama atau arsipkan profil yang dipilih. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileBar(overview: GivingOverview, onSelect: (String) -> Unit, onAdd: () -> Unit, onRename: () -> Unit, onArchive: () -> Unit) {
    val spacing = MaterialTheme.spacing
    Column(modifier = Modifier.padding(top = spacing.s3)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.s2)) {
            overview.profiles.forEach { profile ->
                FilterChip(selected = profile.id == overview.profile?.id, onClick = { onSelect(profile.id) }, label = { Text(profile.name) }, colors = rizqflowFilterChipColors())
            }
            AssistChip(onClick = onAdd, label = { Text(stringResource(R.string.zakat_profile_add)) })
        }
        Row {
            TextButton(onClick = onRename) { Text(stringResource(R.string.zakat_profile_rename)) }
            if (overview.profiles.size > 1) {
                TextButton(onClick = onArchive) { Text(stringResource(R.string.zakat_profile_archive), color = MaterialTheme.colorScheme.error) }
            }
        }
        // Tanpa Pro hanya profil pertama yang pengingat haulnya aktif (monetisasi.md); profil tambahan
        // yang tersisa setelah turun paket diberi tahu supaya pengingat yang diam tidak mengejutkan.
        if (!overview.canAddProfile && overview.profile?.id != overview.profiles.firstOrNull()?.id) {
            Text(
                stringResource(R.string.zakat_profile_reminder_first_only),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun profileErrorText(error: LedgerError): Int = when (error) {
    LedgerError.NAME_TAKEN -> R.string.zakat_profile_err_taken
    LedgerError.INVALID_NAME -> R.string.zakat_profile_err_name
    LedgerError.FEATURE_LOCKED -> R.string.role_locked_note
    LedgerError.LAST_PROFILE -> R.string.zakat_profile_err_last
    else -> R.string.catat_error_generic
}

// ---------------------------------------------------------------------------------- kartu status

@Composable
private fun SetupCard(first: Boolean, onFill: () -> Unit) {
    val spacing = MaterialTheme.spacing
    Column(
        modifier = Modifier
            .padding(top = spacing.s4)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(16.dp))
            .padding(spacing.s4),
    ) {
        Text(stringResource(if (first) R.string.zakat_setup_title else R.string.zakat_setup_incomplete), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.zakat_setup_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = spacing.s1))
        Button(onClick = onFill, modifier = Modifier.padding(top = spacing.s3).fillMaxWidth().height(48.dp)) { Text(stringResource(R.string.zakat_setup_action)) }
    }
}

@Composable
private fun PercentageCard(target: Money) {
    val spacing = MaterialTheme.spacing
    Column(
        modifier = Modifier
            .padding(top = spacing.s4)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(16.dp))
            .padding(spacing.s4),
    ) {
        Text(stringResource(R.string.zakat_percentage_target), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(formatRupiah(target), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = spacing.s1))
        Text(
            stringResource(R.string.zakat_percentage_hint, formatRupiah(target)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.s2),
        )
    }
}

@Composable
private fun ZakatCard(status: GivingStatus.Zakat, goldPrice: com.roziqrizal.rizqflow.domain.zakat.GoldPrice?, onUpdateWealth: () -> Unit, onPay: () -> Unit) {
    val spacing = MaterialTheme.spacing
    val today = remember { LocalDate.now() }
    val hijriMonths = stringArrayResource(R.array.hijri_months).toList()
    Column(
        modifier = Modifier
            .padding(top = spacing.s4)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(16.dp))
            .padding(spacing.s4),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        when (val haul = status.haul) {
            HaulStatus.BelowNisab -> {
                androidx.compose.material3.Icon(RizqflowIcons.Bulan, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.zakat_status_below), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = spacing.s1))
                Text(stringResource(R.string.zakat_status_below_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            is HaulStatus.Running -> {
                androidx.compose.material3.Icon(RizqflowIcons.Bulan, contentDescription = null, tint = MaterialTheme.rizqflow.statusGood)
                Text(stringResource(R.string.zakat_status_running), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = spacing.s1))
                Text(
                    stringResource(R.string.zakat_status_running_day, haul.elapsedDays.coerceAtLeast(0) + 1, haul.totalDays),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.zakat_status_due, UmmAlQuraCalendar().toHijri(haul.due).format(hijriMonths)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is HaulStatus.Completed -> {
                androidx.compose.material3.Icon(RizqflowIcons.Centang, contentDescription = null, tint = MaterialTheme.rizqflow.statusGood)
                Text(stringResource(R.string.zakat_status_completed), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = spacing.s1))
                Text(stringResource(R.string.zakat_status_completed_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onPay, modifier = Modifier.padding(top = spacing.s3).fillMaxWidth().height(48.dp)) { Text(stringResource(R.string.zakat_tunaikan)) }
            }
        }
    }

    Row(modifier = Modifier.fillMaxWidth().padding(top = spacing.s4), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.zakat_net_wealth), style = MaterialTheme.typography.bodyLarge)
        Text(formatRupiah(status.netWealth), style = MaterialTheme.typography.titleSmall)
    }
    Row(modifier = Modifier.fillMaxWidth().padding(top = spacing.s1), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.zakat_nisab_today), style = MaterialTheme.typography.bodyLarge)
        Text(formatRupiah(status.nisab), style = MaterialTheme.typography.titleSmall)
    }
    if (goldPrice != null) {
        Text(
            stringResource(R.string.zakat_price_manual, formatDate(goldPrice.day, today)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.s1),
        )
    }
    if (status.haul !is HaulStatus.Completed) {
        OutlinedButton(onClick = onUpdateWealth, modifier = Modifier.padding(top = spacing.s3).fillMaxWidth().height(48.dp)) {
            Text(stringResource(R.string.zakat_update_wealth))
        }
    } else {
        TextButton(onClick = onUpdateWealth, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.zakat_update_wealth)) }
    }
}

// ---------------------------------------------------------------------------------- S17 Tunaikan zakat

@Composable
private fun PayZakatSheet(
    workspace: AccountWorkspace,
    roomId: RoomId,
    status: GivingStatus.Zakat,
    profileId: String?,
    note: String?,
    onCancel: () -> Unit,
    onPaid: (TransactionId) -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val today = remember { LocalDate.now() }
    var accounts by remember { mutableStateOf<List<Account>>(emptyList()) }
    var accountId by rememberSaveable { mutableStateOf<String?>(null) }
    // Bawaan 2,5% dari harta bersih; bisa diubah lewat papan angka.
    var digits by rememberSaveable { mutableStateOf((status.netWealth.minor * 25 / 1000).toString()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<LedgerError?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        accounts = workspace.repositories.accounts.activeAccounts()
        if (accountId == null) accountId = accounts.firstOrNull()?.id?.value
    }

    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = spacing.s5).padding(bottom = spacing.s4)) {
        Text(stringResource(R.string.pay_zakat_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = spacing.s3))
        Text(
            stringResource(if (status.haul is HaulStatus.Completed) R.string.pay_zakat_due_title else R.string.pay_zakat_not_due),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(formatRupiah(com.roziqrizal.rizqflow.domain.ledger.AmountPad.toRupiah(digits)), style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(top = spacing.s3))
        Text(
            stringResource(R.string.pay_zakat_net_wealth, formatRupiah(status.netWealth)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(stringResource(R.string.pay_zakat_account), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = spacing.s3, bottom = spacing.s1))
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.s2)) {
            accounts.forEach { account ->
                FilterChip(selected = accountId == account.id.value, onClick = { accountId = account.id.value }, label = { Text(account.name) }, colors = rizqflowFilterChipColors())
            }
        }
        Text(
            stringResource(R.string.pay_zakat_category),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.s3),
        )
        Text(
            stringResource(R.string.pay_zakat_early_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.s2),
        )

        AmountKeypad(enabled = !busy, onKey = { digits = com.roziqrizal.rizqflow.domain.ledger.AmountPad.apply(digits, it) }, modifier = Modifier.padding(top = spacing.s3))

        Button(
            onClick = {
                val account = accountId ?: return@Button
                busy = true
                scope.launch {
                    val result = workspace.zakat.payZakat(roomId, AccountId(account), com.roziqrizal.rizqflow.domain.ledger.AmountPad.toRupiah(digits), today, note, profileId)
                    busy = false
                    when (result) {
                        is LedgerResult.Success -> onPaid(result.value)
                        is LedgerResult.Failure -> error = result.error
                    }
                }
            },
            enabled = !busy && accountId != null && com.roziqrizal.rizqflow.domain.ledger.AmountPad.toRupiah(digits).isPositive,
            modifier = Modifier.padding(top = spacing.s3).fillMaxWidth().height(52.dp),
        ) { Text(stringResource(R.string.pay_zakat_confirm)) }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.catat_date_cancel)) }
    }
}
