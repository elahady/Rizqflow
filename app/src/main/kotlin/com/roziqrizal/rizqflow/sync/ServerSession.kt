package com.roziqrizal.rizqflow.sync

import com.roziqrizal.rizqflow.domain.sync.AccountRemote
import com.roziqrizal.rizqflow.domain.sync.ApiAccount
import com.roziqrizal.rizqflow.domain.sync.ApiTokenStore
import com.roziqrizal.rizqflow.domain.sync.PurchaseRemote
import com.roziqrizal.rizqflow.domain.sync.RefreshedToken
import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import com.roziqrizal.rizqflow.domain.sync.RoomMembershipRemote
import com.roziqrizal.rizqflow.domain.sync.SyncRemote
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Satu-satunya tempat yang memegang keadaan sesi server satu akun (Tahap 12, token API yang
 * kedaluwarsa dan bisa dicabut): token dan masa berlakunya, serta keadaan "perlu sambungkan ulang".
 * Dirakit per `AccountWorkspace` bersama [ApiTokenStore] akun itu, sehingga berganti akun otomatis
 * berganti sesi.
 *
 * Aturan inti: jawaban 401 dari endpoint terlindungi mana pun (sync, ruang keluarga, klaim pembelian,
 * hapus akun, putar token, keluar) berarti sesi server berakhir atau dicabut. Penanganannya TIDAK
 * tersebar di tiap pemanggil: setiap remote dibungkus lewat [guard], dan pembungkus itu memanggil
 * [onUnauthorized] sehingga pemanggil cukup membaca [RemoteResult] seperti biasa. Remote baru wajib
 * dirakit lewat [guard] di `AccountWorkspace`; di situlah satu-satunya tempat menyambungkannya.
 *
 * OFFLINE-FIRST: kelas ini tidak menyentuh data lokal, paket dari Google Play, maupun UI. Yang berhenti
 * hanya sync dan fitur server, sampai tersambung ulang. Jaringan mati ([com.roziqrizal.rizqflow.domain.sync.RemoteError.Unreachable])
 * dan kegagalan lain selain 401 tidak pernah mencabut apa pun di sini.
 *
 * [needsReconnect] bernilai true bila tidak ada token server di perangkat ini. Itu hanya bermakna untuk
 * akun Google (akun lokal memang tidak punya akun server); UI yang menyaringnya per jenis akun. Token
 * yang HILANG (belum pernah berhasil masuk ke server, mis. saat itu offline) dan token yang BERAKHIR
 * (401) sengaja memberi keadaan yang sama, karena jalan keluarnya sama: masuk Google ulang.
 */
