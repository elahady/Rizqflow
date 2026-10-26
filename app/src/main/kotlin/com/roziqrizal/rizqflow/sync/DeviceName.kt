package com.roziqrizal.rizqflow.sync

import android.os.Build

/**
 * Nama perangkat yang dikirim ke server saat masuk dan memutar token (Tahap 14), supaya daftar "Perangkat yang
 * masuk" bisa membedakan dua ponsel. Tidak dikirim sebagai pengenal dan tidak dipakai untuk apa pun selain tampilan.
 * Contoh: "Samsung SM-S911B", "Google Pixel 8". Batas sama dengan server (100 karakter).
 */
object DeviceName {
    /** Batas panjang di server (`device_name` max:100). */
    const val MAX_LENGTH = 100

    fun read(): String? = format(manufacturer = Build.MANUFACTURER, model = Build.MODEL)
}

/**
 * Susun nama dari pabrikan dan model. Bila model sudah diawali pabrikan (mis. model "Samsung SM-S911B" dengan
 * pabrikan "samsung"), pabrikan tidak diulang. Huruf pertama dibesarkan. Kosong berarti null.
 */
internal fun format(manufacturer: String?, model: String?): String? {
    val maker = manufacturer?.trim().orEmpty()
    val name = model?.trim().orEmpty()
    val combined = when {
        name.isEmpty() -> maker
        maker.isEmpty() || name.startsWith(maker, ignoreCase = true) -> name
        else -> "$maker $name"
    }
    return combined.replaceFirstChar { it.uppercaseChar() }
        .take(DeviceName.MAX_LENGTH)
        .ifBlank { null }
}
