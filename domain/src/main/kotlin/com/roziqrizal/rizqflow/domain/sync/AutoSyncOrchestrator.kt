package com.roziqrizal.rizqflow.domain.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sync otomatis (Tahap 6 sync-desain.md bagian H; keputusan pemilik #14 gerbang kode pemulihan dan #15 hanya saat
 * aplikasi di depan). SATU per `AccountWorkspace`. TANPA WorkManager atau penjadwal latar: semua pemicu berasal dari
 * aplikasi yang sedang dipakai.
 *
 * Pemicu:
 * 1. [onForeground] — aplikasi masuk ke depan; dipanggil SETELAH transaksi berulang dijalankan dan token diperbarui.
 *    Jeda minimum [AutoSyncConfig.foregroundMinGapMs] sejak run terakhir dimulai.
 * 2. [onLocalChange] — penyimpanan lokal menambah baris kotor; debounce [AutoSyncConfig.debounceMs] (penyimpanan baru
 *    mengulang hitungan). Hanya berjalan selama aplikasi di depan.
 * 3. [syncNow] — tombol "Sinkronkan sekarang": mengabaikan jeda, jeda minimum, dan saklar hanya Wi-Fi (pengguna
 *    memintanya sendiri); mereset jeda dan halangan lama. Selalu menjalankan run sendiri (menunggu run otomatis yang
 *    sedang berjalan), jadi hasilnya milik tombol itu.
 * 4. [onBackground] — satu upaya terbaik bila ada perubahan belum terkirim, [NonCancellable], dibatasi
 *    [AutoSyncConfig.backgroundBudgetMs]. Timer debounce dan percobaan ulang dibatalkan.
 * 5. [onNetworkChanged] — jaringan berubah saat aplikasi di depan ([NetworkChangeSource], didaftarkan hanya selama di
 *    depan): HANYA bila ada sync yang tertunda karena jaringan (tanpa jaringan, atau hanya-Wi-Fi di jaringan berbayar)
 *    dan jaringan kini cukup, maka satu run dipicu. Tunduk pada jeda minimum (ditunda sampai jeda habis, bukan dibuang)
 *    dan backoff seperti [onForeground].
 * Tambahan: [resume] (sambung ulang berhasil, atau langganan Sync baru dimiliki) dan percobaan ulang setelah jeda
 * (hanya selama di depan).
 *
 * **Satu penerbangan.** [runLock] adalah satu-satunya Mutex run. Pemicu OTOMATIS yang mendapati run berjalan bergabung
 * ke dalamnya (tidak memulai run kedua); bila pemicunya perubahan lokal, run itu diulang setelah selesai (debounce
 * ulang) supaya perubahan yang tiba setelah pengumpulan push tidak tertinggal.
 *
 * **Batas tunggu debounce.** Penyimpanan yang terus datang mengulang hitungan 10 detik, tetapi tidak lebih lama dari
 * [AutoSyncConfig.debounceMaxWaitMs] sejak perubahan pertama yang belum terkirim. Cakupan berurutan: pribadi, lalu tiap
 * ruang bersama aktif ([AutoSyncSteps]; ruang bersama lewat `SharedRoomAutoSync.withRoomLock` di produksi).
 *
 * **Jeda ([SyncBackoff]).** Unreachable/5xx/galat lain berlipat dua 30 dtk..15 menit; `Retry-After` (mis. 429) diikuti;
 * sukses atau sync manual mereset. 401 menghentikan sync otomatis sampai token berganti ([resume] atau sambung ulang);
 * 403 pada cakupan pribadi menghentikannya sampai [resume] dan menandai [AutoSyncPhase.NEEDS_SUBSCRIPTION] (UI membuka
 * ajakan berlangganan lewat `SyncGate`). 403 pada satu ruang bersama (mis. dikeluarkan dari ruang) hanya melewati ruang
 * itu. Kegagalan sync TIDAK PERNAH menyentuh atau memblokir data lokal: tidak ada pemanggil yang menunggu di jalur tulis.
 *
 * **Tanpa loop.** Penerapan hasil pull tidak menandai kotor (`withSyncApplying`), jadi pull tidak memicu [onLocalChange]
 * dan push sesudahnya mengirim 0 baris (dibuktikan tes `:data`).
 *
 * Seluruh waktu lewat [nowMs] (monotonik) dan [wallClockMs] (hanya untuk tampilan), debounce lewat `delay` pada [scope]:
 * tes memakai waktu virtual, tanpa tidur nyata.
 */
