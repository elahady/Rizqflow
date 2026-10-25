package com.roziqrizal.rizqflow.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import com.roziqrizal.rizqflow.BuildConfig
import com.roziqrizal.rizqflow.RizqflowApplication
import com.roziqrizal.rizqflow.billing.BillingPeriod
import com.roziqrizal.rizqflow.billing.BuyOutcome
import com.roziqrizal.rizqflow.billing.PurchaseClaimCoordinator
import com.roziqrizal.rizqflow.billing.PurchaseController
import com.roziqrizal.rizqflow.billing.SubscriptionOffer
import com.roziqrizal.rizqflow.billing.findActivity
import com.roziqrizal.rizqflow.billing.opensPlan
import com.roziqrizal.rizqflow.billing.purchaseClaimsAllowed
import com.roziqrizal.rizqflow.widget.SafeToSpendWidget
import com.roziqrizal.rizqflow.domain.allocation.AllocationRule
import com.roziqrizal.rizqflow.ui.denah.DenahScreen
import com.roziqrizal.rizqflow.ui.kelola.KelolaScreen
import com.roziqrizal.rizqflow.ui.kelola.manageErrorText
import com.roziqrizal.rizqflow.domain.ledger.ArchiveUndo
import com.roziqrizal.rizqflow.ui.role.RoleFlow
import com.roziqrizal.rizqflow.ui.ruang.AturanScreen
import com.roziqrizal.rizqflow.ui.ruang.RoomDetailScreen
import com.roziqrizal.rizqflow.ui.ruangkeluarga.FamilyJoinResult
import com.roziqrizal.rizqflow.ui.ruangkeluarga.QrScanScreen
import com.roziqrizal.rizqflow.ui.ruangkeluarga.RuangKeluargaScreen
import com.roziqrizal.rizqflow.ui.ruangkeluarga.joinSharedRoom
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
import com.roziqrizal.rizqflow.ui.security.DeleteAccountConfirmContent
import com.roziqrizal.rizqflow.ui.security.SecuritySettingsScreen
import com.roziqrizal.rizqflow.ui.theme.ThemePreference
import com.roziqrizal.rizqflow.ui.theme.ThemeSettingsScreen
import com.roziqrizal.rizqflow.domain.auth.AppLockService
import com.roziqrizal.rizqflow.domain.auth.DeleteAccountConfirmation
import com.roziqrizal.rizqflow.ui.zakat.ZakatFlow
import com.roziqrizal.rizqflow.domain.model.RoomId
import java.time.YearMonth
import com.roziqrizal.rizqflow.domain.entitlement.Feature
import com.roziqrizal.rizqflow.domain.entitlement.LivePlanEntitlements
import com.roziqrizal.rizqflow.domain.entitlement.Plan
import com.roziqrizal.rizqflow.ui.paywall.PaywallSheet
import com.roziqrizal.rizqflow.ui.paywall.SyncPaywallSheet
import com.roziqrizal.rizqflow.ui.paywall.buyMessage
import com.roziqrizal.rizqflow.ui.paywall.paywallPlanFor
import com.roziqrizal.rizqflow.ui.paywall.restoreMessage
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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import com.roziqrizal.rizqflow.ui.catat.QuickCatatSheet
import com.roziqrizal.rizqflow.domain.ledger.FavoriteUse
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
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
import android.util.Log
import com.roziqrizal.rizqflow.auth.AuthBusy
import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.sync.DeleteAccountPlan
import com.roziqrizal.rizqflow.sync.RefreshOutcome
import com.roziqrizal.rizqflow.sync.planDeleteAccount
import com.roziqrizal.rizqflow.sync.forgetStatusDurably
import com.roziqrizal.rizqflow.sync.recoveryRow
import com.roziqrizal.rizqflow.sync.restoreAvailable
import com.roziqrizal.rizqflow.ui.recovery.RecoveryRestoreScreen
import com.roziqrizal.rizqflow.ui.recovery.RecoverySetupScreen
import com.roziqrizal.rizqflow.sync.shouldOfferReconnect
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
    /** Bertambah setiap masuk ke server berhasil (token API baru tersimpan): saat itu pembelian lama dilaporkan. */
    serverLoginSignal: Int = 0,
    /** "Sambungkan ulang ke server" (Tahap 12): masuk Google untuk akun yang sama; hasilnya kembali lewat [serverLoginSignal]. */
    onReconnectServer: () -> Unit = {},
    /** Bertambah setiap sambung ulang yang diminta pengguna gagal menjangkau server: satu kabar lembut. */
    serverConnectFailedSignal: Int = 0,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
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
    var familyRoom by rememberSaveable { mutableStateOf<String?>(null) }
    var familyScanOpen by rememberSaveable { mutableStateOf(false) }
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
    var recoverySetupOpen by rememberSaveable { mutableStateOf(false) }
    var recoveryRestoreOpen by rememberSaveable { mutableStateOf(false) }
    var tampilanOpen by rememberSaveable { mutableStateOf(false) }
    var paywallOpen by rememberSaveable { mutableStateOf(false) }
    var deleteAccountSheet by rememberSaveable { mutableStateOf(false) }
    var deletingAccount by remember { mutableStateOf(false) }
    // Nama Feature yang memicu (S21 mendahulukan manfaatnya); null = manfaat umum dari menu Lainnya.
    var paywallTrigger by rememberSaveable { mutableStateOf<String?>(null) }
    fun openPaywall(trigger: Feature? = null) {
        paywallTrigger = trigger?.name
        paywallOpen = true
    }
    val ownedPlans by workspace.purchases.plans.collectAsState()
    // Pembelian terikat ke akun Play perangkat; di build debug paket tidak dicabut otomatis supaya
    // saklar "Pro (uji)" di menu Lainnya tidak hilang tiap aplikasi dibuka.
    val purchaseController = remember(workspace) {
        PurchaseController(workspace.purchases, (context.applicationContext as RizqflowApplication).billing, revokeMissing = !BuildConfig.DEBUG)
    }
    var proPrice by remember { mutableStateOf<String?>(null) }
    // Penawaran langganan Sync dari toko (harga per masa, uji coba bila ada); null = toko belum terhubung.
    var syncOffers by remember { mutableStateOf<List<SubscriptionOffer>?>(null) }
    var purchaseBusy by remember { mutableStateOf(false) }
    // Varian S21 dipilih dari paket yang dibutuhkan fitur pemicunya: fitur Sync membuka varian Sync.
    val paywallPlan = paywallPlanFor(paywallTrigger?.let { Feature.valueOf(it) })
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // Laporan token pembelian ke server (Tahap 9). Hanya untuk akun yang punya token server (masuk dengan
    // Google): akun lokal dan mode demo tidak pernah mengirim. Hanya LAPORAN di latar: tidak pernah
    // mengubah paket lokal dan tidak pernah menghalangi beli atau pulihkan (lihat PurchaseClaimCoordinator).
    // Keputusan "boleh melapor" ada di purchaseClaimsAllowed dan PurchaseClaimCoordinator (keduanya diuji),
    // bukan di sini: koordinator membaca nilai terbaru tiap klaim, jadi berpindah ke demo berlaku seketika.
    val claimsEnabled by rememberUpdatedState(purchaseClaimsAllowed(demo, account.local))
    // Memutar token server yang hampir kedaluwarsa (Tahap 12): saat dibuka dan sebelum klaim. Diam bila jaringan
    // mati; 401 sudah ditangani ServerSession. Satu hal yang tidak boleh diam: token baru gagal ditulis ke disk.
    suspend fun refreshServerToken() {
        if (workspace.tokenRefresher.refreshIfDue() == RefreshOutcome.StorageFailed) {
            Log.w("RizqflowSession", "Token server baru gagal ditulis ke penyimpanan; dipakai di proses ini saja, sesi bisa berakhir setelah aplikasi ditutup.")
        }
    }
    val purchaseClaims = remember(workspace) {
        val app = context.applicationContext as RizqflowApplication
        PurchaseClaimCoordinator(
            app.billing,
            workspace.purchaseRemote,
            workspace.apiTokenStore,
            app.claimedPurchaseTokens,
            enabled = { claimsEnabled },
            refreshToken = { refreshServerToken() },
        )
    }
    // Baris "Sambungkan ulang ke server": hanya akun Google di data aslinya yang token servernya hilang/berakhir.
    val needsReconnect by workspace.serverSession.needsReconnect.collectAsState()
    val connectingToServer by workspace.serverSession.connecting.collectAsState()
    val offerReconnect = shouldOfferReconnect(google = account.google, demo = demo, needsReconnect = needsReconnect)
    // Kode pemulihan 24 kata (Tahap 13). Penanda non-rahasia dibaca ulang tiap layar kode pemulihan ditutup.
    // Baris "Kode pemulihan" hanya untuk akun Google dengan Sync aktif dan token server aktif; "Pulihkan" tidak
    // menunggu Sync (membaca salinan tidak butuh langganan). Keduanya hanya ajakan lembut, tidak memblokir apa pun.
    var recoveryStatus by remember(workspace) { mutableStateOf(workspace.recovery.status()) }
    val hasServerToken = !needsReconnect
    val restoreAllowed = restoreAvailable(google = account.google, demo = demo, hasToken = hasServerToken)
    // Satu-satunya kabar dari server yang ditampilkan: token pembelian ini sudah dipakai akun Rizqflow lain.
    // Antre di belakang snackbar yang sedang tampil (bukan menimpanya), supaya "Pro sudah aktif" tidak hilang
    // dipotong kabar ini; kabarnya muncul sekali per token per proses (diatur PurchaseClaimCoordinator).
    suspend fun reportPurchases() {
        if (purchaseClaims.claim().showClaimedByOtherNotice) {
            snackbar.showSnackbar(resources.getString(R.string.purchase_claimed_by_other), duration = SnackbarDuration.Long)
        }
    }
    fun reportPurchasesInBackground() {
        scope.launch { reportPurchases() }
    }
    // Pulihkan diam-diam tiap aplikasi dibuka: pasang ulang atau ganti perangkat tidak menghilangkan Pro atau Sync.
    // Paket lokal dibereskan lebih dulu; baru sesudahnya token pembelian dilaporkan ke server. TIDAK ada panggilan
    // ke server sebelum sync(): pemulihan paket dari Google Play tidak boleh menunggu jaringan yang menggantung.
    // Memutar token tidak perlu dipanggil di sini; claim() memutarnya sendiri (refreshToken) sebelum melapor.
    LaunchedEffect(purchaseController, demo) {
        if (!demo) {
            purchaseController.sync()
            reportPurchases()
        }
    }
    // Baru selesai masuk ke server: laporkan pembelian lama (pemulihan). Sebelum itu belum ada token server.
    LaunchedEffect(purchaseClaims, serverLoginSignal) {
        if (serverLoginSignal > 0) reportPurchases()
    }
    // Pembayaran tertunda yang baru lunas (atau kode promo yang ditebus) sampai ke aplikasi lewat toko,
    // bukan lewat tombol Beli yang menunggu: baca ulang toko supaya paketnya terbuka dan di-acknowledge.
    // Pelaporan ke server dijalankan terpisah supaya jaringan yang lambat tidak menunda sync paket berikutnya.
    LaunchedEffect(purchaseController, demo) {
        if (!demo) purchaseController.syncOnExternalUpdates(afterSync = { reportPurchasesInBackground() })
    }
    LaunchedEffect(paywallOpen, paywallPlan) {
        if (!paywallOpen) return@LaunchedEffect
        when (paywallPlan) {
            Plan.PRO -> if (proPrice == null) proPrice = purchaseController.price(Plan.PRO)
            // Dibaca ulang tiap sheet dibuka (bukan disimpan seumur sesi): hak uji coba bisa berubah
            // setelah berlangganan atau membatalkan, dan teks uji coba hanya boleh tampil bila toko menawarkannya sekarang.
            Plan.SYNC -> {
                syncOffers = null
                syncOffers = purchaseController.syncOffers()
            }
        }
    }
    var handledQuick by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(quickCatatRequest) {
        if (quickCatatRequest > handledQuick) {
            handledQuick = quickCatatRequest
            quick = true
        }
    }
    // Berubah setiap ada transaksi yang disimpan, diubah, dihapus, atau diurungkan: daftar memuat ulang.
    var version by rememberSaveable { mutableIntStateOf(0) }
    // Widget layar utama (Pro) ikut diperbarui tiap transaksi berubah atau paket berubah (beli, pulihkan, turun paket).
    LaunchedEffect(workspace, version, ownedPlans, demo) {
        if (!demo) SafeToSpendWidget.updateAll(context, workspace)
    }
    val undoLabel = resources.getString(R.string.action_undo)

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

    // Tawaran aktif "Pulihkan" (snackbar) di perangkat baru SENGAJA tidak dipasang (diputuskan 2026-10-25): belum ada
    // data yang bergantung pada kunci akun, jadi menawarkannya menjanjikan lebih dari kenyataan, dan GET salinan pada
    // tiap start dingin untuk semua akun Google tidak sepadan. Baris menu "Pulihkan" tetap ada. Pasang kembali
    // (`shouldOfferRestoreOnNewDevice` + `RecoveryKeyService.checkRemoteCopy`) setelah sinkron data akun ada.

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
            if (notifier.show(resources.getString(R.string.rules_saved), undoLabel)) {
                workspace.rules.restoreRules(previous)
                version++
            }
        }
    }

    // Berjalan di scope MainHost: layar Detail ruang sudah tertutup saat ruangnya diarsipkan.
    fun announceRoomArchived(undo: ArchiveUndo) {
        version++
        scope.launch {
            if (notifier.show(resources.getString(R.string.room_archived, undo.room.name), undoLabel)) {
                workspace.rules.undoArchive(undo)
                version++
            }
        }
    }

    fun announceReconciled(transactionId: TransactionId?, diff: com.roziqrizal.rizqflow.domain.money.Money) {
        version++
        if (transactionId == null) {
            scope.launch { notifier.show(resources.getString(R.string.reconcile_saved_match)) }
            return
        }
        val message = if (diff.isNegative) {
            resources.getString(R.string.reconcile_saved_expense, formatRupiah(diff.abs()))
        } else {
            resources.getString(R.string.reconcile_saved_income, formatRupiah(diff))
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
            resources.getString(R.string.bills_paid_done, name)
        } else {
            val next = formatDateWith(
                payment.updated.nextDue,
                LocalDate.now(),
                resources.getString(R.string.date_today),
                resources.getString(R.string.date_yesterday),
                context.resources.getStringArray(R.array.month_abbrev).toList(),
            )
            resources.getString(R.string.bills_paid_next, name, next)
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

    // Hapus akun server (Tahap 9): cuma menghapus akun dan langganan Sync di server, data ponsel
    // tidak disentuh. Keputusannya ada di planDeleteAccount (diuji): tanpa token, keluar langsung HANYA bila akun
    // ini memang tidak pernah punya akun server. Akun Google yang token servernya hilang karena sesi berakhir
    // (401) tidak boleh keluar diam-diam, karena akun server dan langganannya masih ada: diminta sambungkan ulang dulu.
    fun deleteAccount() {
        when (val plan = planDeleteAccount(account.google, workspace.serverSession.token(), workspace.serverSession.hasEverHeldToken())) {
            DeleteAccountPlan.SignOutOnly -> {
                deleteAccountSheet = false
                onSignOut()
            }

            DeleteAccountPlan.ReconnectFirst -> {
                deleteAccountSheet = false
                scope.launch { notifier.show(resources.getString(R.string.account_delete_session_expired)) }
            }

            is DeleteAccountPlan.DeleteOnServer -> {
                deletingAccount = true
                scope.launch {
                    // Jawaban 401 sudah ditangani ServerSession (token dibersihkan, baris sambung ulang muncul).
                    when (val result = workspace.accountRemote.deleteAccount(plan.token)) {
                        is RemoteResult.Success -> {
                            workspace.serverSession.onAccountDeleted()
                            // Salinan kode pemulihan di server ikut terhapus bersama akunnya: penanda lokal tidak boleh tetap "terkonfigurasi".
                            workspace.recovery.forgetStatusDurably()
                            deletingAccount = false
                            deleteAccountSheet = false
                            onSignOut()
                        }

                        is RemoteResult.Failure -> {
                            deletingAccount = false
                            val message = if (result.error == RemoteError.Unauthorized) R.string.account_delete_session_expired else R.string.account_delete_failed
                            notifier.show(resources.getString(message))
                        }
                    }
                }
            }
        }
    }

    fun announceFavorite(use: FavoriteUse) {
        version++
        scope.launch {
            if (notifier.show(resources.getString(R.string.quick_used, use.previous.name, formatRupiah(use.transaction.amount)), undoLabel)) {
                workspace.favorites.undoUse(use)
                version++
            }
        }
    }

    fun announceEdit(previous: TransactionSnapshot) {
        version++
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(resources.getString(R.string.saved_edit), actionLabel = undoLabel, duration = SnackbarDuration.Long)
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
            val result = snackbar.showSnackbar(resources.getString(R.string.deleted), actionLabel = undoLabel, duration = SnackbarDuration.Long)
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
                if (saved.roomCount > 0) resources.getString(R.string.saved_income, amount, saved.roomCount) else resources.getString(R.string.saved_income_none, amount)

            TransactionKind.EXPENSE -> {
                val extra = when {
                    saved.favoriteSaved -> " " + resources.getString(R.string.saved_favorite)
                    saved.favoriteError != null -> " " + resources.getString(R.string.saved_favorite_failed, manageErrorText(context, saved.favoriteError))
                    else -> ""
                }
                resources.getString(R.string.saved_expense, amount) + extra
            }
            TransactionKind.TRANSFER -> resources.getString(R.string.saved_transfer, amount)
            // Transaksi pinjaman dicatat dari layar Utang-piutang, yang mengumumkannya sendiri.
            TransactionKind.LOAN_OUT, TransactionKind.LOAN_IN -> resources.getString(R.string.saved_transfer, amount)
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
        // Di mode demo ruang kerja aktifnya milik akun demo yang tidak punya token: menghapus "akun" dari sini hanya
        // akan keluar tanpa menghapus apa pun di server. Baris disembunyikan sampai pengguna keluar dari demo.
        onDeleteAccount = if (demo) null else ({ deleteAccountSheet = true }),
        onOpenFamilyScan = { familyScanOpen = true },
        onOpenSyncPaywall = { openPaywall(Feature.ENCRYPTED_SYNC) },
        syncOwned = Plan.SYNC in ownedPlans,
        onReconnect = if (offerReconnect) onReconnectServer else null,
        reconnectBusy = account.busy == AuthBusy.GOOGLE || connectingToServer,
        recoveryRow = recoveryRow(
            google = account.google,
            demo = demo,
            syncOwned = Plan.SYNC in ownedPlans,
            hasToken = hasServerToken,
            status = recoveryStatus,
        ),
        onOpenRecovery = { recoverySetupOpen = true },
        onOpenRecoveryRestore = if (restoreAllowed) ({ recoveryRestoreOpen = true }) else null,
    )
    // Sambung ulang yang diminta pengguna tidak menjangkau server (offline, 503): satu kabar lembut, barisnya tetap ada.
    LaunchedEffect(serverConnectFailedSignal) {
        if (serverConnectFailedSignal > 0) notifier.show(resources.getString(R.string.reconnect_failed))
    }
    if (tampilanOpen) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ThemeSettingsScreen(
                preference = themePreference,
                entitlements = LivePlanEntitlements { ownedPlans },
                onOpenPaywall = { openPaywall(Feature.THEMES) },
                onClose = { tampilanOpen = false },
            )
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
            Box {
                ReportScreen(
                    workspace = workspace,
                    refreshKey = version,
                    entitlements = LivePlanEntitlements { ownedPlans },
                    notifier = notifier,
                    onOpenPaywall = { openPaywall(Feature.REPORTS_AND_INSIGHTS) },
                    onClose = { reportOpen = false },
                )
                // Layar ini menutupi Scaffold beserta snackbar-nya, jadi ia membawa SnackbarHost sendiri.
                SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
            }
        }
    }
    if (familyScanOpen) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box {
                QrScanScreen(
                    onClose = { familyScanOpen = false },
                    onScanned = { invite ->
                        scope.launch {
                            when (val result = joinSharedRoom(workspace, invite)) {
                                is FamilyJoinResult.Success -> {
                                    familyScanOpen = false
                                    familyRoom = result.roomId.value
                                }

                                FamilyJoinResult.NotSignedIn -> notifier.show(resources.getString(R.string.family_join_not_signed_in))
                                is FamilyJoinResult.Failed -> notifier.show(resources.getString(R.string.family_join_failed))
                            }
                        }
                    },
                )
                SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
            }
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
    if (recoverySetupOpen) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box {
                RecoverySetupScreen(
                    workspace = workspace,
                    accountId = account.identifier,
                    onOpenRestore = {
                        recoverySetupOpen = false
                        recoveryStatus = workspace.recovery.status()
                        recoveryRestoreOpen = true
                    },
                    onClose = {
                        recoverySetupOpen = false
                        recoveryStatus = workspace.recovery.status()
                    },
                )
                SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
            }
        }
    }
    if (recoveryRestoreOpen) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box {
                RecoveryRestoreScreen(
                    workspace = workspace,
                    notifier = notifier,
                    onRecovered = {
                        recoveryRestoreOpen = false
                        recoveryStatus = workspace.recovery.status()
                    },
                    onClose = { recoveryRestoreOpen = false },
                )
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
        // Pembelian dan pemulihan sama untuk kedua varian; yang berbeda hanya isi sheet dan paket yang dibeli.
        fun buy(plan: Plan, period: BillingPeriod?) {
            scope.launch {
                purchaseBusy = true
                val outcome = purchaseController.buy(plan, activity = { context.findActivity() }, period = period)
                purchaseBusy = false
                // Paket sudah terbuka dari Google Play; pelaporan ke server berjalan di latar dan tidak menunggu.
                if (outcome.opensPlan()) reportPurchasesInBackground()
                // Sheet menutup hanya bila ada kabar akhir; dibatalkan tetap terbuka supaya bisa dicoba lagi.
                if (outcome != BuyOutcome.CANCELED) paywallOpen = false
                if (outcome != BuyOutcome.CANCELED) notifier.show(resources.getString(buyMessage(outcome, plan)))
            }
        }

        fun restore(plan: Plan) {
            scope.launch {
                purchaseBusy = true
                val report = purchaseController.restoreReport()
                purchaseBusy = false
                if (report.opensPlan()) reportPurchasesInBackground()
                paywallOpen = false
                notifier.show(resources.getString(restoreMessage(report, plan)))
            }
        }

        ModalBottomSheet(onDismissRequest = { paywallOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            when (paywallPlan) {
                Plan.PRO -> PaywallSheet(
                    trigger = paywallTrigger?.let { Feature.valueOf(it) },
                    priceText = proPrice,
                    busy = purchaseBusy,
                    onBuy = { buy(Plan.PRO, period = null) },
                    onRestore = { restore(Plan.PRO) },
                    onDismiss = { paywallOpen = false },
                )

                Plan.SYNC -> SyncPaywallSheet(
                    offers = syncOffers,
                    owned = Plan.SYNC in ownedPlans,
                    busy = purchaseBusy,
                    onBuy = { period -> buy(Plan.SYNC, period) },
                    onRestore = { restore(Plan.SYNC) },
                    onDismiss = { paywallOpen = false },
                )
            }
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
    if (deleteAccountSheet) {
        ModalBottomSheet(onDismissRequest = { if (!deletingAccount) deleteAccountSheet = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Text(stringResource(R.string.account_delete_confirm_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.account_delete_confirm_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                // Konfirmasi tambahan (keputusan pemilik 2026-10-24): PIN bila kunci aplikasi aktif, bila tidak ketik HAPUS.
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    DeleteAccountConfirmContent(
                        confirmation = remember(appLock) { DeleteAccountConfirmation(appLock) },
                        busy = deletingAccount,
                        onConfirm = ::deleteAccount,
                        onCancel = { deleteAccountSheet = false },
                    )
                }
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
                onOpenPaywall = { openPaywall(it) },
            )
        }
    } else if (catat) {
        // Surface menangkap sentuhan supaya tidak tembus ke menu utama di bawahnya.
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            CatatFlow(workspace, onClose = { catat = false }, onSaved = ::announce, onOpenPaywall = { openPaywall(it) })
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
                    onOpenFamily = { familyRoom = it.value },
                )
                SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
            }
        }
    } else if (familyRoom != null) {
        // Sama seperti Zakat/Peran: dibuka dari Detail ruang tanpa menutupnya, jadi Kembali balik ke sana.
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box {
                RuangKeluargaScreen(
                    workspace = workspace,
                    roomId = RoomId(familyRoom.orEmpty()),
                    notifier = notifier,
                    onClose = { familyRoom = null },
                )
                SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
            }
        }
    }
}
