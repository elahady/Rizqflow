package com.roziqrizal.rizqflow.workspace

import android.content.Context
import android.util.Log
import com.roziqrizal.rizqflow.BuildConfig
import com.roziqrizal.rizqflow.billing.PurchaseStore
import com.roziqrizal.rizqflow.data.LocalLedger
import com.roziqrizal.rizqflow.data.crypto.AndroidKeystoreAccountKeyProvider
import com.roziqrizal.rizqflow.data.crypto.AndroidKeystoreRoomKeyProvider
import com.roziqrizal.rizqflow.data.remote.ApiBaseUrl
import com.roziqrizal.rizqflow.data.remote.RetrofitAccountRemote
import com.roziqrizal.rizqflow.data.remote.RetrofitDeviceRemote
import com.roziqrizal.rizqflow.data.remote.RetrofitPurchaseRemote
import com.roziqrizal.rizqflow.data.remote.RetrofitRecoveryKeyRemote
import com.roziqrizal.rizqflow.data.remote.RetrofitRoomMembershipRemote
import com.roziqrizal.rizqflow.data.remote.RetrofitSyncRemote
import com.roziqrizal.rizqflow.data.remote.RizqflowApiClient
import com.roziqrizal.rizqflow.data.remote.SharedPrefsApiTokenStore
import com.roziqrizal.rizqflow.data.sync.AccountSyncHandler
import com.roziqrizal.rizqflow.data.sync.AllocationConfigSyncHandler
import com.roziqrizal.rizqflow.data.sync.BillSyncHandler
import com.roziqrizal.rizqflow.data.sync.CategorySyncHandler
import com.roziqrizal.rizqflow.data.sync.DebtPaymentSyncHandler
import com.roziqrizal.rizqflow.data.sync.DebtSyncHandler
import com.roziqrizal.rizqflow.data.sync.JsonTransactionSyncCodec
import com.roziqrizal.rizqflow.data.sync.PureWorkspaceReplacer
import com.roziqrizal.rizqflow.data.sync.RecurringRuleSyncHandler
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
import com.roziqrizal.rizqflow.domain.sync.NewDeviceRecoveryAdvisor
import com.roziqrizal.rizqflow.domain.sync.PersonalSyncCoordinator
import com.roziqrizal.rizqflow.domain.sync.PurchaseRemote
import com.roziqrizal.rizqflow.domain.sync.RecoveryKeyRemote
import com.roziqrizal.rizqflow.domain.sync.RecoveryKeyService
import com.roziqrizal.rizqflow.domain.sync.RecoveryStatusStore
import com.roziqrizal.rizqflow.domain.sync.RoomKeyProvider
import com.roziqrizal.rizqflow.domain.sync.RoomMemberKeyRedistribution
import com.roziqrizal.rizqflow.domain.sync.RoomMembershipRemote
import com.roziqrizal.rizqflow.domain.sync.SharedRoomStatus
import com.roziqrizal.rizqflow.domain.sync.SyncCursorStore
import com.roziqrizal.rizqflow.domain.sync.SyncCoordinator
import com.roziqrizal.rizqflow.domain.sync.SyncRemote
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
import com.roziqrizal.rizqflow.sync.DeviceId
import com.roziqrizal.rizqflow.sync.DeviceName
import com.roziqrizal.rizqflow.sync.ServerLink
import com.roziqrizal.rizqflow.sync.ServerSession
import com.roziqrizal.rizqflow.sync.TokenRefresher
import java.util.UUID

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
    private val syncRemote: SyncRemote,
    val roomMembershipRemote: RoomMembershipRemote,
    val purchaseRemote: PurchaseRemote,
    /** Daftar perangkat dan "Keluar dari semua perangkat" (Tahap 14); sudah dibungkus [serverSession]. */
    val deviceRemote: DeviceRemote,
    private val syncCursorStore: SyncCursorStore,
    /** Kunci akun di Keystore (Tahap 13), satu per akun; dasar kode pemulihan. */
    val accountKeyProvider: AccountKeyProvider,
    /** Salinan kunci akun terbungkus kode pemulihan di server; sudah dibungkus [serverSession]. */
    private val recoveryRemote: RecoveryKeyRemote,
    /** Penanda NON-RAHASIA "kode pemulihan sudah diatur" per akun (tidak pernah memuat kode atau kunci). */
    private val recoveryStatusStore: RecoveryStatusStore,
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

    /** Sync push/pull ruang keluarga, hanya transaksi (Tahap 9, mesin Tahap 2) — lihat [SyncCoordinator]. */
    val sync = SyncCoordinator(
        transactions = local.transactions,
        rooms = local.rooms,
        accounts = local.accounts,
        roomKeys = roomKeyProvider,
        syncRemote = syncRemote,
        codec = JsonTransactionSyncCodec(),
        apiTokenStore = apiTokenStore,
        cursorStore = syncCursorStore,
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
        onFirstTimeConfigured = { local.syncBootstrap.markAllDirty() },
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
     * Keluarkan anggota (Tahap 9): rotasi kunci ruang lalu siapkan undangan (QR) baru untuk anggota
     * yang tetap — lihat [RoomMemberKeyRedistribution]. Tidak mengubah keanggotaan di server.
     */
    val memberRemoval = RoomMemberKeyRedistribution(roomKeyProvider)

    /** Memutar token API sebelum kedaluwarsa (Tahap 12); satu per ruang kerja, jadi satu Mutex per akun. */
    val tokenRefresher = TokenRefresher(serverSession, accountRemote)

    /**
     * Sinkron otomatis (Tahap 9): dipasang ke [SyncCoordinator.syncNow] sekali per ruang kerja. Repositori transaksi
     * memanggilnya setelah pengeluaran di ruang bersama tersimpan; [syncSharedRoomsOnOpen] dipanggil saat
     * ruang kerja dibuka. Galat hanya dicatat, tidak pernah sampai ke pemanggil penyimpanan.
     */
    private fun connectAutoSync(deviceId: String) {
        local.autoSync.attach(
            sync = { roomId -> sync.syncNow(roomId, deviceId) },
            log = { message -> Log.w(TAG, "Sync otomatis: $message") },
        )
    }

    /** Sinkron semua ruang bersama saat ruang kerja dibuka. Tidak menunggu hasilnya; tombol manual tetap fallback. */
    fun syncSharedRoomsOnOpen() = local.autoSync.onWorkspaceOpened()

    /**
     * Menandai ruang bersama ACTIVE setelah pendaftaran anggota di server berhasil (undangan QR diterima, atau
     * pemilik membuka Ruang Keluarga). Tanpa baris ACTIVE, [syncSharedRoomsOnOpen] dan pemicu simpan tidak jalan.
     */
    val sharedRoomActivation = SharedRoomActivation(local.syncStore)

    override fun close() = local.close()

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
            val workspace = AccountWorkspace(
                local = LocalLedger.open(context, accountId),
                purchases = PurchaseStore.shared(context),
                accountRemote = session.guard(RetrofitAccountRemote(apiService, DeviceName.read())),
                apiTokenStore = tokenStore,
                serverSession = session,
                roomKeyProvider = AndroidKeystoreRoomKeyProvider(context),
                syncRemote = session.guard(RetrofitSyncRemote(apiService)),
                roomMembershipRemote = session.guard(RetrofitRoomMembershipRemote(apiService)),
                purchaseRemote = session.guard(RetrofitPurchaseRemote(apiService)),
                deviceRemote = session.guard(RetrofitDeviceRemote(apiService)),
                syncCursorStore = SharedPrefsSyncCursorStore(context),
                accountKeyProvider = AndroidKeystoreAccountKeyProvider(context, accountId),
                recoveryRemote = session.guard(RetrofitRecoveryKeyRemote(apiService)),
                recoveryStatusStore = SharedPrefsRecoveryStatusStore(context, accountId),
            )
            workspace.connectAutoSync(DeviceId.read(context))
            return workspace
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
            return ServerLink(session, session.guard(RetrofitAccountRemote(RizqflowApiClient.create(resolveBaseUrl()))))
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
