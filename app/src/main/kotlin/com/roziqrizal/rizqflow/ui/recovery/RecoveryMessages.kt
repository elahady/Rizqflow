package com.roziqrizal.rizqflow.ui.recovery

import androidx.annotation.StringRes
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.sync.EnableRecoveryResult
import com.roziqrizal.rizqflow.domain.sync.InvalidRecoveryCodeKind
import com.roziqrizal.rizqflow.domain.sync.RecoverKeyResult
import com.roziqrizal.rizqflow.domain.sync.RecoveryCopyState
import com.roziqrizal.rizqflow.domain.sync.RemoteError

/**
 * Pesan untuk hasil yang BUKAN sukses saat mengatur kode pemulihan. Hasil [EnableRecoveryResult.Ready] dan
 * [EnableRecoveryResult.ExistingCopyOnServer] ditangani layar sendiri (bukan pesan); memanggil fungsi ini untuknya
 * adalah kesalahan pemrograman. [sessionEnded] = `ServerSession.sessionEndedBy(error)`: 401 hanya dinyatakan
 * "sesi berakhir" bila sesi benar-benar berakhir.
 *
 * 403 tanpa Sync dipetakan ke pesan lembut "butuh langganan Sync", BUKAN "gagal". Hasil yang TIDAK BISA DIPASTIKAN
 * ([EnableRecoveryResult.Unverified]) dan salinan yang tidak sama ([EnableRecoveryResult.ServerCopyMismatch]) punya
 * pesan jujur masing-masing: keduanya tidak menampilkan kode dan mengarahkan ke "buat kode baru".
 */
@StringRes
fun enableFailureMessage(result: EnableRecoveryResult, sessionEnded: Boolean): Int = when (result) {
    is EnableRecoveryResult.Ready, EnableRecoveryResult.ExistingCopyOnServer ->
        error("Bukan hasil gagal: $result")

    EnableRecoveryResult.NotSignedIn -> R.string.recovery_err_not_signed_in
    EnableRecoveryResult.SyncRequired -> R.string.recovery_err_sync_required
    EnableRecoveryResult.KeyUnavailable -> R.string.recovery_err_key
    EnableRecoveryResult.Unverified -> R.string.recovery_err_unverified
    EnableRecoveryResult.ServerCopyMismatch -> R.string.recovery_err_mismatch
    is EnableRecoveryResult.Failed -> when (val error = result.error) {
        RemoteError.Unreachable -> R.string.recovery_err_network
        RemoteError.Unauthorized -> if (sessionEnded) R.string.recovery_err_session_ended else R.string.recovery_err_generic
        RemoteError.SyncRequired -> R.string.recovery_err_sync_required
        is RemoteError.Rejected -> R.string.recovery_err_generic
        is RemoteError.Unexpected -> if (error.isRouteMissing()) R.string.recovery_err_server_not_ready else R.string.recovery_err_generic
    }
}

/** Pesan untuk hasil [RecoverKeyResult] selain [RecoverKeyResult.Recovered] (sukses punya pesannya sendiri). */
@StringRes
fun recoverFailureMessage(result: RecoverKeyResult, sessionEnded: Boolean): Int = when (result) {
    RecoverKeyResult.Recovered -> error("Bukan hasil gagal: $result")
    is RecoverKeyResult.InvalidCode -> invalidCodeMessage(result.kind)
    RecoverKeyResult.NotSignedIn -> R.string.recovery_err_not_signed_in
    RecoverKeyResult.NoCopyOnServer -> R.string.restore_err_none
    // Sengaja satu pesan untuk kode salah dan salinan rusak: tidak membedakan lebih jauh.
    RecoverKeyResult.WrongCode -> R.string.restore_err_wrong_code
    RecoverKeyResult.UnsupportedVersion -> R.string.restore_err_unsupported
    RecoverKeyResult.BackupUnreadable -> R.string.restore_err_unreadable
    RecoverKeyResult.KeyUnavailable -> R.string.recovery_err_key
    is RecoverKeyResult.Failed -> when (val error = result.error) {
        RemoteError.Unreachable -> R.string.restore_err_network
        RemoteError.Unauthorized -> if (sessionEnded) R.string.recovery_err_session_ended else R.string.restore_err_generic
        RemoteError.SyncRequired, is RemoteError.Rejected -> R.string.restore_err_generic
        is RemoteError.Unexpected -> if (error.isRouteMissing()) R.string.restore_err_server_not_ready else R.string.restore_err_generic
    }
}

@StringRes
fun invalidCodeMessage(kind: InvalidRecoveryCodeKind): Int = when (kind) {
    InvalidRecoveryCodeKind.WRONG_COUNT -> R.string.restore_err_count
    InvalidRecoveryCodeKind.UNKNOWN_WORD -> R.string.restore_err_unknown
    InvalidRecoveryCodeKind.BAD_CHECKSUM -> R.string.restore_err_checksum
}

/**
 * 404 tanpa penanda kontrak dari server (rute belum dideploy, proxy, server lama): `RetrofitRecoveryKeyRemote`
 * melaporkannya sebagai `Unexpected(404)`. Pesannya "server belum siap", BUKAN "belum ada kode pemulihan" (yang
 * hanya untuk 404 bertanda `no_recovery_key`).
 */
private fun RemoteError.Unexpected.isRouteMissing(): Boolean = httpCode == 404

/**
 * Pesan keadaan salinan di server terhadap penanda perangkat ini (layar Kode pemulihan). Hanya
 * [RecoveryCopyState.REPLACED] dan [RecoveryCopyState.MISSING] yang punya pesan; sisanya null (tidak ada yang perlu
 * dikabarkan, dan tidak menakut-nakuti tanpa alasan).
 */
@StringRes
fun copyStateMessage(state: RecoveryCopyState): Int? = when (state) {
    RecoveryCopyState.REPLACED -> R.string.recovery_status_replaced
    RecoveryCopyState.MISSING -> R.string.recovery_status_missing
    RecoveryCopyState.IN_SYNC, RecoveryCopyState.UNKNOWN -> null
}
