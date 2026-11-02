package com.roziqrizal.rizqflow.ui

import android.content.res.Resources
import androidx.annotation.StringRes
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.domain.sync.ServerErrorCodes
import com.roziqrizal.rizqflow.domain.sync.isRateLimited
import com.roziqrizal.rizqflow.domain.sync.rateLimitRetryAfterSeconds
import com.roziqrizal.rizqflow.domain.sync.rejectionCode

/**
 * Pesan layar untuk galat server, tanpa pernah menampilkan teks mentah server ("tenang, bukan panik"): galat
 * dipetakan lewat `code` stabil ([ServerErrorCodes]) ke string aplikasi sendiri. Fungsi murni tanpa Context supaya
 * bisa diuji; [resolve] mengubahnya jadi teks di layar.
 */
data class UiMessage(@StringRes val res: Int, val number: Long? = null) {
    fun resolve(resources: Resources): String =
        if (number == null) resources.getString(res) else resources.getString(res, number)
}

/** Batas bawah detik yang dibulatkan ke menit saat menampilkan jeda 429. */
private const val SECONDS_PER_MINUTE = 60L

/**
 * Pesan "terlalu sering" (HTTP 429). Jeda dari server ([retryAfterSeconds], `Retry-After` atau `retry_after`) disebut
 * bila ada: di bawah semenit dalam detik, selebihnya dibulatkan KE ATAS ke menit (jangan menjanjikan lebih cepat dari
 * yang diminta server). Tanpa jeda atau jeda 0: kalimat tanpa angka.
 */
fun rateLimitedMessage(retryAfterSeconds: Long?): UiMessage = when {
    retryAfterSeconds == null || retryAfterSeconds <= 0 -> UiMessage(R.string.remote_rate_limited)
    retryAfterSeconds < SECONDS_PER_MINUTE -> UiMessage(R.string.remote_rate_limited_seconds, retryAfterSeconds)
    else -> UiMessage(R.string.remote_rate_limited_minutes, (retryAfterSeconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE)
}

/**
 * Pesan untuk galat server di layar Ruang Keluarga, pemindai undangan, dan sinkron manual ruang. Urutan: 429 selalu
 * pesan "terlalu sering" (bukan "gagal"); galat berkode dipetakan ke penjelasan lembut yang sesuai; sisanya
 * [fallback] milik aksinya (mis. "Gagal sinkron. Periksa koneksi..."). `SyncRequired` TIDAK dipetakan di sini: pemanggil
 * membuka paywall Sync lebih dulu (`opensSyncPaywall`).
 */
fun RemoteError.familyRoomMessage(@StringRes fallback: Int): UiMessage {
    if (isRateLimited()) return rateLimitedMessage(rateLimitRetryAfterSeconds())
    return when (rejectionCode()) {
        ServerErrorCodes.NOT_ROOM_MEMBER -> UiMessage(R.string.family_err_not_member)
        ServerErrorCodes.NOT_ROOM_OWNER -> UiMessage(R.string.family_err_not_owner)
        ServerErrorCodes.OWNER_CANNOT_LEAVE -> UiMessage(R.string.family_err_owner_cannot_leave)
        ServerErrorCodes.OWNER_CANNOT_BE_REMOVED -> UiMessage(R.string.family_err_owner_cannot_be_removed)
        ServerErrorCodes.ALREADY_OWNER -> UiMessage(R.string.family_err_already_owner)
        ServerErrorCodes.MEMBER_NOT_FOUND -> UiMessage(R.string.family_err_member_not_found)
        ServerErrorCodes.INVALID_ROOM_ID -> UiMessage(R.string.family_err_invalid_room)
        ServerErrorCodes.INVITE_REQUIRED -> UiMessage(R.string.family_err_invite_required)
        ServerErrorCodes.INVITE_INVALID -> UiMessage(R.string.family_err_invite_invalid)
        ServerErrorCodes.INVITE_EXPIRED -> UiMessage(R.string.family_err_invite_expired)
        ServerErrorCodes.INVITE_USED -> UiMessage(R.string.family_err_invite_used)
        ServerErrorCodes.ROOM_FULL -> UiMessage(R.string.family_err_room_full)
        else -> UiMessage(fallback)
    }
}
