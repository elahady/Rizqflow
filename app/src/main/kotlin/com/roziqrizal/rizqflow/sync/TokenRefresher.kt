package com.roziqrizal.rizqflow.sync

import com.roziqrizal.rizqflow.domain.sync.AccountRemote
import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import com.roziqrizal.rizqflow.domain.sync.TokenRefreshPolicy
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Hasil satu [TokenRefresher.refreshIfDue]. Tidak ada yang melempar: semuanya keadaan biasa. */
sealed interface RefreshOutcome {
    /** Tidak ada token, atau masa berlakunya masih jauh ([TokenRefreshPolicy]): tidak ada panggilan jaringan. */
    data object NotNeeded : RefreshOutcome

    /** Token diputar dan token baru tertulis ke penyimpanan tetap. */
    data object Rotated : RefreshOutcome

    /**
     * Token diputar (yang lama sudah dicabut server) tetapi token baru GAGAL ditulis ke disk. Dipakai proses
     * ini; pemanggil wajib melaporkannya (log), bukan mengabaikannya.
     */
    data object StorageFailed : RefreshOutcome

    /** Jaringan mati atau server gangguan: tidak ada yang berubah, dicoba lagi pada pemicu berikutnya. */
    data class Deferred(val error: RemoteError?) : RefreshOutcome

    /** Server menjawab 401: sesi berakhir. Token sudah dibersihkan dan "perlu sambungkan ulang" sudah naik di [ServerSession]. */
    data object SessionEnded : RefreshOutcome

    /** Pengguna keluar atau masuk ulang selama putar: token pengganti tidak dipakai (dan dicabut sebisanya). */
    data object Superseded : RefreshOutcome
}

/**
 * Memutar token API sebelum kedaluwarsa (Tahap 12). Dipanggil saat aplikasi dibuka dan sebelum klaim
 * pembelian; keputusan "perlu atau tidak" ada di [TokenRefreshPolicy], jam disuntikkan lewat [clock].
 *
 * Tiga aturan yang menjaga token tidak hilang:
 * 1. SERIALISASI. Server mencabut token lama begitu putar berhasil, jadi dua putar bersamaan dengan token
 *    yang sama (sync pembukaan dan klaim pembelian) akan membuat salah satunya 401 dan menghapus sesi yang
 *    sah. [mutex] membuat putaran kedua menunggu, lalu membaca ulang token dan tanggalnya: yang sudah baru
 *    tidak diputar lagi.
 * 2. TOKEN BARU DISIMPAN DULU. Begitu jawaban sukses diterima, token baru langsung ditulis lewat
 *    [ServerSession.onRotated] tanpa titik jeda di antaranya, dan hasil penulisan dilaporkan
 *    ([RefreshOutcome.StorageFailed]), tidak pernah diam.
 * 3. TIDAK DIBATALKAN DI TENGAH JALAN. Permintaan putar dan penyimpanannya berjalan di [NonCancellable]: bila
 *    layar yang memicunya ditutup tepat saat permintaan terbang, jawaban tetap diterima dan disimpan. Kalau
 *    dibatalkan, server sudah mencabut token lama sementara token baru tidak pernah sampai ke ponsel.
 * 4. JEDA MINIMUM. Token yang baru diterima (kurang dari [TokenRefreshPolicy.MIN_INTERVAL]) tidak diputar lagi,
 *    sehingga jam ponsel yang jauh ke depan tidak membuat token diputar tiap aplikasi dibuka.
 *
 * Penulisan token ke disk (commit() sinkron) berjalan di [ioDispatcher], bukan di thread pemanggil (biasanya utama).
 *
 * [remote] harus remote yang dibungkus [ServerSession.guard] sehingga jawaban 401 dari putar sudah
 * diteruskan ke [ServerSession]. Gagal karena jaringan: diam ([RefreshOutcome.Deferred]).
 */
class TokenRefresher(
    private val session: ServerSession,
    private val remote: AccountRemote,
    private val clock: () -> Instant = { Instant.now() },
    /** Batas waktu mencabut token pengganti yang terbuang ([RefreshOutcome.Superseded]). */
    private val revokeOrphanTimeoutMillis: Long = 5_000L,
    /** Tempat permintaan putar dan penulisan token ke disk: commit() sinkron tidak boleh berjalan di thread utama. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()

    suspend fun refreshIfDue(): RefreshOutcome = mutex.withLock {
        val token = session.token()
        val isDue = TokenRefreshPolicy.isDue(
            hasToken = token != null,
            expiresAt = session.expiresAt(),
            now = clock(),
            lastSavedAt = session.lastSavedAt(),
        )
        if (!isDue) return@withLock RefreshOutcome.NotNeeded
        withContext(NonCancellable + ioDispatcher) { rotate(requireNotNull(token)) }
    }

    private suspend fun rotate(token: String): RefreshOutcome {
        val result = try {
            remote.refresh(token)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Kontrak remote tidak melempar; ini jaring terakhir supaya latar tidak pernah menjatuhkan aplikasi.
            return RefreshOutcome.Deferred(null)
        }
        return when (result) {
            is RemoteResult.Success -> when (session.onRotated(token, result.value, at = clock())) {
                RotationOutcome.SAVED -> RefreshOutcome.Rotated
                RotationOutcome.NOT_PERSISTED -> RefreshOutcome.StorageFailed
                RotationOutcome.SUPERSEDED -> {
                    revokeOrphan(result.value.apiToken)
                    RefreshOutcome.Superseded
                }
            }

            is RemoteResult.Failure ->
                if (result.error == RemoteError.Unauthorized) RefreshOutcome.SessionEnded else RefreshOutcome.Deferred(result.error)
        }
    }

    /** Token pengganti yang tidak dipakai tetap hidup di server; cabut sebisanya agar tidak jadi token yatim. */
    private suspend fun revokeOrphan(orphanToken: String) {
        try {
            withTimeoutOrNull(revokeOrphanTimeoutMillis) { remote.logout(orphanToken) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Upaya terbaik saja.
        }
    }
}