class AutoSyncOrchestrator(
    private val scope: CoroutineScope,
    private val steps: AutoSyncSteps,
    private val gate: AutoSyncGate,
    private val network: AutoSyncNetwork,
    private val config: AutoSyncConfig = AutoSyncConfig(),
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    private val networkChanges: NetworkChangeSource = NetworkChangeSource.None,
    private val lastSync: LastSyncStore = LastSyncStore.None,
) {
    private sealed interface Halt {
        /** Token [token] ditolak server (401). Berlaku sampai tokennya berganti. */
        data class Auth(val token: String?) : Halt

        /** Server menjawab 403 untuk cakupan pribadi. Berlaku sampai [resume] atau tombol manual. */
        data object Subscription : Halt
    }

    private sealed interface RunOutcome {
        data class Done(val pushed: Int, val applied: Int, val pushWaitsForRecovery: Boolean, val roomsSkipped: Int) : RunOutcome

        data class Failure(val error: RemoteError) : RunOutcome

        data object NotSignedIn : RunOutcome
    }

    private val runLock = Mutex()
    private val stateLock = Any()

    // Semua di bawah dijaga stateLock.
    private val backoff = SyncBackoff(config.backoffInitialMs, config.backoffMaxMs, config.retryAfterMaxMs)
    private var halt: Halt? = null
    private var foreground = false
    private var dirty = false
    private var rerunRequested = false
    private var lastRunStartMs: Long? = null
    private var debounceJob: Job? = null
    private var retryJob: Job? = null

    /** Waktu monotonik perubahan PERTAMA yang belum terkirim (dasar [AutoSyncConfig.debounceMaxWaitMs]); null bila tidak ada. */
    private var firstPendingChangeMs: Long? = null

    /** True bila pemicu otomatis terakhir tertahan jaringan dan belum ada run sejak itu (dasar [onNetworkChanged]). */
    private var pendingNetwork = false
    private var networkWatch: AutoCloseable? = null
    private var gapJob: Job? = null

    private val _state = MutableStateFlow(AutoSyncState(lastSyncedAtMs = sanitizeLastSync(runCatching { lastSync.read() }.getOrNull(), wallClockMs())))
    val state: StateFlow<AutoSyncState> = _state.asStateFlow()

    // ---- Pemicu ----

    /** Aplikasi masuk ke depan. Mengembalikan job run-nya (tes menunggunya; UI boleh mengabaikan). */
    fun onForeground(): Job {
        synchronized(stateLock) {
            foreground = true
            startNetworkWatch()
        }
        return scope.launch { runAutomatic(AutoSyncTrigger.FOREGROUND) }
    }

    /**
     * Jaringan berubah saat di depan. Murah: tidak melakukan apa pun kecuali ada sync yang tertunda karena jaringan; run-nya
     * sendiri menilai ulang jaringan ([AutoSyncNetwork]) dan tunduk pada jeda minimum dan backoff.
     */
    fun onNetworkChanged() {
        val relevant = synchronized(stateLock) { foreground && pendingNetwork }
        if (!relevant) return
        scope.launch { runAutomatic(AutoSyncTrigger.NETWORK_CHANGE) }
    }

    /** Ruang kerja ditutup: lepas pendengar jaringan dan semua timer. Tidak memulai upaya latar. */
    fun shutdown() {
        synchronized(stateLock) {
            foreground = false
            stopNetworkWatch()
            debounceJob?.cancel()
            debounceJob = null
            retryJob?.cancel()
            retryJob = null
            gapJob?.cancel()
            gapJob = null
        }
    }

    // Dipanggil di bawah stateLock. Idempoten: onForeground boleh dipanggil berulang tanpa ke latar di antaranya.
    private fun startNetworkWatch() {
        if (networkWatch != null) return
        networkWatch = try {
            networkChanges.watch { onNetworkChanged() }
        } catch (e: Exception) {
            log("pendengar jaringan gagal dipasang: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun stopNetworkWatch() {
        val watch = networkWatch ?: return
        networkWatch = null
        try {
            watch.close()
        } catch (e: Exception) {
            log("pendengar jaringan gagal dilepas: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Penyimpanan lokal menambah baris kotor. Murah dan aman dipanggil dari thread mana pun, juga di dalam transaksi
     * Room (hanya menandai dan mengatur timer; tidak menyentuh jaringan atau database).
     */
    fun onLocalChange() {
        synchronized(stateLock) {
            dirty = true
            val now = nowMs()
            val first = firstPendingChangeMs ?: now.also { firstPendingChangeMs = it }
            if (!foreground) return
            debounceJob?.cancel()
            // Diam 10 detik, tetapi tidak melewati batas tunggu maksimum sejak perubahan pertama yang belum terkirim.
            val wait = minOf(config.debounceMs, maxOf(0L, config.debounceMaxWaitMs - (now - first)))
            debounceJob = scope.launch {
                delay(wait)
                // Dilepas SEBELUM run: penyimpanan baru selama run tidak boleh membatalkan run yang sedang jalan.
                val self = currentCoroutineContext()[Job]
                synchronized(stateLock) {
                    if (debounceJob === self) debounceJob = null
                }
                runAutomatic(AutoSyncTrigger.LOCAL_CHANGE)
            }
        }
    }

    /** Aplikasi masuk latar: satu upaya terbaik bila ada yang belum terkirim; selebihnya berhenti. */
    fun onBackground() {
        val attempt = synchronized(stateLock) {
            foreground = false
            stopNetworkWatch()
            debounceJob?.cancel()
            debounceJob = null
            retryJob?.cancel()
            retryJob = null
            gapJob?.cancel()
            gapJob = null
            dirty
        }
        if (!attempt) return
        scope.launch {
            withContext(NonCancellable) {
                withTimeoutOrNull(config.backgroundBudgetMs) { runAutomatic(AutoSyncTrigger.BACKGROUND) }
            }
        }
    }

    /** Token server berganti (masuk atau sambung ulang) atau langganan Sync baru dimiliki: cabut halangan dan coba lagi. */
    fun resume(): Job {
        synchronized(stateLock) {
            halt = null
            backoff.reset()
        }
        return scope.launch { runAutomatic(AutoSyncTrigger.RESUME) }
    }

    /**
     * Tombol "Sinkronkan sekarang". Tidak ikut dibatalkan bila layarnya ditutup (run sudah idempoten, tetapi membatalkan
     * di tengah hanya membuang pekerjaan). Mengembalikan hasil run-nya sendiri.
     */
    suspend fun syncNow(): AutoSyncReport = withContext(NonCancellable) {
        runLock.withLock {
            synchronized(stateLock) {
                backoff.reset()
                halt = null
            }
            if (gate.apiToken() == null) {
                setPhase(AutoSyncPhase.NEEDS_RECONNECT)
                return@withLock AutoSyncReport.NotSignedIn
            }
            if (!gate.syncEntitled()) {
                setPhase(AutoSyncPhase.NEEDS_SUBSCRIPTION)
                return@withLock AutoSyncReport.NeedsSubscription
            }
            execute(AutoSyncTrigger.MANUAL)
        }
    }

    // ---- Run otomatis ----

    private suspend fun runAutomatic(trigger: AutoSyncTrigger) {
        if (!preflight(trigger)) return
        // Bergabung ke run yang berjalan: tidak memulai run kedua.
        if (!runLock.tryLock()) {
            if (trigger == AutoSyncTrigger.LOCAL_CHANGE) synchronized(stateLock) { rerunRequested = true }
            return
        }
        val report = try {
            execute(trigger)
        } finally {
            runLock.unlock()
        }
        val rerun = synchronized(stateLock) {
            (rerunRequested && report is AutoSyncReport.Synced).also { rerunRequested = false }
        }
        if (rerun) onLocalChange()
    }

    /** @return true bila run boleh dimulai. Bila tidak, [AutoSyncState.phase] menjelaskan alasannya (kecuali jeda minimum). */
    private fun preflight(trigger: AutoSyncTrigger): Boolean {
        val token = gate.apiToken()
        if (token == null) {
            setPhase(AutoSyncPhase.NEEDS_RECONNECT)
            return false
        }
        if (!gate.syncEntitled()) {
            setPhase(AutoSyncPhase.NEEDS_SUBSCRIPTION)
            return false
        }
        val now = nowMs()
        val blockedPhase: AutoSyncPhase? = synchronized(stateLock) {
            when (val current = halt) {
                is Halt.Auth -> if (current.token == token) AutoSyncPhase.NEEDS_RECONNECT else { halt = null; null }
                Halt.Subscription -> AutoSyncPhase.NEEDS_SUBSCRIPTION
                null -> null
            } ?: when {
                // Perubahan lokal dan percobaan ulang hanya berarti selama aplikasi di depan (keputusan #15).
                (trigger == AutoSyncTrigger.LOCAL_CHANGE || trigger == AutoSyncTrigger.RETRY || trigger == AutoSyncTrigger.NETWORK_CHANGE) && !foreground -> AutoSyncPhase.IDLE
                backoff.isBlocked(now) -> AutoSyncPhase.BACKOFF
                else -> null
            }
        }
        if (blockedPhase != null) {
            // IDLE di sini = berhenti diam-diam (di latar), bukan perubahan tampilan.
            if (blockedPhase != AutoSyncPhase.IDLE) setPhase(blockedPhase)
            return false
        }
        if (trigger == AutoSyncTrigger.NETWORK_CHANGE) {
            // Kabar jaringan hanya berarti bila ada yang menunggu jaringan; selain itu run-nya sudah/akan dipicu pemicu lain.
            if (!synchronized(stateLock) { pendingNetwork }) return false
        }
        if (trigger == AutoSyncTrigger.FOREGROUND || trigger == AutoSyncTrigger.NETWORK_CHANGE) {
            val last = synchronized(stateLock) { lastRunStartMs }
            if (last != null && now - last < config.foregroundMinGapMs) {
                // Kabar jaringan yang jatuh di dalam jeda ditunda sampai jeda habis (bukan dibuang): tanpa ini sync yang
                // tertunda jaringan tidak punya pemicu lagi sampai ada perubahan jaringan berikutnya.
                if (trigger == AutoSyncTrigger.NETWORK_CHANGE) scheduleGapRetry(config.foregroundMinGapMs - (now - last))
                return false
            }
        }
        // Tertahan jaringan: ingat, supaya perubahan jaringan berikutnya (onNetworkChanged) memicu run. BACKGROUND tidak
        // ikut: di latar tidak ada pendengar jaringan.
        val check = network.check()
        if (check != NetworkCheck.OK && trigger != AutoSyncTrigger.BACKGROUND) {
            synchronized(stateLock) { pendingNetwork = true }
        }
        when (check) {
            NetworkCheck.OK -> Unit
            NetworkCheck.NO_NETWORK -> { setPhase(AutoSyncPhase.OFFLINE); return false }
            NetworkCheck.WIFI_ONLY_BLOCKED -> { setPhase(AutoSyncPhase.WIFI_ONLY); return false }
        }
        return true
    }

    // ---- Satu run (pemanggil memegang runLock) ----

    private suspend fun execute(trigger: AutoSyncTrigger): AutoSyncReport {
        val token = gate.apiToken()
        synchronized(stateLock) {
            lastRunStartMs = nowMs()
            // Perubahan yang tiba SELAMA run menyalakan lagi bendera ini; yang sudah ada sekarang ikut terkirim run ini.
            dirty = false
            firstPendingChangeMs = null
            pendingNetwork = false
        }
        setPhase(AutoSyncPhase.SYNCING)

        val outcome = try {
            runScopes()
        } catch (e: CancellationException) {
            // Dibatalkan di tengah (batas waktu latar, atau ruang kerja ditutup): belum tentu terkirim, jangan berbohong.
            synchronized(stateLock) { dirty = true }
            _state.update { it.copy(phase = AutoSyncPhase.IDLE) }
            throw e
        }

        return when (outcome) {
            is RunOutcome.Done -> {
                synchronized(stateLock) {
                    backoff.reset()
                    // retryJob yang sedang menjalankan run ini sudah dilepas sebelum run dimulai (lihat scheduleRetry).
                    retryJob?.cancel()
                    retryJob = null
                }
                val finishedAt = wallClockMs()
                // Bertahan setelah proses mati; gagal menyimpan tidak boleh menggagalkan sync.
                try {
                    lastSync.write(finishedAt)
                } catch (e: Exception) {
                    log("waktu sinkron terakhir gagal disimpan: ${e.javaClass.simpleName}")
                }
                _state.value = AutoSyncState(AutoSyncPhase.IDLE, finishedAt, outcome.pushWaitsForRecovery)
                AutoSyncReport.Synced(outcome.pushed, outcome.applied, outcome.pushWaitsForRecovery, outcome.roomsSkipped)
            }

            RunOutcome.NotSignedIn -> {
                setPhase(AutoSyncPhase.NEEDS_RECONNECT)
                AutoSyncReport.NotSignedIn
            }

            is RunOutcome.Failure -> {
                handleFailure(trigger, token, outcome.error)
                if (outcome.error == RemoteError.SyncRequired) AutoSyncReport.NeedsSubscription else AutoSyncReport.Failed(outcome.error)
            }
        }
    }

    private fun handleFailure(trigger: AutoSyncTrigger, token: String?, error: RemoteError) {
        log("sinkron ${trigger.name.lowercase()} gagal: ${describe(error)}")
        when (error) {
            RemoteError.Unauthorized -> {
                synchronized(stateLock) {
                    halt = Halt.Auth(token)
                    dirty = true
                }
                setPhase(AutoSyncPhase.NEEDS_RECONNECT)
            }

            RemoteError.SyncRequired -> {
                synchronized(stateLock) {
                    halt = Halt.Subscription
                    dirty = true
                }
                setPhase(AutoSyncPhase.NEEDS_SUBSCRIPTION)
            }

            else -> {
                val delayMs = synchronized(stateLock) {
                    dirty = true
                    backoff.recordFailure(error, nowMs())
                }
                setPhase(AutoSyncPhase.BACKOFF)
                scheduleRetry(delayMs)
            }
        }
    }

    /** Percobaan ulang setelah jeda, hanya selama aplikasi di depan. Tanpa penjadwal latar. */
    private fun scheduleRetry(delayMs: Long) {
        synchronized(stateLock) {
            if (!foreground) return
            retryJob?.cancel()
            retryJob = scope.launch {
                delay(delayMs)
                // Dilepas SEBELUM run, sama seperti debounce: run yang gagal lagi menjadwalkan retry baru tanpa membatalkan dirinya.
                val self = currentCoroutineContext()[Job]
                synchronized(stateLock) {
                    if (retryJob === self) retryJob = null
                }
                runAutomatic(AutoSyncTrigger.RETRY)
            }
        }
    }

    /** Satu run NETWORK_CHANGE setelah jeda minimum habis; hanya selama di depan. */
    private fun scheduleGapRetry(delayMs: Long) {
        synchronized(stateLock) {
            if (!foreground) return
            gapJob?.cancel()
            gapJob = scope.launch {
                delay(delayMs)
                val self = currentCoroutineContext()[Job]
                synchronized(stateLock) {
                    if (gapJob === self) gapJob = null
                }
                runAutomatic(AutoSyncTrigger.NETWORK_CHANGE)
            }
        }
    }

    private suspend fun runScopes(): RunOutcome {
        var pushed = 0
        var applied = 0
        var pushWaits = false
        var roomsSkipped = 0

        when (val personal = safely("pribadi") { steps.personal() }) {
            is ScopeSyncOutcome.Done -> {
                pushed += personal.pushed
                applied += personal.applied
                pushWaits = personal.pushWaitsForRecovery
            }

            ScopeSyncOutcome.NotSignedIn -> return RunOutcome.NotSignedIn
            is ScopeSyncOutcome.Failed -> return RunOutcome.Failure(personal.error)
        }

        val rooms = try {
            steps.activeSharedRooms()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("daftar ruang bersama gagal dibaca: ${e.javaClass.simpleName}")
            emptyList()
        }
        for (roomId in rooms) {
            when (val result = safely("ruang bersama") { steps.sharedRoom(roomId) }) {
                is ScopeSyncOutcome.Done -> {
                    pushed += result.pushed
                    applied += result.applied
                }

                ScopeSyncOutcome.NotSignedIn -> return RunOutcome.NotSignedIn
                is ScopeSyncOutcome.Failed ->
                    // 403 pada SATU ruang (mis. dikeluarkan) bukan berarti langganan habis: cakupan pribadi baru saja berhasil.
                    if (result.error == RemoteError.SyncRequired) {
                        roomsSkipped++
                        log("ruang bersama dilewati (403)")
                    } else {
                        return RunOutcome.Failure(result.error)
                    }
            }
        }
        return RunOutcome.Done(pushed, applied, pushWaits, roomsSkipped)
    }

    /** Langkah yang melempar (bug, bukan jaringan) dianggap gangguan sementara: jeda berlaku, aplikasi tidak jatuh. */
    private suspend fun safely(label: String, block: suspend () -> ScopeSyncOutcome): ScopeSyncOutcome =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("langkah $label melempar ${e.javaClass.simpleName}")
            ScopeSyncOutcome.Failed(RemoteError.Unreachable)
        }

    private fun setPhase(phase: AutoSyncPhase) {
        _state.update { it.copy(phase = phase) }
    }

    private fun describe(error: RemoteError): String = when (error) {
        RemoteError.Unreachable -> "tidak terjangkau"
        RemoteError.Unauthorized -> "401"
        RemoteError.SyncRequired -> "403"
        is RemoteError.Rejected -> "422"
        is RemoteError.Unexpected -> "http ${error.httpCode}"
    }
}