class ServerSession(
    private val store: ApiTokenStore,
    /** Jam ponsel; disuntikkan supaya waktu terima token (jeda minimum putar) bisa diuji. */
    private val clock: () -> Instant = { Instant.now() },
    /** Tempat menulis token ke penyimpanan: commit() sinkron tidak boleh berjalan di thread utama. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Batas waktu mencabut token yang terbuang ([connect] yang keburu ditutup oleh keluar akun). */
    private val revokeOrphanTimeoutMillis: Long = 5_000L,
) {
    private val lock = Any()

    /**
     * Dinaikkan setiap token DIBERSIHKAN (keluar, 401, hapus akun). [connect] mencatatnya sebelum menunggu server
     * dan hanya menyimpan token bila angkanya belum berubah: masuk yang jawabannya baru datang SETELAH pengguna
     * keluar tidak boleh menghidupkan lagi sesi yang sudah ditutup.
     */
    private var epoch = 0L

    private val _needsReconnect = MutableStateFlow(store.read() == null)
    val needsReconnect: StateFlow<Boolean> = _needsReconnect.asStateFlow()

    private val _connecting = MutableStateFlow(false)

    /** True selama [connect] menunggu server; UI memakainya supaya tidak menampilkan "sambungkan ulang" saat masuk sedang berjalan. */
    val connecting: StateFlow<Boolean> = _connecting.asStateFlow()

    fun token(): String? = store.read()

    fun expiresAt(): Instant? = store.expiresAt()

    /** Waktu token yang tersimpan diterima (jeda minimum putar); null bila tidak diketahui. */
    fun lastSavedAt(): Instant? = store.lastSavedAt()

    /** Apakah perangkat ini pernah punya token server untuk akun ini (tetap true setelah sesi berakhir; lihat [ApiTokenStore.hasEverHeldToken]). */
    fun hasEverHeldToken(): Boolean = store.hasEverHeldToken()

    /**
     * Masuk ke server dengan ID token Google yang baru diambil: dipakai bersama oleh masuk pertama dan
     * sambungkan ulang, jadi hanya ada satu jalur menyimpan token. Berhasil berarti token dan masa
     * berlakunya tersimpan dan keadaan "perlu sambungkan ulang" turun; gagal tidak mengubah apa pun.
     *
     * TIDAK DIBATALKAN DI TENGAH JALAN, seperti `TokenRefresher`: begitu server menerima ID token (sekali pakai),
     * jawabannya harus sampai ke penyimpanan. Pemanggil di Compose bisa dibatalkan kapan saja oleh perubahan
     * kunci efek (ID token yang baru dikonsumsi mengubah state), dan kalau permintaan ikut batal, server sudah
     * mencatat sesi sementara ponsel tidak pernah menerima tokennya. Penulisan ke disk berjalan di [ioDispatcher].
     *
     * Bila pengguna keluar selama menunggu (lihat [epoch]), token baru dibuang dan dicabut sebisanya, dan hasilnya
     * dilaporkan sebagai [RemoteError.Unreachable] (tidak tersambung).
     */
    suspend fun connect(remote: AccountRemote, idToken: String, deviceId: String): RemoteResult<ApiAccount> =
        withContext(NonCancellable + ioDispatcher) {
            val startedEpoch = synchronized(lock) { epoch }
            _connecting.value = true
            try {
                val result = remote.loginWithGoogle(idToken, deviceId)
                if (result is RemoteResult.Success) {
                    if (onLoggedIn(result.value, clock(), expectedEpoch = startedEpoch)) {
                        result
                    } else {
                        revokeOrphan(remote, result.value.apiToken)
                        RemoteResult.Failure(RemoteError.Unreachable)
                    }
                } else {
                    result
                }
            } finally {
                _connecting.value = false
            }
        }

    /** True bila token disimpan; false bila sesi sudah ditutup sejak [expectedEpoch] dicatat (token dibuang). */
    private fun onLoggedIn(account: ApiAccount, at: Instant, expectedEpoch: Long): Boolean = synchronized(lock) {
        if (epoch != expectedEpoch) return false
        store.save(account.apiToken, account.expiresAt, at)
        _needsReconnect.value = false
        true
    }

    private suspend fun revokeOrphan(remote: AccountRemote, orphanToken: String) {
        try {
            withTimeoutOrNull(revokeOrphanTimeoutMillis) { remote.logout(orphanToken) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Upaya terbaik saja: token yatim berakhir sendiri pada masa berlakunya.
        }
    }

    /**
     * Mencatat token hasil putar. Hanya disimpan bila yang tersimpan MASIH [previousToken] yang dikirim
     * ke server: bila sementara itu pengguna keluar atau masuk ulang, token pengganti dibuang supaya
     * tidak menghidupkan lagi sesi yang sudah ditutup.
     *
     * Penulisan ke penyimpanan dilaporkan, tidak pernah diam: token lama SUDAH dicabut server begitu putar
     * berhasil, jadi bila token baru gagal ditulis ke disk ([RotationOutcome.NOT_PERSISTED]) pemanggil
     * harus tahu. Token baru tetap dipakai proses ini (penyimpanan menyimpannya di memori) dan sesi tidak
     * dinyatakan berakhir; yang hilang hanya kemampuan bertahan setelah proses mati, dan pada pembukaan
     * berikutnya jawaban 401 yang akan menaikkan "perlu sambungkan ulang".
     */
    fun onRotated(previousToken: String, refreshed: RefreshedToken, at: Instant): RotationOutcome = synchronized(lock) {
        if (store.read() != previousToken) return RotationOutcome.SUPERSEDED
        val persisted = store.save(refreshed.apiToken, refreshed.expiresAt, at)
        _needsReconnect.value = false
        if (persisted) RotationOutcome.SAVED else RotationOutcome.NOT_PERSISTED
    }

    /**
     * Server menolak [rejectedToken] dengan 401. Token dibersihkan dan [needsReconnect] naik, TAPI hanya
     * bila yang tersimpan masih token itu: permintaan lama yang terbang saat token diputar bisa menerima
     * 401 untuk token yang sudah diganti, dan itu tidak boleh membuang token baru yang sah. Hasil true
     * berarti sesi benar-benar diakhiri oleh panggilan ini.
     */
    fun onUnauthorized(rejectedToken: String): Boolean = synchronized(lock) {
        if (store.read() != rejectedToken) return false
        store.clear()
        epoch++
        _needsReconnect.value = true
        true
    }

    /** Membersihkan token lokal tanpa menyentuh server. */
    fun clearLocal() {
        synchronized(lock) {
            store.clear()
            epoch++
            _needsReconnect.value = true
        }
    }

    /**
     * Membaca token lalu membersihkannya SEBAGAI SATU LANGKAH di bawah kunci yang sama dengan [onRotated] dan
     * [onUnauthorized]. Membaca ([token]) lalu [clearLocal] sebagai dua langkah terpisah membuka celah: putar token
     * yang selesai di antaranya menulis token baru yang lalu ikut terhapus, sementara yang dicabut di server
     * adalah token lama yang sudah mati; token baru tertinggal sebagai yatim di server. Dipakai keluar akun.
     */
    fun takeToken(): String? = synchronized(lock) {
        val token = store.read()
        store.clear()
        epoch++
        _needsReconnect.value = true
        token
    }

    /**
     * Akun server baru saja dihapus: token dan catatan "pernah punya" ikut dilupakan, karena tidak ada lagi
     * akun server yang bisa dihapus atau disambungkan ulang.
     */
    fun onAccountDeleted() {
        synchronized(lock) {
            store.forgetAccount()
            epoch++
            _needsReconnect.value = true
        }
    }

    // --- Satu tempat merakit pembungkus 401 untuk semua remote terlindungi ---

    /** Dari [RemoteResult.Failure] 401 mana pun yang membawa token ini: sesi berakhir (lihat [onUnauthorized]). */
    internal fun <T> observe(token: String, result: RemoteResult<T>): RemoteResult<T> {
        if (result is RemoteResult.Failure && result.error == RemoteError.Unauthorized) onUnauthorized(token)
        return result
    }

    fun guard(remote: AccountRemote): AccountRemote = GuardedAccountRemote(remote, this)

    fun guard(remote: SyncRemote): SyncRemote =
        GuardedSyncRemote(remote, this)

    fun guard(remote: RoomMembershipRemote): RoomMembershipRemote =
        GuardedRoomMembershipRemote(remote, this)

    fun guard(remote: PurchaseRemote): PurchaseRemote =
        GuardedPurchaseRemote(remote, this)
}

/** Nasib token hasil putar yang diterima dari server, lihat [ServerSession.onRotated]. */
enum class RotationOutcome {
    /** Tersimpan di penyimpanan tetap. */
    SAVED,

    /** Dipakai proses ini tetapi GAGAL ditulis ke disk: pemanggil harus melaporkannya, bukan mendiamkannya. */
    NOT_PERSISTED,

    /** Dibuang: token yang tersimpan sudah bukan yang dikirim (pengguna keluar atau masuk ulang selama putar). */
    SUPERSEDED,
}

/**
 * Apakah baris "Sambungkan ulang ke server" ditawarkan di menu Lainnya. Hanya akun Google (satu-satunya yang
 * punya akun server) di data aslinya, dan hanya bila token server hilang atau sudah berakhir: akun lokal,
 * akun uji, dan mode demo tidak pernah melihatnya.
 */
fun shouldOfferReconnect(google: Boolean, demo: Boolean, needsReconnect: Boolean): Boolean =
    google && !demo && needsReconnect

/** Rencana "Hapus akun" (menu Lainnya); keputusan murni supaya bisa diuji tanpa Compose. */
sealed interface DeleteAccountPlan {
    /** Ada token: hapus akun di server dengan token ini, lalu keluar. */
    data class DeleteOnServer(val token: String) : DeleteAccountPlan

    /**
     * Akun ini PERNAH punya akun server tetapi tokennya hilang (sesi berakhir lewat 401, atau dibersihkan saat
     * keluar). Tidak boleh keluar diam-diam: akun server dan langganannya masih ada. Minta sambungkan ulang dulu.
     */
    data object ReconnectFirst : DeleteAccountPlan

    /** Memang tidak ada akun server untuk dihapus (akun lokal, atau Google yang tak pernah berhasil tersambung): langsung keluar. */
    data object SignOutOnly : DeleteAccountPlan
}

fun planDeleteAccount(google: Boolean, token: String?, everHeldToken: Boolean): DeleteAccountPlan = when {
    token != null -> DeleteAccountPlan.DeleteOnServer(token)
    google && everHeldToken -> DeleteAccountPlan.ReconnectFirst
    else -> DeleteAccountPlan.SignOutOnly
}

/** Sesi server dan remote akun yang sama, untuk keluar akun ([ServerSignOut]). */
class ServerLink(val session: ServerSession, val remote: AccountRemote)

/**
 * Keluar akun terhadap server untuk akun yang SEDANG masuk. Di mode demo ruang kerja aktifnya milik akun demo,
 * jadi sesi yang dibersihkan dan dicabut adalah milik akun asli ([realAccountLink], dibuat hanya bila perlu),
 * bukan milik ruang kerja demo yang tidak punya token. [demoActive] HARUS dibaca sebelum keadaan demo diubah.
 */
fun ServerSignOut.signOutAccount(demoActive: Boolean, workspaceLink: ServerLink, realAccountLink: () -> ServerLink): Job? {
    val link = if (demoActive) realAccountLink() else workspaceLink
    return signOut(link.session, link.remote)
}

fun RemoteResult<*>.isUnauthorized(): Boolean = this is RemoteResult.Failure && error == RemoteError.Unauthorized

/**
 * Menjalankan [call] (yang membaca token sendiri) dan, bila dijawab 401 padahal token sementara itu DIPUTAR
 * (permintaan membawa token lama T1, putar sudah menyimpan T2), mengulanginya SEKALI dengan token baru. Itu
 * bukan sesi berakhir: 401 untuk T1 tidak membersihkan T2 ([ServerSession.onUnauthorized]). Token yang tidak
 * berubah berarti 401 itu nyata dan tidak diulang.
 */
suspend fun <R> ServerSession.retryOnceIfRotated(isUnauthorized: (R) -> Boolean, call: suspend () -> R): R {
    val before = token()
    val result = call()
    if (!isUnauthorized(result)) return result
    val after = token()
    return if (after != null && after != before) call() else result
}

/** "Sesi berakhir" boleh dinyatakan ke pengguna hanya bila [result] 401 DAN sesi benar-benar berakhir ([ServerSession.needsReconnect]). */
fun ServerSession.sessionEndedBy(result: RemoteResult<*>): Boolean = result.isUnauthorized() && needsReconnect.value

fun ServerSession.sessionEndedBy(error: RemoteError): Boolean = error == RemoteError.Unauthorized && needsReconnect.value
