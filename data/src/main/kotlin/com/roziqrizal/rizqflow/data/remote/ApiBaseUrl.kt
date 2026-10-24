package com.roziqrizal.rizqflow.data.remote

import java.net.URI
import java.net.URISyntaxException

/**
 * Pemeriksaan murni alamat dasar API (Tahap 12, "alamat API bisa dikonfigurasi"). Alamat berasal dari
 * `BuildConfig.API_BASE_URL`, yang ditentukan `app/build.gradle.kts` (properti `-PrizqflowApiBaseUrl`,
 * lalu `rizqflow.apiBaseUrl` di local.properties, lalu `RIZQFLOW_API_BASE_URL`, lalu bawaan tipe build).
 * Gradle sudah memeriksa garis miring dan https untuk rilis; pemeriksaan di sini adalah lapis kedua di
 * dalam aplikasi, supaya alamat tak sah tidak pernah sampai ke Retrofit (yang melempar exception saat
 * ruang kerja dibuka) dan alamat http tidak pernah dipakai di build rilis walau konfigurasi Gradle salah.
 *
 * Aturan ini sengaja dicerminkan di `app/build.gradle.kts` (skrip build tidak bisa memanggil kode
 * `:data`); ubah keduanya bersama.
 */
object ApiBaseUrl {
    /**
     * Alamat pengganti yang TIDAK PERNAH bisa dihubungi: TLD `.invalid` dicadangkan RFC 2606 untuk
     * tidak pernah diselesaikan DNS. Dipakai [resolve] saat konfigurasi tak sah, supaya aplikasi tetap
     * terbuka dan berfungsi penuh offline (semua pemanggilan server gagal sebagai "tidak terjangkau"),
     * bukan crash dan bukan menyambung ke alamat yang tidak dimaksud.
     */
    const val UNREACHABLE = "https://rizqflow-api-belum-dikonfigurasi.invalid/api/"

    enum class Reason {
        /** Kosong atau hanya spasi. */
        BLANK,

        /** Bukan URL yang bisa diurai (karakter terlarang, spasi di tengah, dan sebagainya). */
        MALFORMED,

        /** Skema selain http dan https (ftp, file, javascript, atau tanpa skema). */
        UNSUPPORTED_SCHEME,

        /** Tidak ada nama host (mis. `https:///api/` atau `https://:8000/api/`). */
        MISSING_HOST,

        /** Mengandung nama pengguna atau sandi di URL; rahasia tidak boleh ditanam di alamat. */
        CREDENTIALS_IN_URL,

        /** http polos di build yang tidak mengizinkan cleartext (rilis). */
        CLEARTEXT_NOT_ALLOWED,

        /** Retrofit mewajibkan alamat dasar berakhir dengan `/`. */
        MISSING_TRAILING_SLASH,
    }

    sealed interface Validation {
        /** [url] sudah dipangkas dan siap diberikan ke Retrofit. */
        data class Valid(val url: String) : Validation

        data class Invalid(val reason: Reason) : Validation
    }

    /**
     * Memeriksa [raw]. [allowCleartext] true hanya untuk build debug (http ke emulator, LAN, atau
     * `adb reverse`); build rilis memberi false sehingga hanya https yang lolos.
     */
    fun validate(raw: String?, allowCleartext: Boolean): Validation {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return Validation.Invalid(Reason.BLANK)

        val uri = try {
            URI(trimmed)
        } catch (_: URISyntaxException) {
            return Validation.Invalid(Reason.MALFORMED)
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return Validation.Invalid(Reason.UNSUPPORTED_SCHEME)
        if (uri.host.isNullOrBlank()) return Validation.Invalid(Reason.MISSING_HOST)
        if (uri.userInfo != null) return Validation.Invalid(Reason.CREDENTIALS_IN_URL)
        if (scheme == "http" && !allowCleartext) return Validation.Invalid(Reason.CLEARTEXT_NOT_ALLOWED)
        if (!trimmed.endsWith("/")) return Validation.Invalid(Reason.MISSING_TRAILING_SLASH)

        return Validation.Valid(trimmed)
    }

    /**
     * Alamat yang aman dipakai: [raw] bila sah, kalau tidak [UNREACHABLE]. [onInvalid] dipanggil dengan
     * alasannya (untuk dicatat di log) sebelum jatuh ke pengganti.
     */
    fun resolve(raw: String?, allowCleartext: Boolean, onInvalid: (Reason) -> Unit = {}): String =
        when (val validation = validate(raw, allowCleartext)) {
            is Validation.Valid -> validation.url
            is Validation.Invalid -> {
                onInvalid(validation.reason)
                UNREACHABLE
            }
        }
}
