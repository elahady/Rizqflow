package com.roziqrizal.rizqflow.domain.sync

/**
 * Hasil generik untuk semua pemanggilan [AccountRemote] dan [SyncRemote] — satu bentuk kegagalan
 * untuk seluruh lapisan jaringan (sama prinsipnya dengan satu lapisan entitlement di
 * [com.roziqrizal.rizqflow.domain.entitlement.Entitlements]), supaya UI menangani jaringan
 * mati/token kedaluwarsa/butuh Sync dengan cara yang konsisten di semua layar. Prinsip "tenang,
 * bukan panik" (CLAUDE.md): kegagalan jaringan bukan error aplikasi, karena aplikasi tetap
 * berfungsi penuh offline — pemanggil yang memutuskan kapan menampilkan peringatan lembut.
 */
sealed interface RemoteResult<out T> {
    data class Success<out T>(val value: T) : RemoteResult<T>

    data class Failure(val error: RemoteError) : RemoteResult<Nothing>
}

sealed interface RemoteError {
    /** Tidak ada koneksi, server tidak terjangkau, atau request time out. */
    data object Unreachable : RemoteError

    /**
     * HTTP 401 dari endpoint terlindungi: token API tidak sah, sudah kedaluwarsa, atau sudah dicabut
     * (mis. akun dihapus atau sandi diganti dari tempat lain). Berarti sesi server berakhir; satu
     * tempat pusat (`ServerSession` di `:app`) yang membersihkan token dan menaikkan keadaan "perlu
     * sambungkan ulang", bukan tiap pemanggil.
     */
    data object Unauthorized : RemoteError

    /** HTTP 403: fitur butuh langganan Sync aktif (lihat `SyncController::denyWithoutSync` di server). */
    data object SyncRequired : RemoteError

    /** HTTP 422: validasi gagal di server. [message] sudah dalam Bahasa Indonesia, siap ditampilkan. */
    data class Rejected(val message: String) : RemoteError

    /** Kode lain yang tidak diantisipasi (5xx dan lainnya). */
    data class Unexpected(val httpCode: Int) : RemoteError
}
