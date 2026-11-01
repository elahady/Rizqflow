package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.model.RoomId

/**
 * Tipe-tipe pendukung sync otomatis (Tahap 6 sync-desain.md bagian H; keputusan pemilik #14 dan #15). Semuanya murni:
 * tidak ada Android, jaringan, atau jam nyata di sini, sehingga [AutoSyncOrchestrator] bisa diuji dengan bagian palsu
 * dan waktu virtual.
 */

/** Apa yang meminta sync. Hanya [MANUAL] yang mengabaikan jeda ([SyncBackoff]) dan syarat jaringan. */
enum class AutoSyncTrigger {
    /** Aplikasi masuk ke depan (setelah transaksi berulang dijalankan dan token diperbarui). Jeda minimum antar-pemicu. */
    FOREGROUND,

    /** Sambung ulang berhasil atau langganan Sync baru dimiliki: halangan lama dicabut, tanpa jeda minimum. */
    RESUME,

    /** Debounce setelah penyimpanan lokal yang menambah baris kotor. */
    LOCAL_CHANGE,

    /** Percobaan ulang setelah jeda [SyncBackoff] habis, hanya selama aplikasi di depan. */
    RETRY,

    /** Satu upaya terbaik saat aplikasi masuk latar, dibatasi [AutoSyncConfig.backgroundBudgetMs]. */
    BACKGROUND,

    /** Tombol "Sinkronkan sekarang". */
    MANUAL,
}

/** Angka-angka kebijakan; bawaannya adalah keputusan bagian H sync-desain.md, parameter supaya tes tak menunggu lama. */
data class AutoSyncConfig(
    /** Jarak minimum antara dua run yang dipicu [AutoSyncTrigger.FOREGROUND]. */
    val foregroundMinGapMs: Long = 60_000L,
    /** Diam sebelum run dipicu [AutoSyncTrigger.LOCAL_CHANGE]; penyimpanan baru mengulang hitungan. */
    val debounceMs: Long = 10_000L,
    val backoffInitialMs: Long = 30_000L,
    val backoffMaxMs: Long = 900_000L,
    /** Batas atas `Retry-After` dari server. */
    val retryAfterMaxMs: Long = 3_600_000L,
    /** Batas waktu upaya saat masuk latar ("sekitar 5 detik"). */
    val backgroundBudgetMs: Long = 5_000L,
)

/** Hasil sync satu cakupan (pribadi, atau satu ruang bersama), dipetakan dari hasil koordinator masing-masing. */
sealed interface ScopeSyncOutcome {
    /** [pushWaitsForRecovery] true bila push pribadi ditahan gerbang kode pemulihan (keputusan #14), bukan kegagalan. */
    data class Done(val pushed: Int, val applied: Int, val pushWaitsForRecovery: Boolean = false) : ScopeSyncOutcome

    data class Failed(val error: RemoteError) : ScopeSyncOutcome

    /** Token API tidak ada: tidak ada permintaan yang dikirim. */
    data object NotSignedIn : ScopeSyncOutcome
}

fun PersonalSyncResult.toScopeOutcome(): ScopeSyncOutcome = when (this) {
    is PersonalSyncResult.Success -> ScopeSyncOutcome.Done(pushed, applied, pushWaitsForRecovery = pushGated)
    is PersonalSyncResult.Failed -> ScopeSyncOutcome.Failed(error)
    PersonalSyncResult.NotSignedIn -> ScopeSyncOutcome.NotSignedIn
}

fun SyncResult.toScopeOutcome(): ScopeSyncOutcome = when (this) {
    is SyncResult.Success -> ScopeSyncOutcome.Done(pushed, applied)
    is SyncResult.Failed -> ScopeSyncOutcome.Failed(error)
    SyncResult.NotSignedIn -> ScopeSyncOutcome.NotSignedIn
}

/**
 * Langkah-langkah satu run, berurutan: cakupan pribadi, lalu tiap ruang bersama aktif. Implementasi produksi
 * (`AccountWorkspace`) memanggil `PersonalSyncCoordinator` dan `SyncCoordinator`, yang ruang bersamanya WAJIB lewat
 * `SharedRoomAutoSync.withRoomLock` supaya tidak berpapasan dengan tombol "Sinkronkan sekarang" milik ruang itu.
 * Tidak boleh melempar kegagalan jaringan; [AutoSyncOrchestrator] tetap menjaga bila ada yang lolos.
 */
