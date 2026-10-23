package com.roziqrizal.rizqflow.domain.sync

/**
 * Penyimpanan token API Sanctum (hasil [AccountRemote.loginWithGoogle]), dibaca ulang sebelum
 * setiap pemanggilan [SyncRemote]/[AccountRemote.deleteAccount]. Kontrak murni — implementasi
 * SharedPreferences (`SharedPrefsApiTokenStore`) ada di `:data`, mengikuti pola PurchaseStore/
 * ThemePreference di `:app` (lihat CLAUDE.md "Konsekuensi yang diterima" soal penguncian sisi
 * klien: token bisa dilihat pengguna mahir di perangkatnya sendiri, dan itu diterima — token
 * dicabut dari server, bukan disembunyikan, yang jadi pertahanan sebenarnya).
 */
interface ApiTokenStore {
    fun read(): String?

    fun save(token: String)

    fun clear()
}
