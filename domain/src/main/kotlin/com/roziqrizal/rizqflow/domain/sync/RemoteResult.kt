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

    /**
     * HTTP 403 yang berarti "butuh langganan Sync aktif": `code` = [ServerErrorCodes.SYNC_REQUIRED], atau 403 TANPA
     * `code` (server lama). Hanya galat ini yang membuka paywall Sync (`opensSyncPaywall` di `:app`); 403 dengan kode
     * lain (mis. `not_room_member`) menjadi [Rejected].
     */
    data object SyncRequired : RemoteError

    /**
     * Server menolak karena aturan, bukan karena jaringan atau langganan: HTTP 422, 403 dengan kode selain
     * `sync_required`, atau 404 dengan kode keanggotaan ruang ([ServerErrorCodes.REJECTION_CODES]). [message] dari
     * server (Bahasa Indonesia) hanya cocok untuk log atau layar yang memang menampilkannya (mis. masuk email); layar
     * ruang keluarga memetakan [code] ke teks sendiri dan tidak menampilkan [message]. [code] = `code` stabil dari
     * badan galat server ([ServerErrorCodes]), null bila server tidak mengirimnya.
     */
    data class Rejected(val message: String, val code: String? = null) : RemoteError

    /**
     * Kode lain yang tidak diantisipasi (5xx, 429, dan lainnya). [retryAfterSeconds] = header `Retry-After` bila server
     * mengirimnya (dalam detik; tanggal HTTP sudah dihitung selisihnya), atau `retry_after` di badan galat bila header
     * tidak terbaca; dipakai [SyncBackoff] untuk 429 dan 503 dan oleh pesan "terlalu sering" di layar ([isRateLimited]).
     */
    data class Unexpected(val httpCode: Int, val retryAfterSeconds: Long? = null) : RemoteError
}
