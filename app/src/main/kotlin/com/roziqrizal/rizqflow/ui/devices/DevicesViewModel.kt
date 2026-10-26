package com.roziqrizal.rizqflow.ui.devices

import com.roziqrizal.rizqflow.domain.sync.DeviceRemote
import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import com.roziqrizal.rizqflow.domain.sync.ServerDevice
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Sebab galat di layar Perangkat yang masuk; teksnya diambil layar dari resource supaya kelas ini tetap murni. */
enum class DevicesFailure {
    /** Tidak ada token server, atau server menolaknya (401): masuk ulang ke akun dulu. */
    SessionEnded,

    /** Server tidak terjangkau: tidak ada yang berubah, coba lagi nanti. */
    Offline,

    /** Galat lain (5xx, jawaban rusak, dan sebagainya). */
    Other,
}

/** Keadaan layar Perangkat yang masuk. [signedOutEverywhere] naik setelah server mencabut semua token; layar lalu keluar lokal. */
data class DevicesUiState(
    val loading: Boolean = false,
    val devices: List<ServerDevice> = emptyList(),
    val loadFailure: DevicesFailure? = null,
    val revoking: Boolean = false,
    val revokeFailure: DevicesFailure? = null,
    val signedOutEverywhere: Boolean = false,
)

/**
 * Layar "Perangkat yang masuk" dan "Keluar dari semua perangkat" (Tahap 14, menu Lainnya). Bentuknya kelas biasa
 * dengan [StateFlow], seperti pengontrol lain di aplikasi ini, dan tidak bergantung pada Android: remote dan token
 * disuntikkan, jadi tesnya cukup memakai remote palsu.
 *
 * Aturan keluar: keluar lokal HANYA setelah server mencabut semua token (sukses), atau bila server sudah menjawab
 * 401 (token ini memang sudah tidak sah, jadi perangkat ini sudah keluar). Galat jaringan tidak memaksa keluar lokal,
 * karena itu akan membuat pengguna mengira perangkat lain sudah dikeluarkan padahal tidak.
 */
class DevicesViewModel(
    private val remote: DeviceRemote,
    /** Token API akun yang sedang aktif; null bila tidak ada (sesi server berakhir atau akun tanpa akun server). */
    private val tokenProvider: () -> String?,
) {
    private val _state = MutableStateFlow(DevicesUiState())
    val state: StateFlow<DevicesUiState> = _state.asStateFlow()

    /** Memuat daftar perangkat. Perangkat ini ditaruh di atas, lalu yang paling baru dipakai. */
    suspend fun load() {
        val token = tokenProvider()
        if (token == null) {
            _state.update { it.copy(loading = false, loadFailure = DevicesFailure.SessionEnded) }
            return
        }
        _state.update { it.copy(loading = true, loadFailure = null) }
        val result = remote.listDevices(token)
        _state.update { current ->
            when (result) {
                is RemoteResult.Success -> current.copy(
                    loading = false,
                    devices = result.value.sortedWith(
                        compareBy<ServerDevice>({ !it.current }, { -(it.lastUsedAt ?: it.createdAt ?: Instant.EPOCH).toEpochMilli() }),
                    ),
                    loadFailure = null,
                )

                is RemoteResult.Failure -> current.copy(loading = false, loadFailure = result.error.toFailure())
            }
        }
    }

    /** "Keluar dari semua perangkat": mencabut semua token akun di server, lalu menandai keluar lokal. */
    suspend fun signOutEverywhere() {
        if (_state.value.revoking || _state.value.signedOutEverywhere) return
        val token = tokenProvider()
        if (token == null) {
            _state.update { it.copy(revokeFailure = DevicesFailure.SessionEnded) }
            return
        }
        _state.update { it.copy(revoking = true, revokeFailure = null) }
        when (val result = remote.revokeAllDevices(token)) {
            is RemoteResult.Success -> _state.update { it.copy(revoking = false, signedOutEverywhere = true) }

            is RemoteResult.Failure -> when (result.error) {
                // Token sudah tidak sah di server: perangkat ini memang sudah keluar, jadi keluar lokal dilanjutkan.
                RemoteError.Unauthorized -> _state.update { it.copy(revoking = false, signedOutEverywhere = true) }

                else -> _state.update { it.copy(revoking = false, revokeFailure = result.error.toFailure()) }
            }
        }
    }

    private fun RemoteError.toFailure(): DevicesFailure = when (this) {
        RemoteError.Unauthorized -> DevicesFailure.SessionEnded
        RemoteError.Unreachable -> DevicesFailure.Offline
        else -> DevicesFailure.Other
    }
}
