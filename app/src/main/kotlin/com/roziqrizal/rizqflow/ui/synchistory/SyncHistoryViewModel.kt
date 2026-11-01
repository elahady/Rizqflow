package com.roziqrizal.rizqflow.ui.synchistory

import com.roziqrizal.rizqflow.domain.sync.FullResyncOutcome
import com.roziqrizal.rizqflow.domain.sync.LocalSyncHistory
import com.roziqrizal.rizqflow.domain.sync.PersonalSyncResult
import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import com.roziqrizal.rizqflow.domain.sync.ServerSyncDevice
import com.roziqrizal.rizqflow.domain.sync.ServerSyncHistory
import com.roziqrizal.rizqflow.domain.sync.SyncHealth
import com.roziqrizal.rizqflow.domain.sync.SyncHealthPolicy
import com.roziqrizal.rizqflow.domain.sync.SyncHistoryRemote
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Sebab galat di layar Riwayat sinkron; teksnya diambil layar dari resource supaya kelas ini tetap murni. */
enum class SyncHistoryFailure {
    /** Tidak ada token server, atau server menolaknya (401): masuk ulang ke akun dulu. */
    SessionEnded,

    /** Server tidak terjangkau: tidak ada yang berubah, coba lagi nanti. */
    Offline,

    /** Galat lain (5xx, jawaban rusak, dan sebagainya). */
    Other,
}

/** Keadaan tombol "Sinkron ulang penuh". */
sealed interface ResyncState {
    data object Idle : ResyncState

    data object Running : ResyncState

    /** Selesai tanpa galat; [applied] = baris yang diterapkan dari server (bisa 0 bila semuanya sudah sama). */
    data class Done(val applied: Int) : ResyncState

    /** Tidak selesai; kursor sudah direset sehingga sinkron berikutnya melanjutkan dari awal. Data lokal tidak berubah. */
    data class Failed(val reason: SyncHistoryFailure) : ResyncState
}

/**
 * Keadaan layar Riwayat sinkron. [local] selalu dibaca dari database lokal (tetap tampil walau offline); [server]
 * null bila server belum bisa dihubungi, dengan alasannya di [serverFailure].
 */
data class SyncHistoryUiState(
    val loading: Boolean = false,
    val local: LocalSyncHistory? = null,
    val server: ServerSyncHistory? = null,
    val serverFailure: SyncHistoryFailure? = null,
    val health: SyncHealth = SyncHealth.CALM,
    /** Baris server untuk perangkat ini (waktu push/pull terakhir), bila server sudah pernah melihatnya. */
    val thisDevice: ServerSyncDevice? = null,
    val otherDevices: List<ServerSyncDevice> = emptyList(),
    val resync: ResyncState = ResyncState.Idle,
)

/**
 * Layar "Riwayat sinkron" (Tahap 9, menu Lainnya, dekat "Rizqflow Sync"). Kelas biasa dengan [StateFlow], tanpa Android:
 * pembaca lokal, remote, token, dan "sinkron ulang penuh" disuntikkan, jadi tesnya cukup memakai pengganti sederhana.
 *
 * Aturan: bagian lokal (baris menunggu, masalah sinkron) selalu tampil karena dibaca dari database sendiri; bagian
 * server (waktu push/pull, kuota, kompaksi) menyusul bila server terjangkau. Galat jaringan tidak pernah menjadi
 * "error aplikasi": cukup satu kalimat lembut dan tombol coba lagi.
 */
class SyncHistoryViewModel(
    private val readLocal: suspend () -> LocalSyncHistory,
    private val remote: SyncHistoryRemote,
    /** Token API akun yang sedang aktif; null bila tidak ada (sesi server berakhir). */
    private val tokenProvider: () -> String?,
    /** Pengenal pemasangan ini, untuk mengenali baris perangkat ini di jawaban server. */
    private val deviceId: String,
    /** "Sinkron ulang penuh"; dipasang ke `AccountWorkspace.fullResync`. */
    private val runFullResync: suspend () -> FullResyncOutcome,
) {
    private val _state = MutableStateFlow(SyncHistoryUiState())
    val state: StateFlow<SyncHistoryUiState> = _state.asStateFlow()

    /** Memuat bagian lokal dulu (cepat, tanpa jaringan), lalu bagian server. Tidak mengubah status [ResyncState]. */
    suspend fun load() {
        _state.update { it.copy(loading = true) }
        val local = readLocal()
        _state.update { it.copy(local = local, health = SyncHealthPolicy.assess(local, it.server)) }

        val token = tokenProvider()
        if (token == null) {
            publish(local, server = null, failure = SyncHistoryFailure.SessionEnded)
            return
        }
        when (val result = remote.history(token)) {
            is RemoteResult.Success -> publish(local, server = result.value, failure = null)
            is RemoteResult.Failure -> publish(local, server = null, failure = result.error.toFailure())
        }
    }

    /**
     * "Sinkron ulang penuh": reset kursor lalu tarik ulang dari awal, tanpa menghapus data lokal. Setelahnya layar dimuat
     * ulang supaya angka menunggu, masalah, dan waktu push/pull terbaru tampil. Satu kali jalan pada satu waktu.
     */
    suspend fun fullResync() {
        if (_state.value.resync == ResyncState.Running) return
        _state.update { it.copy(resync = ResyncState.Running) }
        val outcome = try {
            runFullResync()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _state.update { it.copy(resync = ResyncState.Failed(SyncHistoryFailure.Other)) }
            return
        }
        val resync = if (outcome.complete) {
            ResyncState.Done(outcome.applied)
        } else {
            ResyncState.Failed(failureOf(outcome))
        }
        _state.update { it.copy(resync = resync) }
        load()
    }

    /** Menutup pesan hasil sinkron ulang (kembali ke [ResyncState.Idle]). */
    fun dismissResyncResult() {
        _state.update { if (it.resync is ResyncState.Done || it.resync is ResyncState.Failed) it.copy(resync = ResyncState.Idle) else it }
    }

    private fun publish(local: LocalSyncHistory, server: ServerSyncHistory?, failure: SyncHistoryFailure?) {
        _state.update {
            it.copy(
                loading = false,
                local = local,
                server = server,
                serverFailure = failure,
                health = SyncHealthPolicy.assess(local, server),
                thisDevice = server?.forDevice(deviceId),
                otherDevices = server?.others(deviceId).orEmpty(),
            )
        }
    }

    private fun failureOf(outcome: FullResyncOutcome): SyncHistoryFailure =
        when {
            outcome.personal is PersonalSyncResult.NotSignedIn -> SyncHistoryFailure.SessionEnded
            else -> outcome.firstError?.toFailure() ?: SyncHistoryFailure.Other
        }

    private fun RemoteError.toFailure(): SyncHistoryFailure = when (this) {
        RemoteError.Unauthorized -> SyncHistoryFailure.SessionEnded
        RemoteError.Unreachable -> SyncHistoryFailure.Offline
        else -> SyncHistoryFailure.Other
    }
}
