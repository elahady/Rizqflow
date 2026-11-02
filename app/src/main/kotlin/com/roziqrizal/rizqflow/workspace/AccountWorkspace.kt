package com.roziqrizal.rizqflow.workspace

import android.content.Context
import android.util.Log
import com.roziqrizal.rizqflow.BuildConfig
import com.roziqrizal.rizqflow.billing.PurchaseStore
import com.roziqrizal.rizqflow.data.LocalLedger
import com.roziqrizal.rizqflow.data.crypto.AndroidKeystoreAccountKeyProvider
import com.roziqrizal.rizqflow.data.crypto.AndroidKeystoreMemberSecretStore
import com.roziqrizal.rizqflow.data.crypto.AndroidKeystoreRoomKeyProvider
import com.roziqrizal.rizqflow.data.remote.ApiBaseUrl
import com.roziqrizal.rizqflow.data.remote.RetrofitAccountRemote
import com.roziqrizal.rizqflow.data.remote.RetrofitDeviceRemote
import com.roziqrizal.rizqflow.data.remote.RetrofitPurchaseRemote
import com.roziqrizal.rizqflow.data.remote.RetrofitPushTokenRegistrar
import com.roziqrizal.rizqflow.data.remote.RetrofitRecoveryKeyRemote
import com.roziqrizal.rizqflow.data.remote.RetrofitRoomMembershipRemote
import com.roziqrizal.rizqflow.data.remote.RetrofitSyncHistoryRemote
import com.roziqrizal.rizqflow.data.remote.RetrofitSyncRemote
import com.roziqrizal.rizqflow.data.remote.RizqflowApiClient
import com.roziqrizal.rizqflow.data.remote.RizqflowApiService
import com.roziqrizal.rizqflow.data.remote.SharedPrefsApiTokenStore
import com.roziqrizal.rizqflow.data.sync.AccountSyncHandler
import com.roziqrizal.rizqflow.data.sync.AllocationConfigSyncHandler
import com.roziqrizal.rizqflow.data.sync.BillSyncHandler
import com.roziqrizal.rizqflow.data.sync.CategorySyncHandler
import com.roziqrizal.rizqflow.data.sync.DebtPaymentSyncHandler
import com.roziqrizal.rizqflow.data.sync.DebtSyncHandler
import com.roziqrizal.rizqflow.data.sync.JsonTransactionSyncCodec
import com.roziqrizal.rizqflow.data.sync.PreRestoreBackup
import com.roziqrizal.rizqflow.data.sync.PreRestoreCleaner
import com.roziqrizal.rizqflow.data.sync.PureWorkspaceReplacer
import com.roziqrizal.rizqflow.data.sync.RecurringRuleSyncHandler
import com.roziqrizal.rizqflow.data.sync.RoomSharedRecordStore
import com.roziqrizal.rizqflow.data.sync.RoomSyncHandler
import com.roziqrizal.rizqflow.data.sync.RoomWorkspacePurityChecker
import com.roziqrizal.rizqflow.data.sync.SharedPrefsRecoveryStatusStore
import com.roziqrizal.rizqflow.data.sync.SharedPrefsSyncCursorStore
import com.roziqrizal.rizqflow.data.sync.SharedRoomActivation
import com.roziqrizal.rizqflow.data.sync.TransactionSyncHandler
import com.roziqrizal.rizqflow.data.sync.ZakatProfileSyncHandler
import com.roziqrizal.rizqflow.data.sync.WealthItemSyncHandler
import com.roziqrizal.rizqflow.data.sync.WealthCheckSyncHandler
import com.roziqrizal.rizqflow.data.sync.ZakatPaymentSyncHandler
import com.roziqrizal.rizqflow.data.sync.GoldPriceSyncHandler
import com.roziqrizal.rizqflow.data.sync.TraderProfileSyncHandler
import com.roziqrizal.rizqflow.data.sync.DcaPlanSyncHandler
import com.roziqrizal.rizqflow.data.sync.QuickFavoriteSyncHandler
import com.roziqrizal.rizqflow.data.sync.DayCheckSyncHandler
import com.roziqrizal.rizqflow.domain.sync.AccountKeyProvider
import com.roziqrizal.rizqflow.domain.sync.AccountRemote
import com.roziqrizal.rizqflow.domain.sync.ApiTokenStore
import com.roziqrizal.rizqflow.domain.sync.DeviceRemote
import com.roziqrizal.rizqflow.domain.sync.EnvelopeService
import com.roziqrizal.rizqflow.domain.sync.EnvelopeSyncHandler
import com.roziqrizal.rizqflow.domain.sync.FullResync
import com.roziqrizal.rizqflow.domain.sync.LocalSyncHistoryReader
import com.roziqrizal.rizqflow.domain.sync.NewDeviceRecoveryAdvisor
import com.roziqrizal.rizqflow.domain.sync.NewDeviceRestoreFlow
import com.roziqrizal.rizqflow.domain.sync.PersonalSyncCoordinator
import com.roziqrizal.rizqflow.domain.sync.PurchaseRemote
import com.roziqrizal.rizqflow.domain.sync.PushTokenRegistrar
import com.roziqrizal.rizqflow.domain.sync.PushTokenSync
import com.roziqrizal.rizqflow.domain.sync.RecoveryKeyRemote
import com.roziqrizal.rizqflow.domain.sync.RecoveryKeyService
import com.roziqrizal.rizqflow.domain.sync.RecoveryStatusStore
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import com.roziqrizal.rizqflow.domain.sync.RoomKeyProvider
import com.roziqrizal.rizqflow.domain.sync.MemberSecretStore
import com.roziqrizal.rizqflow.domain.sync.RoomMemberKeyRedistribution
import com.roziqrizal.rizqflow.domain.sync.RoomMembershipRemote
import com.roziqrizal.rizqflow.domain.sync.SharedRoomLeaver
import com.roziqrizal.rizqflow.domain.sync.SharedRoomStatus
import com.roziqrizal.rizqflow.domain.sync.SyncCursorStore
import com.roziqrizal.rizqflow.domain.sync.SyncHistoryRemote
import com.roziqrizal.rizqflow.domain.sync.SyncCoordinator
import com.roziqrizal.rizqflow.domain.sync.SyncRemote
import com.roziqrizal.rizqflow.domain.sync.SyncResult
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.bill.BillReminderService
import com.roziqrizal.rizqflow.domain.bill.BillService
import com.roziqrizal.rizqflow.domain.debt.DebtReminderService
import com.roziqrizal.rizqflow.domain.debt.DebtService
import com.roziqrizal.rizqflow.domain.entitlement.Entitlements
import com.roziqrizal.rizqflow.domain.entitlement.LivePlanEntitlements
import com.roziqrizal.rizqflow.domain.ledger.DemoData
import com.roziqrizal.rizqflow.domain.ledger.FavoriteService
import com.roziqrizal.rizqflow.domain.ledger.LedgerService
import com.roziqrizal.rizqflow.domain.ledger.ManagementService
import com.roziqrizal.rizqflow.domain.ledger.ReconciliationService
import com.roziqrizal.rizqflow.domain.ledger.ReminderService
import com.roziqrizal.rizqflow.domain.ledger.RuleService
import com.roziqrizal.rizqflow.domain.ledger.WorkspaceSetup
import com.roziqrizal.rizqflow.domain.role.DcaReminderService
import com.roziqrizal.rizqflow.domain.recurring.RecurringService
import com.roziqrizal.rizqflow.domain.role.RoleService
import com.roziqrizal.rizqflow.domain.smartinput.SmartInputService
import com.roziqrizal.rizqflow.domain.zakat.HaulReminderService
import com.roziqrizal.rizqflow.domain.zakat.ZakatService
import com.roziqrizal.rizqflow.push.FcmAvailability
import com.roziqrizal.rizqflow.sync.DeviceId
import com.roziqrizal.rizqflow.sync.DeviceName
import com.roziqrizal.rizqflow.sync.ServerLink
import com.roziqrizal.rizqflow.sync.ServerSession
import com.roziqrizal.rizqflow.sync.TokenRefresher
import com.roziqrizal.rizqflow.data.sync.SharedPrefsAutoSyncSettings
import com.roziqrizal.rizqflow.data.sync.AndroidNetworkChangeSource
import com.roziqrizal.rizqflow.domain.auth.AccountStorage
import com.roziqrizal.rizqflow.data.sync.FilePreRestoreCopyStore
import com.roziqrizal.rizqflow.data.sync.PreRestoreFiles
import com.roziqrizal.rizqflow.data.sync.PreRestoreUseLock
import com.roziqrizal.rizqflow.domain.sync.PreRestoreRevertFlow
import com.roziqrizal.rizqflow.data.sync.SharedPrefsLastSyncStore
import com.roziqrizal.rizqflow.domain.sync.LastSyncStore
import com.roziqrizal.rizqflow.domain.sync.NetworkChangeSource
import com.roziqrizal.rizqflow.domain.entitlement.Feature
import com.roziqrizal.rizqflow.domain.sync.AutoSyncGate
import com.roziqrizal.rizqflow.domain.sync.AutoSyncNetwork
import com.roziqrizal.rizqflow.domain.sync.AutoSyncOrchestrator
import com.roziqrizal.rizqflow.domain.sync.AutoSyncSettings
import com.roziqrizal.rizqflow.domain.sync.RecoveryStatus
import com.roziqrizal.rizqflow.domain.sync.WorkspaceAutoSyncSteps
import com.roziqrizal.rizqflow.sync.AndroidAutoSyncNetwork
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Ruang kerja satu akun yang sedang masuk: databasenya dan layanan di atasnya. Dibuat saat akun
 * masuk dan ditutup saat keluar; akun lain membuka berkas lain (satu database per akun).
 * Pengenal dibuat sebagai UUID dan waktu diambil dari jam sistem.
 */
