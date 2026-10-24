package com.roziqrizal.rizqflow.widget

import com.roziqrizal.rizqflow.domain.ledger.SafeToSpend
import com.roziqrizal.rizqflow.domain.money.Money

/**
 * Apa yang ditampilkan widget "Sisa aman hari ini". Murni Kotlin tanpa Android supaya urutan
 * keputusannya bisa diuji; [SafeToSpendWidget] yang menerjemahkannya ke RemoteViews.
 */
sealed interface WidgetState {
    /** Widget adalah fitur Pro (`Feature.HOME_WIDGET`); tanpa Pro hanya mengajak membuka aplikasi. */
    data object NeedsPro : WidgetState

    /** Belum ada akun yang masuk, atau sedang mode demo: tidak ada data pengguna untuk ditampilkan. */
    data object SignedOut : WidgetState

    /**
     * Kunci aplikasi (PIN) aktif, jadi nominal disembunyikan. Widget ada di layar utama, terlihat tanpa
     * membuka kunci; menampilkan nominal di sana melewati PIN yang dipasang pengguna, sedangkan
     * penguncian adalah prinsip bawaan (data keuangan tanpa perlindungan bukan fitur premium).
     */
    data object Locked : WidgetState

    /** Tidak ada ruang Mencukupi yang punya jatah bulan ini, jadi sisa aman belum bisa dihitung. */
    data object NoBudget : WidgetState

    data class Safe(val remaining: Money, val dailyBudget: Money, val isOver: Boolean) : WidgetState
}

object WidgetStateResolver {

    /**
     * Urutan memutuskan: Pro dulu, lalu ada akun, lalu kunci, baru data. [safe] baru dipanggil bila
     * semua syarat sebelumnya lolos, jadi ruang kerja (database) tidak dibuka sia-sia untuk widget
     * yang akan menampilkan keadaan terkunci atau belum masuk.
     */
    suspend fun resolve(
        proEnabled: Boolean,
        signedIn: Boolean,
        lockEnabled: Boolean,
        safe: suspend () -> SafeToSpend?,
    ): WidgetState {
        if (!proEnabled) return WidgetState.NeedsPro
        if (!signedIn) return WidgetState.SignedOut
        if (lockEnabled) return WidgetState.Locked
        val value = safe() ?: return WidgetState.NoBudget
        return WidgetState.Safe(value.remaining, value.dailyBudget, value.isOver)
    }
}
