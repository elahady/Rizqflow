package com.roziqrizal.rizqflow.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.roziqrizal.rizqflow.BuildConfig
import com.roziqrizal.rizqflow.domain.allocation.AllocationRule
import com.roziqrizal.rizqflow.ui.denah.DenahScreen
import com.roziqrizal.rizqflow.ui.kelola.KelolaScreen
import com.roziqrizal.rizqflow.ui.kelola.manageErrorText
import com.roziqrizal.rizqflow.domain.ledger.ArchiveUndo
import com.roziqrizal.rizqflow.ui.role.RoleFlow
import com.roziqrizal.rizqflow.ui.ruang.AturanScreen
import com.roziqrizal.rizqflow.ui.ruang.RoomDetailScreen
import com.roziqrizal.rizqflow.ui.about.AboutScreen
import com.roziqrizal.rizqflow.ui.reconciliation.ReconciliationScreen
import com.roziqrizal.rizqflow.ui.backup.BackupScreen
import com.roziqrizal.rizqflow.ui.csv.CsvScreen
import com.roziqrizal.rizqflow.ui.recurring.RecurringScreen
import com.roziqrizal.rizqflow.ui.bill.BillsScreen
import com.roziqrizal.rizqflow.domain.bill.BillPayment
import com.roziqrizal.rizqflow.domain.debt.DebtPayment
import com.roziqrizal.rizqflow.ui.debt.DebtsScreen
import com.roziqrizal.rizqflow.ui.report.ReportScreen
import com.roziqrizal.rizqflow.ui.reminder.ReminderSettingsScreen
import com.roziqrizal.rizqflow.ui.security.SecuritySettingsScreen
import com.roziqrizal.rizqflow.ui.theme.ThemePreference
import com.roziqrizal.rizqflow.ui.theme.ThemeSettingsScreen
import com.roziqrizal.rizqflow.domain.auth.AppLockService
import com.roziqrizal.rizqflow.ui.zakat.ZakatFlow
import com.roziqrizal.rizqflow.domain.model.RoomId
import java.time.YearMonth
import com.roziqrizal.rizqflow.domain.entitlement.Feature
import com.roziqrizal.rizqflow.domain.entitlement.Plan
import com.roziqrizal.rizqflow.ui.paywall.PaywallSheet
import com.roziqrizal.rizqflow.ui.ruang.RuangScreen
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.ledger.TransactionSnapshot
import com.roziqrizal.rizqflow.domain.model.TransactionId
import com.roziqrizal.rizqflow.domain.model.TransactionKind
import com.roziqrizal.rizqflow.ui.catat.CatatFlow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import com.roziqrizal.rizqflow.ui.catat.QuickCatatSheet
import com.roziqrizal.rizqflow.domain.ledger.FavoriteUse
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import com.roziqrizal.rizqflow.ui.catat.SavedInfo
import com.roziqrizal.rizqflow.ui.transaksi.TransaksiScreen
import com.roziqrizal.rizqflow.workspace.AccountWorkspace
import kotlinx.coroutines.launch
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.roziqrizal.rizqflow.notifications.ReminderScheduler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.time.LocalDate