interface AutoSyncSteps {
    suspend fun personal(): ScopeSyncOutcome

    /** Ruang bersama berstatus ACTIVE saat ini. */
    suspend fun activeSharedRooms(): List<RoomId>

    suspend fun sharedRoom(roomId: RoomId): ScopeSyncOutcome
}

/**
 * Gerbang sebelum SATU permintaan pun dikirim (keputusan #14, "Penguncian Sync di klien"): tanpa token server atau
 * tanpa langganan Sync, tidak ada request. Dibaca tiap pemicu, bukan di-cache, supaya paket yang berubah langsung berlaku.
 */
interface AutoSyncGate {
    fun apiToken(): String?

    fun syncEntitled(): Boolean
}

/** Keadaan jaringan menurut platform, tanpa membedakan jenis selain yang berpengaruh pada saklar hanya Wi-Fi. */
enum class NetworkKind {
    NONE,

    /** Berbayar per data (seluler, atau Wi-Fi yang ditandai berbayar). */
    METERED,

    /** Tidak berbayar (Wi-Fi biasa, ethernet). */
    UNMETERED,
}

enum class NetworkCheck { OK, NO_NETWORK, WIFI_ONLY_BLOCKED }

/** Keputusan murni saklar "hanya Wi-Fi" (keputusan #15). */
object AutoSyncNetworkPolicy {
    fun check(wifiOnly: Boolean, network: NetworkKind): NetworkCheck = when (network) {
        NetworkKind.NONE -> NetworkCheck.NO_NETWORK
        NetworkKind.UNMETERED -> NetworkCheck.OK
        NetworkKind.METERED -> if (wifiOnly) NetworkCheck.WIFI_ONLY_BLOCKED else NetworkCheck.OK
    }
}

/** Dibaca tiap pemicu otomatis; sync manual mengabaikannya. */
fun interface AutoSyncNetwork {
    fun check(): NetworkCheck
}

/** Pengaturan sync yang dipilih pengguna, per perangkat. */
interface AutoSyncSettings {
    /**
     * Sync otomatis hanya saat jaringan tidak berbayar. Bawaan AKTIF (usulan Claude, belum dikonfirmasi pemilik):
     * lebih hemat kuota, dan data tetap aman di ponsel; yang tertunda hanya tersalin ke server.
     */
    var wifiOnly: Boolean
}

/** Fase sync untuk ditampilkan. Pesan Indonesianya ada di `:app` supaya domain bebas dari teks antarmuka. */
enum class AutoSyncPhase {
    IDLE,
    SYNCING,

    /** Server belum terjangkau atau sedang gangguan; dicoba lagi otomatis setelah jeda. */
    BACKOFF,

    /** Tidak ada jaringan. */
    OFFLINE,

    /** Ada jaringan tetapi berbayar, dan saklar hanya Wi-Fi aktif. */
    WIFI_ONLY,

    /** Token server tidak ada atau ditolak (401): sync berhenti sampai sambung ulang. */
    NEEDS_RECONNECT,

    /** Butuh langganan Sync (tidak dimiliki, atau server menjawab 403). */
    NEEDS_SUBSCRIPTION,
}

/**
 * [lastSyncedAtMs] jam dinding run sukses terakhir di proses ini (null = belum ada; sengaja tidak disimpan ke disk).
 * [pushWaitsForRecovery] true bila run terakhir menahan push pribadi karena kode pemulihan belum diatur.
 */
data class AutoSyncState(
    val phase: AutoSyncPhase = AutoSyncPhase.IDLE,
    val lastSyncedAtMs: Long? = null,
    val pushWaitsForRecovery: Boolean = false,
)

/** Hasil satu run bagi pemanggil yang menunggu (tombol manual). */
sealed interface AutoSyncReport {
    /** [roomsSkipped] = ruang bersama yang ditolak server (403) tanpa menghentikan cakupan lain. */
    data class Synced(
        val pushed: Int,
        val applied: Int,
        val pushWaitsForRecovery: Boolean,
        val roomsSkipped: Int = 0,
    ) : AutoSyncReport

    data class Failed(val error: RemoteError) : AutoSyncReport

    /** Tidak dimiliki, atau server menjawab 403: pemanggil membuka ajakan berlangganan (`SyncGate`). */
    data object NeedsSubscription : AutoSyncReport

    data object NotSignedIn : AutoSyncReport
}
