package com.roziqrizal.rizqflow.sync

import com.roziqrizal.rizqflow.domain.sync.EnableRecoveryResult
import com.roziqrizal.rizqflow.domain.sync.RecoverKeyResult
import com.roziqrizal.rizqflow.domain.sync.RecoveryChallenge
import com.roziqrizal.rizqflow.domain.sync.RecoveryCodeDisplay
import com.roziqrizal.rizqflow.domain.sync.RecoveryKeyService
import com.roziqrizal.rizqflow.domain.sync.RecoveryStatus
import com.roziqrizal.rizqflow.domain.sync.RemoteCopyCheck
import com.roziqrizal.rizqflow.domain.sync.RemoteError
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Keadaan baris "Kode pemulihan" di menu Lainnya (Tahap 13). Keputusan murni supaya bisa diuji tanpa Compose.
 * Status selalu ikon plus teks di layar, tidak pernah hanya warna.
 */
sealed interface RecoveryRow {
    /** Tidak ditampilkan: bukan akun Google, mode demo, Sync tidak aktif, atau tidak ada token server. */
    data object Hidden : RecoveryRow

    /** Belum diatur: ajakan lembut. Pemakaian lokal tidak pernah diblokir karenanya. */
    data object Offer : RecoveryRow

    /** Salinan sudah diunggah tetapi kodenya belum dikonfirmasi pengguna. */
    data object Pending : RecoveryRow

    data class Configured(val at: Instant) : RecoveryRow
}

/**
 * Baris "Kode pemulihan" hanya untuk akun Google yang di data aslinya (bukan demo), langganan Sync-nya aktif di
 * perangkat ini, dan punya token server aktif: menyimpan salinan di server butuh ketiganya. Akun lokal tidak punya
 * akun server sama sekali.
 */
fun recoveryRow(google: Boolean, demo: Boolean, syncOwned: Boolean, hasToken: Boolean, status: RecoveryStatus): RecoveryRow {
    if (!google || demo || !syncOwned || !hasToken) return RecoveryRow.Hidden
    return when (status) {
        RecoveryStatus.NotConfigured -> RecoveryRow.Offer
        is RecoveryStatus.PendingConfirmation -> RecoveryRow.Pending
        is RecoveryStatus.Configured -> RecoveryRow.Configured(status.at)
    }
}

/**
 * "Pulihkan dengan kode pemulihan" tersedia (baris menu) untuk akun Google dengan token server (bukan demo). Sengaja TIDAK
 * menunggu Sync aktif: membaca salinan tidak butuh langganan (kontrak server), dan perangkat baru mungkin belum
 * sempat memulihkan pembelian dari Google Play.
 */
fun restoreAvailable(google: Boolean, demo: Boolean, hasToken: Boolean): Boolean = google && !demo && hasToken

/**
 * Tawaran aktif (snackbar) di perangkat baru: server bilang ada salinan sementara penanda lokal belum ada.
 * TIDAK dipasang di UI saat ini (diputuskan 2026-10-25): belum ada data yang bergantung pada kunci akun, jadi tawaran
 * itu menjanjikan lebih dari kenyataan. Dipertahankan (beserta tesnya) untuk dipasang kembali setelah sinkron data akun ada.
 * Tidak pernah memblokir apa pun.
 */
fun shouldOfferRestoreOnNewDevice(google: Boolean, demo: Boolean, hasToken: Boolean, status: RecoveryStatus, copy: RemoteCopyCheck): Boolean =
    restoreAvailable(google, demo, hasToken) && status == RecoveryStatus.NotConfigured && copy == RemoteCopyCheck.EXISTS

/**
 * Kode 24 kata yang sedang ditampilkan, hanya di memori proses, supaya memutar layar (Activity dibuat ulang)
 * tidak membuat pengguna kehilangan kode di tengah mencatat. TIDAK PERNAH disimpan: bukan SharedPreferences,
 * bukan state tersimpan Compose (`rememberSaveable`) atau Bundle, bukan berkas. Mati bersama proses; setelah itu
 * penanda "menunggu konfirmasi" memandu pengguna membuat kode baru. Dikosongkan saat verifikasi lulus, layar
 * ditutup, atau keluar akun. [toString] menyembunyikan isinya.
 */
class RecoveryCodeHolder {
    private var held: RecoveryCodeDisplay? = null
    private var owner: String? = null

    @Synchronized
    fun hold(accountId: String, display: RecoveryCodeDisplay) {
        held = display
        owner = accountId
    }

    /** Kode yang ditahan untuk [accountId], atau null (milik akun lain tidak pernah dikembalikan). */
    @Synchronized
    fun peek(accountId: String): RecoveryCodeDisplay? = if (owner == accountId) held else null

    @Synchronized
    fun clear() {
        held = null
        owner = null
    }

    override fun toString(): String = "RecoveryCodeHolder(disembunyikan)"
}

private fun EnableRecoveryResult.isUnauthorized() = this is EnableRecoveryResult.Failed && error == RemoteError.Unauthorized

private fun RecoverKeyResult.isUnauthorized() = this is RecoverKeyResult.Failed && error == RemoteError.Unauthorized

/**
 * [RecoveryKeyService.enableRecovery] di luar thread utama (turunan kunci PBKDF2 berat). 401 padahal token sementara
 * itu diputar diulang SEKALI dengan token baru ([retryOnceIfRotated]); 401 yang nyata ditangani [ServerSession]
 * lewat remote yang dibungkus, bukan di sini.
 */
suspend fun RecoveryKeyService.enableRecoveryRetrying(
    session: ServerSession,
    replaceExistingCopy: Boolean = false,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): EnableRecoveryResult = withContext(dispatcher) {
    session.retryOnceIfRotated(isUnauthorized = { it.isUnauthorized() }) { enableRecovery(replaceExistingCopy) }
}

/** Seperti [enableRecoveryRetrying] untuk [RecoveryKeyService.recoverKey]. */
suspend fun RecoveryKeyService.recoverKeyRetrying(
    session: ServerSession,
    words: List<String>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): RecoverKeyResult = withContext(dispatcher) {
    session.retryOnceIfRotated(isUnauthorized = { it.isUnauthorized() }) { recoverKey(words) }
}

/**
 * [RecoveryKeyService.confirm] di luar thread utama: menulis penanda lewat `commit()` SharedPreferences yang sinkron
 * (menyentuh disk). Gagal verifikasi tidak menulis apa pun. Membatalkan pemanggil sebelum mulai berarti verifikasi
 * diulang pengguna, tidak merusak apa pun.
 */
suspend fun RecoveryKeyService.confirmOffMain(
    challenge: RecoveryChallenge,
    answers: Map<Int, String>,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): Boolean = withContext(dispatcher) { confirm(challenge, answers) }

/**
 * [RecoveryKeyService.forgetStatus] di luar thread utama dan TIDAK ikut dibatalkan: setelah akun server terhapus,
 * penanda "terkonfigurasi" yang tertinggal akan berbohong (salinannya sudah hilang bersama akun), jadi pembersihan
 * harus selesai walau layar atau lingkup pemanggil sudah dibatalkan.
 */
suspend fun RecoveryKeyService.forgetStatusDurably(dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    withContext(NonCancellable + dispatcher) { forgetStatus() }
}