class AccountWorkspace private constructor(
    private val local: LocalLedger,
    val purchases: PurchaseStore,
    /** Semua remote terlindungi di bawah ini sudah dibungkus [serverSession] (jawaban 401 ditangani satu tempat). */
    val accountRemote: AccountRemote,
    val apiTokenStore: ApiTokenStore,
    /** Keadaan sesi server akun ini: token, masa berlaku, dan "perlu sambungkan ulang" (Tahap 12). */
    val serverSession: ServerSession,
    val roomKeyProvider: RoomKeyProvider,
    /** `member_secret` undangan ruang bersama (key_grant langkah 2): rahasia sendiri sebagai anggota, dan rahasia terbitan sebagai pemilik. */
    val memberSecretStore: MemberSecretStore,
    private val syncRemote: SyncRemote,
    val roomMembershipRemote: RoomMembershipRemote,
    val purchaseRemote: PurchaseRemote,
    /** Daftar perangkat dan "Keluar dari semua perangkat" (Tahap 14); sudah dibungkus [serverSession]. */
    val deviceRemote: DeviceRemote,
    /** Riwayat sinkron dari server (metadata saja, Tahap 7); sudah dibungkus [serverSession]. */
    val syncHistoryRemote: SyncHistoryRemote,
    private val syncCursorStore: SyncCursorStore,
    /** Kunci akun di Keystore (Tahap 13), satu per akun; dasar kode pemulihan. */
    val accountKeyProvider: AccountKeyProvider,
    /** Salinan kunci akun terbungkus kode pemulihan di server; sudah dibungkus [serverSession]. */
    private val recoveryRemote: RecoveryKeyRemote,
    /** Penanda NON-RAHASIA "kode pemulihan sudah diatur" per akun (tidak pernah memuat kode atau kunci). */
    private val recoveryStatusStore: RecoveryStatusStore,
    /** Pendaftar token FCM, sudah dibungkus [serverSession]; null bila jalur FCM mati (tanpa google-services.json). */
    pushTokenRegistrar: PushTokenRegistrar?,
    /** Pengenal pemasangan ([DeviceId]); dipakai sync otomatis, sync pribadi perangkat baru, dan pendaftaran token FCM. */
    private val deviceId: String,
    /** Saklar "hanya Wi-Fi" sync otomatis (per perangkat); dibaca dan diubah layar Sinkron otomatis. */
    val autoSyncSettings: AutoSyncSettings,
    private val autoSyncNetwork: AutoSyncNetwork,
    /** Pendengar perubahan jaringan sync otomatis; hanya aktif selama aplikasi di depan (diatur orkestrator). */
    private val networkChangeSource: NetworkChangeSource,
    /** Waktu sinkron terakhir yang bertahan setelah proses mati, satu per akun. */
    private val lastSyncStore: LastSyncStore,
    /**
     * Berkas database akun ini, dihitung dari konteks TANPA membuka database. Dipakai kunci dan penyimpanan salinan
     * pemulihan yang bisa dipanggil sesudah database ditutup untuk ditimpa (`local.databaseFile` akan membuka lagi
     * database yang sedang ditimpa).
     */
    private val databasePath: java.io.File,
) : AutoCloseable {
    private val newId: () -> String = { UUID.randomUUID().toString() }

    val repositories: LocalLedger get() = local
    val setup = WorkspaceSetup(local.workspace, newId)
    val ledger = LedgerService(local.accounts, local.rooms, local.transactions, newId, System::currentTimeMillis)

    // Status pembelian sungguhan lewat Google Play Billing menyusul (Tahap 7 lanjutan, perlu listing
    // Play Console dulu); [purchases] sudah jadi satu-satunya sumber, dibaca ulang tiap dipanggil
    // supaya perubahan paket langsung berlaku tanpa membuka ulang ruang kerja ini.
    val entitlements: Entitlements = LivePlanEntitlements { purchases.plans.value }
    val rules = RuleService(local.rooms, entitlements, newId)
    val management = ManagementService(local.accounts, local.rooms, newId)
    val favorites = FavoriteService(local.favorites, local.accounts, local.rooms, ledger, newId, System::currentTimeMillis)
    val zakat = ZakatService(local.rooms, local.transactions, local.zakat, ledger, newId, entitlements = entitlements)
    val reconciliation = ReconciliationService(local.accounts, local.rooms, local.transactions, ledger)
    val reminder = ReminderService(local.settings, local.accounts, local.rooms, local.transactions, ledger)
    val haulReminder = HaulReminderService(local.rooms, zakat, local.settings)
    val roles = RoleService(local.rooms, local.accounts, local.transactions, local.roles, ledger, entitlements)
    val dcaReminder = DcaReminderService(roles, local.settings)
    val recurring = RecurringService(local.recurring, local.accounts, local.rooms, local.transactions, ledger, newId)
    val bills = BillService(local.bills, local.accounts, local.rooms, local.transactions, ledger, newId)
    val billReminder = BillReminderService(local.bills, local.settings)
    val debts = DebtService(local.debts, local.accounts, local.transactions, ledger, newId)
    val debtReminder = DebtReminderService(local.debts, local.settings)
    val smartInput = SmartInputService(local.transactions)
    val demo = DemoData(setup, ledger, management, favorites, local.rooms)

    /**
     * Status kotor/LWW amplop lewat tabel `sync_record` yang SAMA dengan cakupan pribadi (peran-anggota.md E.2/H
     * langkah 8) — lihat KDoc [com.roziqrizal.rizqflow.domain.sync.SharedRecordStore].
     */
    val sharedRecordStore = RoomSharedRecordStore(local.db)

    /** Penangan `envelope` (amplop bulanan, peran-anggota.md H langkah 8) — ditambahkan ke [sync] lewat `extraHandlers`. */
    val envelopeSync = EnvelopeSyncHandler(sharedRecordStore, local.family, local.family, issues = local.syncStore)

    /** Menulis amplop dari sheet "Atur amplop" (hanya Pemilik, ditegakkan UI) dan menandainya kotor untuk [envelopeSync]. */
    val envelopes = EnvelopeService(local.family, sharedRecordStore, local.syncClock)

    /** Sync push/pull ruang keluarga: transaksi (Tahap 9, mesin Tahap 2) dan amplop (H langkah 8) — lihat [SyncCoordinator]. */
    val sync = SyncCoordinator(
        transactions = local.transactions,
        rooms = local.rooms,
        accounts = local.accounts,
        roomKeys = roomKeyProvider,
        syncRemote = syncRemote,
        codec = JsonTransactionSyncCodec(),
        apiTokenStore = apiTokenStore,
        cursorStore = syncCursorStore,
        // Baris ruang bersama yang gagal dibuka/diterapkan dicatat di sync_issue (tampil di "Riwayat sinkron").
        issueStore = local.syncStore,
        // Tahap 9 lanjutan: kursor per cakupan ruang bersama pindah ke tabel sync_cursor (sama dengan cakupan
        // pribadi), jatuh ke syncCursorStore (SharedPreferences) hanya untuk ruang yang belum pernah ditarik lewat
        // mesin ini sejak pembaruan ini — lihat KDoc SyncCoordinator.scopedCursors.
        scopedCursors = local.syncStore,
        // `sharedRoomActivation` dideklarasikan di bawah; aman karena lambda ini hanya dipanggil belakangan, setelah
        // konstruksi AccountWorkspace selesai (sama seperti sharedRoomLeaver merujuk sharedRoomActivation.markLeft).
        onNotRoomMember = { roomId -> sharedRoomActivation.markRemoved(roomId) },
        extraHandlers = listOf(envelopeSync),
        // key_grant (langkah 5): rahasia sendiri sebagai anggota, untuk membuka grant dan menandai baris yang menunggu kunci.
        memberSecrets = memberSecretStore,
    )

    /**
     * Kode pemulihan 24 kata (Tahap 13): bungkus kunci akun PLUS kunci semua ruang keluarga aktif yang
     * diikuti perangkat ini (format `version` 2 sejak 2026-10-27, [RecoveryBundle]), simpan salinannya di
     * server, dan pulihkan keduanya di perangkat baru. Kode tidak pernah disimpan atau dikirim; lihat
     * [RecoveryKeyService]. Sejak Tahap 3 sync-desain.md, pulihkan kunci AKUN menggabungkan (merge) ke
     * keyset lokal (lihat `AccountKeyProvider.importAccountKeyFromRecovery`), bukan menimpa — data cakupan
     * pribadi yang sudah dienkripsi kunci lama tetap terbaca. Kunci RUANG yang ikut terbungkus tetap ditimpa
     * ([RoomKeyProvider.importRoomKey], belum diubah): aman karena kunci ruang sebelum dipulihkan memang
     * baru dibuat acak sendiri dan belum pernah dipakai mengenkripsi apa pun.
     *
     * [onFirstTimeConfigured]: begitu kode pemulihan terkonfigurasi untuk PERTAMA kalinya di akun ini,
     * seluruh data lokal yang sudah ada ditandai kotor ([SyncBootstrap.markAllDirty]) supaya ikut terkirim
     * begitu gerbang push [PersonalSyncCoordinator] terbuka (Tahap 3 bagian F, keputusan pemilik #14).
     */
    val recovery = RecoveryKeyService(
        accountKeyProvider,
        recoveryRemote,
        apiTokenStore,
        recoveryStatusStore,
        roomKeys = roomKeyProvider,
        activeSharedRoomIds = { local.syncStore.sharedRooms().filter { it.status == SharedRoomStatus.ACTIVE }.map { it.roomId } },
        // Setelah semua data ditandai kotor, beri tahu sync otomatis: gerbang push baru terbuka, jadi tanpa ini data lama
        // baru terkirim di pemicu berikutnya (mis. aplikasi dibuka lagi).
        onFirstTimeConfigured = {
            local.syncBootstrap.markAllDirty()
            local.changeNotifier.fire()
        },
    )

    /**
     * Sync push/pull cakupan PRIBADI (Tahap 3 sync-desain.md): mesin generik `room`/`account`/`category`/
     * `allocation_config`/`transaction` lewat [PersonalSyncCoordinator], digerbang [recovery] untuk push
     * (bagian F #14) — lihat KDoc kelas itu.
     */
    val personalSync = PersonalSyncCoordinator(
        handlers = listOf(
            RoomSyncHandler(local.db, local.syncStore),
            AccountSyncHandler(local.db, local.syncStore),
            CategorySyncHandler(local.db, local.syncStore),
            AllocationConfigSyncHandler(local.db, local.syncStore),
            TransactionSyncHandler(local.db, local.syncStore),
            DebtSyncHandler(local.db, local.syncStore),
            DebtPaymentSyncHandler(local.db, local.syncStore),
            BillSyncHandler(local.db, local.syncStore),
            RecurringRuleSyncHandler(local.db, local.syncStore),
            ZakatProfileSyncHandler(local.db, local.syncStore),
            WealthItemSyncHandler(local.db, local.syncStore),
            WealthCheckSyncHandler(local.db, local.syncStore),
            ZakatPaymentSyncHandler(local.db, local.syncStore),
            GoldPriceSyncHandler(local.db, local.syncStore),
            TraderProfileSyncHandler(local.db, local.syncStore),
            DcaPlanSyncHandler(local.db, local.syncStore),
            QuickFavoriteSyncHandler(local.db, local.syncStore),
            DayCheckSyncHandler(local.db, local.syncStore),
        ),
        store = local.syncStore,
        accountKeys = accountKeyProvider,
        syncRemote = syncRemote,
        apiTokenStore = apiTokenStore,
        recovery = recovery,
    )

    /**
     * Alur pulihkan perangkat baru (Tahap 3 sync-desain.md bagian B "Perangkat baru", keputusan pemilik
     * #11): [NewDeviceRecoveryAdvisor.decide] menentukan apakah ruang kerja ini masih murni (hasil
     * onboarding bawaan saja, belum dipakai) DAN akun ini punya kode pemulihan di server — bila ya,
     * pemanggil (UI, menyusul) boleh langsung memanggil [pureWorkspaceReplacer] lalu [personalSync] tanpa
     * bertanya; bila tidak, UI wajib menawarkan "Gabung" (biarkan [personalSync] berjalan biasa) atau
     * "Pulihkan" (panggil [pureWorkspaceReplacer] juga, TAPI hanya setelah salinan otomatis/backup —
     * lihat KDoc kelasnya) ke pengguna. Layar belum dibuat (di luar cakupan Tahap 3); ini lapisan logiknya.
     */
    val newDeviceRecoveryAdvisor = NewDeviceRecoveryAdvisor(recovery, RoomWorkspacePurityChecker(local.db))

    /** Eksekusi nyata "ganti dengan data server" untuk [newDeviceRecoveryAdvisor] — lihat KDoc kelasnya. */
    val pureWorkspaceReplacer = PureWorkspaceReplacer(local.db, local.syncStore)

    /**
     * Alur perangkat baru lengkap (keputusan pemilik #11) yang dipakai `NewDeviceRestoreScreen` setelah kunci akun
     * dipulihkan: otomatis ganti bila murni, atau Gabung/Pulihkan; "Pulihkan" membuat salinan `.prerestore` berkas
     * database lebih dulu ([PreRestoreBackup]) dan baru menghapus setelah konfirmasi eksplisit.
     */
    val newDeviceRestore = NewDeviceRestoreFlow(
        advisor = newDeviceRecoveryAdvisor,
        purity = RoomWorkspacePurityChecker(local.db),
        replacer = pureWorkspaceReplacer,
        backup = PreRestoreBackup.forLedger(local),
        sync = { personalSync.syncNow(deviceId) },
    )

    /**
     * Keluarkan anggota (Tahap 9): rotasi kunci ruang lalu siapkan undangan (QR) baru untuk anggota
     * yang tetap — lihat [RoomMemberKeyRedistribution]. Tidak mengubah keanggotaan di server.
     */
    val memberRemoval = RoomMemberKeyRedistribution(roomKeyProvider)

    /** Memutar token API sebelum kedaluwarsa (Tahap 12); satu per ruang kerja, jadi satu Mutex per akun. */
    val tokenRefresher = TokenRefresher(serverSession, accountRemote)

    /**
     * Token FCM perangkat ini di server (wiring FCM 2026-11-01): kirim saat masuk/dibuka/token baru, cabut saat
     * keluar dan sebelum hapus akun; hanya dengan Sync (lihat [PushTokenSync]). Null bila jalur FCM mati (build
     * tanpa google-services.json), sehingga tidak ada panggilan server sama sekali untuk push.
     */
    val pushTokens: PushTokenSync? = pushTokenRegistrar?.let { PushTokenSync(it, entitlements, { serverSession.token() }, deviceId) }

    /** Lingkup coroutine sync otomatis (run, debounce, percobaan ulang); dibatalkan di [close] sebelum database ditutup. */
    private val autoSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Sync otomatis (Tahap 6 sync-desain.md bagian H; keputusan pemilik #14 dan #15): SATU orkestrator per ruang kerja
     * untuk cakupan pribadi lalu tiap ruang bersama aktif, hanya saat aplikasi di depan, tanpa WorkManager. Pemicunya
     * dipasang di [connectAutoSync]; MainHost memanggil `onForeground`/`onBackground`/`resume`, dan layar Sinkron
     * otomatis memanggil `syncNow` serta membaca `state`. Sync ruang bersama lewat [syncSharedRoomNow] (kunci ruang
     * yang sama dengan tombol "Sinkronkan sekarang" ruang itu), jadi tidak berjalan dobel.
     */
    val syncOrchestrator = AutoSyncOrchestrator(
        scope = autoSyncScope,
        steps = WorkspaceAutoSyncSteps(
            recoveryConfigured = { recovery.status() is RecoveryStatus.Configured },
            personalSync = { personalSync.syncNow(deviceId) },
            activeSharedRoomIds = { local.syncStore.sharedRooms().filter { it.status == SharedRoomStatus.ACTIVE }.map { it.roomId } },
            sharedRoomSync = { roomId -> syncSharedRoomNow(roomId, deviceId) },
        ),
        gate = object : AutoSyncGate {
            override fun apiToken(): String? = apiTokenStore.read()

            override fun syncEntitled(): Boolean = entitlements.isEnabled(Feature.ENCRYPTED_SYNC)
        },
        network = autoSyncNetwork,
        log = { message -> Log.w(TAG, "Sync otomatis: $message") },
        networkChanges = networkChangeSource,
        lastSync = lastSyncStore,
    )

    /**
     * Memasang pemicu: tiap rekaman ditandai kotor ([LocalChangeNotifier]) dan tiap pengeluaran ruang bersama
     * tersimpan ([SharedRoomAutoSync], kini hanya meneruskan) menjadi `onLocalChange` yang didebounce. Galat sync
     * tidak pernah sampai ke pemanggil penyimpanan.
     */
    private fun connectAutoSync() {
        local.changeNotifier.listener = { syncOrchestrator.onLocalChange() }
        local.autoSync.delegateTriggersTo { syncOrchestrator.onLocalChange() }
    }

    /**
     * Tombol "Sinkronkan sekarang": [SyncCoordinator.syncNow] di bawah kunci ruang yang SAMA dengan sinkron otomatis
     * ([com.roziqrizal.rizqflow.data.sync.SharedRoomAutoSync.withRoomLock]), jadi tidak pernah berjalan bersamaan
     * dengan putaran otomatis untuk ruang itu; bila ada yang sedang berjalan, tombol ini menunggu lalu berjalan sendiri.
     */
    suspend fun syncSharedRoomNow(roomId: RoomId, deviceId: String): SyncResult =
        local.autoSync.withRoomLock(roomId) { sync.syncNow(roomId, deviceId) }

    /**
     * Pemilik menerbitkan [KeyGrant] untuk anggota yang tetap setelah rotasi kunci (key_grant langkah 4): lewat kunci ruang yang
     * sama dengan sinkron, supaya tidak berpapasan dengan putaran otomatis ruang itu. Lihat [SyncCoordinator.publishKeyGrants].
     */
    suspend fun publishKeyGrants(roomId: RoomId, deviceId: String, memberSecrets: List<ByteArray>): RemoteResult<Int> =
        local.autoSync.withRoomLock(roomId) { sync.publishKeyGrants(roomId, deviceId, memberSecrets) }

    /**
     * Keadaan sinkron lokal untuk layar "Riwayat sinkron": baris kotor dan kursor per cakupan, plus daftar masalah
     * (`sync_issue`). Hanya metadata, tanpa isi transaksi.
     */
    val syncHistoryReader = LocalSyncHistoryReader(
        store = local.syncStore,
        cursors = syncCursorStore,
        pendingShared = { roomId -> local.transactions.pendingSync(roomId).size },
        roomName = { roomId -> local.rooms.find(roomId)?.name },
    )

    /**
     * Diagnostik "sinkron ulang penuh" (sync-desain.md bagian I Tahap 7): reset kursor pull lalu tarik ulang dari awal,
     * tanpa menghapus data lokal. Sinkron pribadi ikut menerapkan ulang baris perangkat ini sendiri; ruang bersama
     * memakai kunci ruang yang sama dengan sinkron otomatis ([syncSharedRoomNow]).
     */
    val fullResync = FullResync(
        store = local.syncStore,
        cursors = syncCursorStore,
        personal = { personalSync.syncNow(deviceId, reapplyOwnRows = true) },
        shared = { roomId -> syncSharedRoomNow(roomId, deviceId) },
    )

    /** Pengenal pemasangan ini, dipakai layar Riwayat sinkron untuk mengenali baris perangkat ini di jawaban server. */
    val syncDeviceId: String get() = deviceId

    /**
     * Membersihkan salinan `.prerestore` yang sudah tidak diperlukan ([PreRestoreCleaner]): umur minimal 30 hari dan
     * hanya bila ruang kerja kini berisi data. Aman dipanggil tiap ruang kerja dibuka; tidak pernah melempar.
     */
    suspend fun cleanUpPreRestore(): PreRestoreCleaner.Outcome {
        val cleaner = PreRestoreCleaner(
            databaseFile = { databasePath },
            isWorkspacePure = { RoomWorkspacePurityChecker(local.db).isPure() },
            // Salinan yang sedang dibaca layar "Kembalikan data sebelum pemulihan" tidak boleh dihapus.
            isInUse = preRestoreLock::isHeld,
        )
        val outcome = cleaner.cleanUp()
        cleaner.cleanUpRevertCopies()
        return outcome
    }

    /** Kunci pakai salinan `.prerestore` (berkas penanda di samping database); dihormati [cleanUpPreRestore]. */
    private val preRestoreLock = PreRestoreUseLock(databaseFile = { databasePath })

    /**
     * "Kembalikan data sebelum pemulihan" (menu Lainnya, hanya bila `.prerestore` ada): memeriksa salinan, membuat salinan
     * darurat `.prerevert` dari keadaan sekarang, dan setelah konfirmasi eksplisit menyerahkan isi `.prerestore` ke jalur
     * penimpa berkas yang sama dengan pemulihan cadangan (`WorkspaceHost.restoreFromBackup`). Tidak memakai primitif baru
     * untuk menimpa; lihat KDoc [PreRestoreRevertFlow]. Versi skema tertinggi dibaca dari database yang terbuka.
     */
    val preRestoreRevert = PreRestoreRevertFlow(
        copies = FilePreRestoreCopyStore(databaseFile = { databasePath }, lock = preRestoreLock),
        emergency = PreRestoreBackup.forLedger(local, PreRestoreFiles.PREREVERT),
        maxSchemaVersion = { local.schemaVersion },
    )

    /**
     * Menandai ruang bersama ACTIVE setelah pendaftaran anggota di server berhasil (undangan QR diterima, atau
     * pemilik membuka Ruang Keluarga). Tanpa baris ACTIVE, ruang itu tidak ikut [syncOrchestrator].
     */
    val sharedRoomActivation = SharedRoomActivation(local.syncStore)

    /**
     * Anggota biasa keluar dari ruang bersama: cabut keanggotaan di server, lalu tandai ruang LEFT di perangkat ini
     * (tanpa menghapus data lokal; sinkron untuk ruang itu berhenti). Pemilik tidak bisa keluar.
     */
    val sharedRoomLeaver = SharedRoomLeaver(roomMembershipRemote) { roomId -> sharedRoomActivation.markLeft(roomId) }

    override fun close() {
        // Pemicu dilepas dan lingkup sync dibatalkan DULU, supaya tidak ada run yang menyentuh database yang sedang ditutup.
        local.changeNotifier.listener = null
        // Pendengar jaringan dilepas eksplisit: membatalkan lingkup saja tidak menutup callback ConnectivityManager.
        syncOrchestrator.shutdown()
        autoSyncScope.cancel()
        local.close()
    }

    companion object {
        /**
         * Klien HTTP (Tahap 9) dirakit di sini, per ruang kerja akun — bukan singleton app-wide —
         * supaya otomatis ganti lingkup saat akun berganti, konsisten dengan database yang juga
         * satu per akun. Gagal dihubungi server tidak menggagalkan [open]: offline-first berarti
         * ruang kerja tetap terbuka penuh, pemanggil (`AccountRemote`) yang menangani jaringan mati.
         */
        fun open(context: Context, accountId: String): AccountWorkspace {
            val apiService = RizqflowApiClient.create(resolveBaseUrl())
            // Token server milik akun ini saja: akun lain di perangkat yang sama membaca berkas lain.
            val tokenStore = SharedPrefsApiTokenStore(context, accountId)
            // Satu-satunya tempat jawaban 401 ditangani: setiap remote terlindungi WAJIB lewat session.guard.
            val session = ServerSession(tokenStore)
            val autoSyncSettings = SharedPrefsAutoSyncSettings(context)
            val workspace = AccountWorkspace(
                local = LocalLedger.open(context, accountId),
                purchases = PurchaseStore.shared(context),
                accountRemote = session.guard(RetrofitAccountRemote(apiService, DeviceName.read())),
                apiTokenStore = tokenStore,
                serverSession = session,
                roomKeyProvider = AndroidKeystoreRoomKeyProvider(context),
                memberSecretStore = AndroidKeystoreMemberSecretStore(context),
                syncRemote = session.guard(RetrofitSyncRemote(apiService)),
                roomMembershipRemote = session.guard(RetrofitRoomMembershipRemote(apiService)),
                purchaseRemote = session.guard(RetrofitPurchaseRemote(apiService)),
                deviceRemote = session.guard(RetrofitDeviceRemote(apiService)),
                syncHistoryRemote = session.guard(RetrofitSyncHistoryRemote(apiService)),
                syncCursorStore = SharedPrefsSyncCursorStore(context),
                accountKeyProvider = AndroidKeystoreAccountKeyProvider(context, accountId),
                recoveryRemote = session.guard(RetrofitRecoveryKeyRemote(apiService)),
                recoveryStatusStore = SharedPrefsRecoveryStatusStore(context, accountId),
                pushTokenRegistrar = pushTokenRegistrar(context, apiService, session),
                deviceId = DeviceId.read(context),
                autoSyncSettings = autoSyncSettings,
                autoSyncNetwork = AndroidAutoSyncNetwork(context, autoSyncSettings),
                networkChangeSource = AndroidNetworkChangeSource(context),
                lastSyncStore = SharedPrefsLastSyncStore(context, accountId),
                databasePath = context.getDatabasePath(AccountStorage.databaseName(accountId)),
            )
            workspace.connectAutoSync()
            return workspace
        }

        /** Null bila jalur FCM mati: tanpa Firebase tidak ada token FCM yang bisa didaftarkan atau perlu dicabut. */
        private fun pushTokenRegistrar(context: Context, apiService: RizqflowApiService, session: ServerSession): PushTokenRegistrar? =
            if (FcmAvailability.isAvailable(context)) session.guard(RetrofitPushTokenRegistrar(apiService)) else null

        /**
         * Pendaftar token FCM akun [accountId] TANPA membuka databasenya, untuk `RizqflowMessagingService.onNewToken`
         * (tidak ada UI atau ruang kerja yang terbuka). Null bila jalur FCM mati.
         */
        fun pushTokenSync(context: Context, accountId: String): PushTokenSync? {
            val session = ServerSession(SharedPrefsApiTokenStore(context, accountId))
            val registrar = pushTokenRegistrar(context, RizqflowApiClient.create(resolveBaseUrl()), session) ?: return null
            val entitlements = LivePlanEntitlements { PurchaseStore.shared(context).plans.value }
            return PushTokenSync(registrar, entitlements, { session.token() }, DeviceId.read(context))
        }

        /**
         * Remote akun untuk masuk email+sandi (Tahap 14), dipakai SEBELUM ruang kerja akun ada: belum ada akun
         * (dan karenanya belum ada berkas token) untuk diikat. Tanpa penjaga 401, sama seperti masuk Google.
         */
        fun signInRemote(): AccountRemote = RetrofitAccountRemote(RizqflowApiClient.create(resolveBaseUrl()), DeviceName.read())

        /**
         * Sesi server dan remote akun [accountId] TANPA membuka database akunnya. Dipakai keluar akun saat
         * ruang kerja yang aktif ternyata ruang demo: token akun asli tetap harus dibersihkan dan dicabut.
         */
        fun serverLink(context: Context, accountId: String): ServerLink {
            val session = ServerSession(SharedPrefsApiTokenStore(context, accountId))
            val apiService = RizqflowApiClient.create(resolveBaseUrl())
            val pushTokens = pushTokenRegistrar(context, apiService, session)?.let { registrar ->
                PushTokenSync(registrar, LivePlanEntitlements { PurchaseStore.shared(context).plans.value }, { session.token() }, DeviceId.read(context))
            }
            return ServerLink(session, session.guard(RetrofitAccountRemote(apiService)), pushTokens)
        }

        // Alamat dari BuildConfig diperiksa lagi di sini (lapis kedua setelah Gradle): alamat tak sah atau
        // http di build rilis tidak pernah sampai ke Retrofit, dan aplikasi tetap terbuka offline.
        private fun resolveBaseUrl(): String =
            ApiBaseUrl.resolve(BuildConfig.API_BASE_URL, allowCleartext = BuildConfig.DEBUG) { reason ->
                Log.e(TAG, "API_BASE_URL ditolak ($reason); semua pemanggilan server dinonaktifkan")
            }

        private const val TAG = "RizqflowWorkspace"
    }
}