/**
 * Menu utama beserta layar Catat di atasnya. Catat menutupi menu utama (bukan menggantinya)
 * sehingga tab yang sedang dibuka tidak hilang. Setelah menyimpan, snackbar menawarkan
 * **Urungkan**, yang menghapus transaksi itu (dan potret alokasinya).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainHost(
    workspace: AccountWorkspace,
    appLock: AppLockService,
    themePreference: ThemePreference,
    account: AccountUi,
    onConnectGmail: () -> Unit,
    onSignOut: () -> Unit,
    onDismissNotice: () -> Unit,
    /** Menutup ruang kerja ini, menimpa berkas databasenya dengan cadangan yang sudah didekripsi, dan membuka ulang (S20). */
    onRestoreBackup: (ByteArray) -> Unit = {},
    /** Bertambah setiap ada permintaan Catat kilat dari luar (pintasan ikon, tile). */
    quickCatatRequest: Int = 0,
    /** Mode demo (S22): memakai data contoh yang terpisah dari data pengguna. */
    demo: Boolean = false,
    onEnterDemo: () -> Unit = {},
    onExitDemo: () -> Unit = {},
) {
    val context = LocalContext.current
    var catat by rememberSaveable { mutableStateOf(false) }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var aturan by rememberSaveable { mutableStateOf(false) }
    var kelola by rememberSaveable { mutableStateOf(false) }
    var quick by rememberSaveable { mutableStateOf(false) }
    var demoSheet by rememberSaveable { mutableStateOf(false) }
    var roomDetail by rememberSaveable { mutableStateOf<String?>(null) }
    var roomMonth by rememberSaveable { mutableStateOf("") }
    var zakatRoom by rememberSaveable { mutableStateOf<String?>(null) }
    var roleRoom by rememberSaveable { mutableStateOf<String?>(null) }
    var aboutOpen by rememberSaveable { mutableStateOf(false) }
    var reconcileAccount by rememberSaveable { mutableStateOf<String?>(null) }
    var reconcileOpen by rememberSaveable { mutableStateOf(false) }
    var reminderOpen by rememberSaveable { mutableStateOf(false) }
    var securityOpen by rememberSaveable { mutableStateOf(false) }
    var csvOpen by rememberSaveable { mutableStateOf(false) }
    var recurringOpen by rememberSaveable { mutableStateOf(false) }
    var billsOpen by rememberSaveable { mutableStateOf(false) }
    var debtsOpen by rememberSaveable { mutableStateOf(false) }
    var reportOpen by rememberSaveable { mutableStateOf(false) }
    var backupOpen by rememberSaveable { mutableStateOf(false) }
    var tampilanOpen by rememberSaveable { mutableStateOf(false) }
    var paywallOpen by rememberSaveable { mutableStateOf(false) }
    // Nama Feature yang memicu (S21 mendahulukan manfaatnya); null = manfaat umum dari menu Lainnya.
    var paywallTrigger by rememberSaveable { mutableStateOf<String?>(null) }
    fun openPaywall(trigger: Feature? = null) {
        paywallTrigger = trigger?.name
        paywallOpen = true
    }
    val ownedPlans by workspace.purchases.plans.collectAsState()
    var handledQuick by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(quickCatatRequest) {
        if (quickCatatRequest > handledQuick) {
            handledQuick = quickCatatRequest
            quick = true
        }
    }
    // Berubah setiap ada transaksi yang disimpan, diubah, dihapus, atau diurungkan: daftar memuat ulang.
    var version by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val undoLabel = context.getString(R.string.action_undo)

    // Pengingat malam (S26): jadwalkan ulang setiap ruang kerja dibuka (juga menutupi kasus
    // reboot bila WorkManager sendiri belum sempat menjadwalkan lagi), tapi tidak untuk mode demo.
    LaunchedEffect(workspace, demo) {
        if (!demo) {
            val settings = workspace.reminder.settings()
            if (settings.enabled) ReminderScheduler.scheduleNext(context, settings)
        }
    }

    // Izin notifikasi Android 13+ diminta sekali, setelah transaksi pertama disimpan (bukan di
    // awal onboarding); version bertambah tepat setelah tiap transaksi tersimpan.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(workspace, version, demo) {
        if (!demo &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !workspace.reminder.hasRequestedNotificationPermission() &&
            workspace.repositories.transactions.latest(null) != null
        ) {
            workspace.reminder.markNotificationPermissionRequested()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Snackbar untuk layar mana pun; hasilnya true bila tombol aksinya ditekan.
    val notifier = remember(snackbar) {
        object : Notifier {
            override suspend fun show(message: String, actionLabel: String?): Boolean {
                snackbar.currentSnackbarData?.dismiss()
                return snackbar.showSnackbar(message, actionLabel = actionLabel, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed
            }
        }
    }

    // Transaksi berulang (Tahap 10): catat semua kemunculan yang sudah tiba, termasuk yang terlewat selama
    // aplikasi tidak dibuka. Berjalan saat ruang kerja dibuka dan tiap aplikasi kembali ke depan (harinya
    // bisa sudah berganti), tidak di mode demo. Aman diulang: tiap kemunculan punya pengenal tetap.
    // Yang tertahan (akun, ruang, atau kategorinya diarsipkan) diberitahukan sekali per pembukaan.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(workspace, demo) {
        if (demo) return@LaunchedEffect
        var blockedNoticed = false
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val run = workspace.recurring.runDue(LocalDate.now())
            if (run.recorded > 0) {
                version++
                scope.launch { notifier.show(context.resources.getQuantityString(R.plurals.recurring_ran, run.recorded, run.recorded)) }
            } else if (run.blocked.isNotEmpty() && !blockedNoticed) {
                scope.launch { notifier.show(context.resources.getQuantityString(R.plurals.recurring_blocked_notice, run.blocked.size, run.blocked.size)) }
            }
            if (run.blocked.isNotEmpty()) blockedNoticed = true
        }
    }

    // Berjalan di scope MainHost supaya tetap hidup setelah layar Aturan ditutup.
    fun announceRules(previous: List<AllocationRule>) {
        version++
        scope.launch {
            if (notifier.show(context.getString(R.string.rules_saved), undoLabel)) {
                workspace.rules.restoreRules(previous)
                version++
            }
        }
    }

    // Berjalan di scope MainHost: layar Detail ruang sudah tertutup saat ruangnya diarsipkan.
    fun announceRoomArchived(undo: ArchiveUndo) {
        version++
        scope.launch {
            if (notifier.show(context.getString(R.string.room_archived, undo.room.name), undoLabel)) {
                workspace.rules.undoArchive(undo)
                version++
            }
        }
    }

    fun announceReconciled(transactionId: TransactionId?, diff: com.roziqrizal.rizqflow.domain.money.Money) {
        version++
        if (transactionId == null) {
            scope.launch { notifier.show(context.getString(R.string.reconcile_saved_match)) }
            return
        }
        val message = if (diff.isNegative) {
            context.getString(R.string.reconcile_saved_expense, formatRupiah(diff.abs()))
        } else {
            context.getString(R.string.reconcile_saved_income, formatRupiah(diff))
        }
        scope.launch {
            if (notifier.show(message, undoLabel)) {
                workspace.ledger.delete(transactionId)
                version++
            }
        }
    }

    // Berjalan di scope MainHost supaya Urungkan tetap hidup setelah layar Tagihan ditutup.
    fun announceBillPaid(payment: BillPayment) {
        version++
        val name = payment.updated.name
        val message = if (payment.updated.isFinished) {
            context.getString(R.string.bills_paid_done, name)
        } else {
            val next = formatDateWith(
                payment.updated.nextDue,
                LocalDate.now(),
                context.getString(R.string.date_today),
                context.getString(R.string.date_yesterday),
                context.resources.getStringArray(R.array.month_abbrev).toList(),
            )
            context.getString(R.string.bills_paid_next, name, next)
        }
        scope.launch {
            if (notifier.show(message, undoLabel)) {
                workspace.bills.undoPay(payment)
                version++
            }
        }
    }

    // Berjalan di scope MainHost supaya Urungkan tetap hidup setelah layar Utang-piutang ditutup.
    fun announceRepaid(message: String, payment: DebtPayment) {
        version++
        scope.launch {
            if (notifier.show(message, undoLabel)) {
                workspace.debts.undoRepay(payment)
                version++
            }
        }
    }

    fun announceFavorite(use: FavoriteUse) {
        version++
        scope.launch {
            if (notifier.show(context.getString(R.string.quick_used, use.previous.name, formatRupiah(use.transaction.amount)), undoLabel)) {
                workspace.favorites.undoUse(use)
                version++
            }
        }
    }

    fun announceEdit(previous: TransactionSnapshot) {
        version++
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(context.getString(R.string.saved_edit), actionLabel = undoLabel, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) {
                workspace.ledger.restore(previous)
                version++
            }
        }
    }

    fun announceDelete(snapshot: TransactionSnapshot) {
        version++
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(context.getString(R.string.deleted), actionLabel = undoLabel, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) {
                workspace.ledger.restore(snapshot)
                version++
            }
        }
    }

    fun announce(saved: SavedInfo) {
        saved.previous?.let { return announceEdit(it) }
        version++
        val amount = formatRupiah(saved.amount)
        val message = when (saved.kind) {
            TransactionKind.INCOME ->
                if (saved.roomCount > 0) context.getString(R.string.saved_income, amount, saved.roomCount) else context.getString(R.string.saved_income_none, amount)

            TransactionKind.EXPENSE -> {
                val extra = when {
                    saved.favoriteSaved -> " " + context.getString(R.string.saved_favorite)
                    saved.favoriteError != null -> " " + context.getString(R.string.saved_favorite_failed, manageErrorText(context, saved.favoriteError))
                    else -> ""
                }
                context.getString(R.string.saved_expense, amount) + extra
            }
            TransactionKind.TRANSFER -> context.getString(R.string.saved_transfer, amount)
            // Transaksi pinjaman dicatat dari layar Utang-piutang, yang mengumumkannya sendiri.
            TransactionKind.LOAN_OUT, TransactionKind.LOAN_IN -> context.getString(R.string.saved_transfer, amount)
        }
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(message, actionLabel = undoLabel, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) {
                workspace.ledger.delete(saved.id)
                version++
            }
        }
    }

    RizqflowApp(
        account = account,
        onCatat = { catat = true },
        onConnectGmail = onConnectGmail,
        onSignOut = onSignOut,
        onDismissNotice = onDismissNotice,
        snackbarHostState = snackbar,
        transaksiContent = { TransaksiScreen(workspace, refreshKey = version, notifier = notifier, onOpen = { editId = it.value }, onQuickCatat = { quick = true }, onOpenLoan = { debtsOpen = true }) },
        denahContent = {
            DenahScreen(
                workspace, refreshKey = version, onCatat = { catat = true }, onOpenRules = { aturan = true }, onChanged = { version++ },
                demo = demo, onDemoClick = { demoSheet = true }, onTryDemo = onEnterDemo,
                onOpenRoom = { id, month -> roomMonth = month.toString(); roomDetail = id.value },
                onOpenReconciliation = { accountId -> reconcileAccount = accountId.value; reconcileOpen = true },
            )
        },
        ruangContent = {
            RuangScreen(
                workspace, refreshKey = version, notifier = notifier, onOpenRules = { aturan = true }, onChanged = { version++ },
                onOpenRoom = { id -> roomMonth = YearMonth.now().toString(); roomDetail = id.value },
                onOpenPaywall = { openPaywall(Feature.UNLIMITED_ROOMS) },
            )
        },
        onOpenRules = { aturan = true },
        onOpenManage = { kelola = true },
        demo = demo,
        onToggleDemo = { if (demo) demoSheet = true else onEnterDemo() },
        onOpenAbout = { aboutOpen = true },
        onOpenReconciliation = {
            reconcileAccount = null
            reconcileOpen = true
        },
        onOpenReminder = { reminderOpen = true },
        onOpenSecurity = { securityOpen = true },
        onOpenCsv = { csvOpen = true },
        onOpenBackup = { backupOpen = true },
        onOpenTampilan = { tampilanOpen = true },
        onOpenRecurring = { recurringOpen = true },
        onOpenBills = { billsOpen = true },
        onOpenDebts = { debtsOpen = true },
        onOpenReport = { reportOpen = true },
        onOpenPaywall = { openPaywall() },
        proOwned = Plan.PRO in ownedPlans,
        onToggleProDebug = if (BuildConfig.DEBUG) {
            { if (Plan.PRO in ownedPlans) workspace.purchases.revoke(Plan.PRO) else workspace.purchases.grant(Plan.PRO) }
        } else {
            null
        },
    )
    if (tampilanOpen) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ThemeSettingsScreen(preference = themePreference, onClose = { tampilanOpen = false })
        }
    }
    if (securityOpen) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            SecuritySettingsScreen(service = appLock, onClose = { securityOpen = false })
        }
    }
    if (recurringOpen) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            RecurringScreen(workspace = workspace, onClose = { recurringOpen = false }, onChanged = { version++ })
        }
    }
    if (reportOpen) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ReportScreen(workspace = workspace, refreshKey = version, onClose = { reportOpen = false })
        }
    }
    if (debtsOpen) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box {
                DebtsScreen(
                    workspace = workspace,
                    refreshKey = version,
                    onClose = { debtsOpen = false },
                    onChanged = { version++ },
                    onRepaid = ::announceRepaid,
                )
                // Layar ini menutupi Scaffold beserta snackbar-nya, jadi ia membawa SnackbarHost sendiri.
                SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
            }
        }
    }
    if (billsOpen) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box {
                BillsScreen(
                    workspace = workspace,
                    refreshKey = version,
                    onClose = { billsOpen = false },
                    onChanged = { version++ },
                    onPaid = ::announceBillPaid,
                )
                // Layar ini menutupi Scaffold beserta snackbar-nya, jadi ia membawa SnackbarHost sendiri.
                SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
            }
        }
    }
    if (csvOpen) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box {
                CsvScreen(workspace = workspace, notifier = notifier, onClose = { csvOpen = false })
                // Layar ini menutupi Scaffold beserta snackbar-nya, jadi ia membawa SnackbarHost sendiri.
                SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
            }
        }
    }
    if (backupOpen) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box {
                BackupScreen(
                    workspace = workspace,
                    notifier = notifier,
                    onClose = { backupOpen = false },
                    onRestored = {
                        backupOpen = false
                        onRestoreBackup(it)
                    },
                )
                SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
            }
        }
    }
    if (paywallOpen) {
        ModalBottomSheet(onDismissRequest = { paywallOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            PaywallSheet(
                trigger = paywallTrigger?.let { Feature.valueOf(it) },
                onBuy = {
                    paywallOpen = false
                    scope.launch { notifier.show(context.getString(R.string.pro_purchase_unavailable)) }
                },
                onRestore = {
                    paywallOpen = false
                    scope.launch { notifier.show(context.getString(R.string.pro_purchase_unavailable)) }
                },
                onDismiss = { paywallOpen = false },
            )
        }
    }
    if (aboutOpen) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            AboutScreen(notifier = notifier, onClose = { aboutOpen = false })
        }
    }
    if (reminderOpen) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ReminderSettingsScreen(workspace = workspace, onClose = { reminderOpen = false })
        }
    }
    if (reconcileOpen) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ReconciliationScreen(
                workspace = workspace,
                initialAccountId = reconcileAccount?.let { com.roziqrizal.rizqflow.domain.model.AccountId(it) },
                notifier = notifier,
                onClose = { reconcileOpen = false },
                onCorrected = { txId, diff ->
                    reconcileOpen = false
                    announceReconciled(txId, diff)
                },
            )
        }
    }
    if (demoSheet && demo) {
        ModalBottomSheet(onDismissRequest = { demoSheet = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Text(stringResource(R.string.demo_sheet_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.demo_sheet_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Button(
                    onClick = {
                        demoSheet = false
                        onExitDemo()
                    },
                    modifier = Modifier.padding(top = 16.dp).fillMaxWidth().height(52.dp),
                ) { Text(stringResource(R.string.demo_exit)) }
                TextButton(onClick = { demoSheet = false }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.demo_stay)) }
            }
        }
    }
    if (quick) {
        ModalBottomSheet(onDismissRequest = { quick = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            QuickCatatSheet(workspace = workspace, onClose = { quick = false }, onSaved = ::announce, onFavoriteUsed = ::announceFavorite)
        }
    }
    val editing = editId
    if (editing != null) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            CatatFlow(
                workspace = workspace,
                editing = TransactionId(editing),
                onClose = { editId = null },
                onSaved = ::announce,
                onDeleted = ::announceDelete,
            )
        }
    } else if (catat) {
        // Surface menangkap sentuhan supaya tidak tembus ke menu utama di bawahnya.
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            CatatFlow(workspace, onClose = { catat = false }, onSaved = ::announce)
        }
    } else if (kelola) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box {
                KelolaScreen(workspace = workspace, notifier = notifier, onClose = { kelola = false }, onChanged = { version++ })
                SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
            }
        }
    } else if (aturan) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box {
                AturanScreen(
                    workspace = workspace,
                    notifier = notifier,
                    onClose = { aturan = false },
                    onSaved = ::announceRules,
                    onTemplateApplied = { version++ },
                    onOpenPaywall = { openPaywall(Feature.ADVANCED_ALLOCATION_RULES) },
                )
                // Layar ini menutupi Scaffold beserta snackbar-nya, jadi ia membawa SnackbarHost sendiri.
                SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp))
            }
        }
    } else if (roleRoom != null) {
        // Sama seperti Zakat: dibuka dari Detail ruang tanpa menutupnya, jadi Kembali balik ke sana.
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box {
                RoleFlow(
                    workspace = workspace,
                    roomId = RoomId(roleRoom.orEmpty()),
                    notifier = notifier,
                    onClose = { roleRoom = null },
                    onOpenPaywall = { openPaywall(Feature.ROLE_SYSTEMS) },
                    onDataChanged = { version++ },
                )
                SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
            }
        }
    } else if (zakatRoom != null) {
        // Diperiksa sebelum roomDetail: dibuka DARI Detail ruang tanpa menutupnya (roomDetail tetap
        // terisi), supaya Kembali dari Zakat balik ke Detail ruang, bukan langsung ke Denah atau Ruang.
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box {
                ZakatFlow(
                    workspace = workspace,
                    roomId = RoomId(zakatRoom.orEmpty()),
                    notifier = notifier,
                    onClose = { zakatRoom = null },
                    onOpenTransaction = { editId = it.value },
                    onOpenPaywall = { openPaywall(Feature.MULTI_ZAKAT_PROFILE) },
                )
                SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
            }
        }
    } else if (roomDetail != null) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box {
                RoomDetailScreen(
                    workspace = workspace,
                    roomId = RoomId(roomDetail.orEmpty()),
                    month = YearMonth.parse(roomMonth),
                    refreshKey = version,
                    notifier = notifier,
                    onClose = { roomDetail = null },
                    onOpenTransaction = { editId = it.value },
                    onOpenRules = { aturan = true },
                    onArchived = ::announceRoomArchived,
                    onOpenZakat = { zakatRoom = it.value },
                    onOpenRole = { roleRoom = it.value },
                )
                SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
            }
        }
    }
}
