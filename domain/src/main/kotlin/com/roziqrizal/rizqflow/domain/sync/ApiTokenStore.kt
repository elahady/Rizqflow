package com.roziqrizal.rizqflow.domain.sync

import java.time.Instant

/**
 * Penyimpanan token API Sanctum (hasil [AccountRemote.loginWithGoogle] atau [AccountRemote.refresh]),
 * dibaca ulang sebelum setiap pemanggilan [SyncRemote]/[AccountRemote.deleteAccount]. Kontrak murni —
 * implementasi SharedPreferences (`SharedPrefsApiTokenStore`) ada di `:data`, mengikuti pola
 * PurchaseStore/ThemePreference di `:app` (lihat CLAUDE.md "Konsekuensi yang diterima" soal penguncian
 * sisi klien: token bisa dilihat pengguna mahir di perangkatnya sendiri, dan itu diterima — token
 * dicabut dari server, bukan disembunyikan, yang jadi pertahanan sebenarnya).
 *
 * Token punya masa berlaku (Tahap 12, bawaan 90 hari di server): [expiresAt] adalah tanggal yang
 * dikirim server bersama token. Token yang tersimpan sebelum server mengirim tanggal itu tetap
 * terbaca dan [expiresAt]-nya null, artinya masa berlakunya BELUM DIKETAHUI (bukan "tidak pernah
 * kedaluwarsa"). Tanggal ini hanya petunjuk kapan perlu memutar token; yang menentukan sesi berakhir
 * tetap jawaban 401 dari server, bukan jam ponsel.
 */
interface ApiTokenStore {
    fun read(): String?

    /** Masa berlaku token yang sedang tersimpan; null bila belum diketahui atau tidak ada token. */
    fun expiresAt(): Instant?

    /**
     * Menyimpan [token] beserta [expiresAt] (null = belum diketahui), menimpa token dan tanggal sebelumnya.
     * Hasil true berarti sudah tertulis ke penyimpanan tetap; false berarti penulisan ke disk gagal
     * (token baru mungkin hanya hidup di memori proses ini). Pemanggil yang menyimpan token hasil putar
     * WAJIB memeriksanya: token lama sudah dicabut server, jadi kegagalan di sini tidak boleh diam.
     */
    fun save(token: String, expiresAt: Instant?): Boolean

    /**
     * Seperti [save], dan mencatat [savedAt] (jam ponsel saat token ini diterima) sebagai waktu terakhir token
     * diterima. Dipakai [TokenRefreshPolicy] sebagai jeda minimum antar putar: jam ponsel yang jauh ke depan
     * membuat masa berlaku tampak selalu dekat, dan tanpa jeda token diputar tiap aplikasi dibuka. Penyimpanan
     * tanpa tempat untuk waktu ini cukup memakai bawaannya (mengabaikan [savedAt]).
     */
    fun save(token: String, expiresAt: Instant?, savedAt: Instant?): Boolean = save(token, expiresAt)

    /** Waktu token yang sedang tersimpan diterima ([save] dengan `savedAt`); null bila tidak ada token atau tidak diketahui. */
    fun lastSavedAt(): Instant? = null

    /**
     * Apakah perangkat ini pernah memegang token server untuk akun ini (sampai akun server dihapus lewat
     * [forgetAccount]). Berbeda dari `read() != null`: tetap true setelah token dibersihkan (sesi berakhir atau
     * keluar), karena akun server-nya masih ada. Dipakai untuk membedakan "tak pernah punya akun server" dari
     * "punya, tetapi tokennya hilang" saat Hapus akun.
     */
    fun hasEverHeldToken(): Boolean = read() != null

    /** Menghapus token dan tanggalnya. Catatan [hasEverHeldToken] tetap ada. */
    fun clear()

    /** Menghapus token, tanggalnya, dan catatan [hasEverHeldToken]: akun server sudah dihapus, tidak ada lagi yang perlu diingat. */
    fun forgetAccount() = clear()
}
