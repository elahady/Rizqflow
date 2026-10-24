package com.roziqrizal.rizqflow.sync

import com.roziqrizal.rizqflow.domain.sync.AccountRemote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keluar akun terhadap server (Tahap 12). Keluar lokal TIDAK PERNAH menunggu jaringan: token lokal dibersihkan
 * dulu secara sinkron, baru pencabutan di server dicoba di latar sebagai upaya terbaik dengan batas waktu
 * pendek. Gagal, offline, atau kehabisan waktu tidak mengubah apa pun di ponsel; token yang tertinggal di
 * server tetap berakhir sendiri pada masa berlakunya dan tidak lagi ada di perangkat ini.
 *
 * Pembersihan token lokal sengaja SINKRON di thread pemanggil (commit() satu berkas kecil), tidak dipindah ke
 * latar: dipanggil dari onClick Keluar, pembersihan harus pasti selesai sebelum proses sempat mati. Yang
 * dipindah ke Dispatchers.IO hanya penulisan token baru (putar dan masuk), yang tidak pernah kritis seperti ini.
 *
 * [scope] harus hidup lebih lama dari ruang kerja akun yang ditutup saat keluar (scope aplikasi), bukan
 * scope layar, kalau tidak pencabutan ikut terbatalkan begitu menu utama dibongkar.
 */
class ServerSignOut(
    private val scope: CoroutineScope,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    /**
     * Membersihkan token akun [session] SEKARANG lalu (bila ada token) mencabutnya di server lewat [remote]
     * di latar. Mengembalikan pekerjaan latar itu (null bila tidak ada token), hanya agar bisa diamati tes.
     */
    fun signOut(session: ServerSession, remote: AccountRemote): Job? {
        // Baca dan hapus sebagai satu langkah (takeToken): token() lalu clearLocal() membuka celah bagi putar token
        // yang selesai di antaranya, sehingga token baru ikut terhapus tetapi yang dicabut adalah token lama yang mati.
        val token = session.takeToken() ?: return null
        return scope.launch {
            try {
                withTimeoutOrNull(timeoutMillis) { remote.logout(token) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Upaya terbaik: pengguna sudah keluar, tidak ada yang bisa atau perlu dilakukan dengan galat ini.
            }
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L
    }
}
