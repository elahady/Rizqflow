package com.roziqrizal.rizqflow.domain.sync

import java.time.Duration
import java.time.Instant

/**
 * Keputusan murni "perlu memutar token API sekarang?" (Tahap 12, token yang kedaluwarsa dan bisa
 * dicabut). Tanpa jaringan dan tanpa jam sungguhan: waktu sekarang disuntikkan pemanggil. Yang
 * menjalankan keputusannya (`TokenRefresher` di `:app`) hanya mengikutinya.
 *
 * Aturannya:
 * - Tidak ada token: tidak ada yang diputar.
 * - Masa berlaku diketahui: diputar bila tinggal [REFRESH_WINDOW] (30 hari) atau lebih dekat,
 *   termasuk yang menurut jam ponsel sudah lewat. Yang sudah lewat tetap DICOBA, bukan langsung
 *   dianggap berakhir: jam ponsel bisa salah, dan hanya jawaban 401 server yang mengakhiri sesi.
 * - Masa berlaku BELUM diketahui (token lama dari sebelum server mengirim tanggal): diputar satu kali
 *   supaya token berikutnya membawa tanggalnya. Menunggu tidak ada gunanya karena klien tidak punya
 *   cara lain untuk tahu kapan token itu berakhir.
 * - JEDA MINIMUM: token yang baru diterima (masuk atau hasil putar) kurang dari [MIN_INTERVAL] yang lalu
 *   tidak diputar lagi, apa pun masa berlakunya. Tanpa ini, jam ponsel yang jauh ke depan (atau tanggal yang
 *   tak terbaca) membuat token tampak selalu hampir kedaluwarsa dan diputar tiap aplikasi dibuka. Waktu
 *   terima yang ternyata di MASA DEPAN menurut jam sekarang (jam ponsel sempat melompat lalu diperbaiki)
 *   diabaikan, bukan dipercaya: kalau tidak, putar akan tertahan sampai jam menyusul tanggal palsu itu.
 */
object TokenRefreshPolicy {
    /** Token yang akan kedaluwarsa dalam rentang ini (atau lebih dekat) diputar. */
    val REFRESH_WINDOW: Duration = Duration.ofDays(30)

    /** Jeda minimum antar putar (dan sesudah masuk): token yang lebih baru dari ini tidak diputar lagi. */
    val MIN_INTERVAL: Duration = Duration.ofDays(1)

    fun isDue(hasToken: Boolean, expiresAt: Instant?, now: Instant, lastSavedAt: Instant? = null): Boolean {
        if (!hasToken) return false
        if (lastSavedAt != null && !lastSavedAt.isAfter(now) && now.isBefore(lastSavedAt.plus(MIN_INTERVAL))) return false
        if (expiresAt == null) return true
        return !expiresAt.isAfter(now.plus(REFRESH_WINDOW))
    }
}
