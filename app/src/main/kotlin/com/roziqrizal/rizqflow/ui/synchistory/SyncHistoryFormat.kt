package com.roziqrizal.rizqflow.ui.synchistory

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Waktu push/pull sesuai bahasa dan zona waktu perangkat (tanggal medium, jam pendek). */
internal fun formatSyncTime(at: Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(zone).format(at)

/** Tanggal saja untuk "sejak" dan kompaksi. */
internal fun formatSyncDate(at: Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).withZone(zone).format(at)

/**
 * Ukuran data yang ramah: byte, KB, atau MB satu desimal (basis 1024). Bukan uang, jadi pecahan boleh; angka dipotong ke
 * bawah supaya "hampir penuh" tidak pernah tampil lebih penuh dari kenyataan. Negatif dianggap 0.
 */
internal fun formatSyncBytes(bytes: Long, locale: Locale = Locale.getDefault()): String {
    val safe = bytes.coerceAtLeast(0)
    return when {
        safe < 1024L -> "$safe B"
        safe < 1024L * 1024L -> String.format(locale, "%.1f KB", floorTenth(safe, 1024L))
        else -> String.format(locale, "%.1f MB", floorTenth(safe, 1024L * 1024L))
    }
}

/** [value] dibagi [unit] dipotong ke bawah pada satu desimal, tanpa kesalahan pembulatan kenaikan. */
private fun floorTenth(value: Long, unit: Long): Double = (value * 10L / unit) / 10.0

/** Id perangkat panjang (UUID) dipendekkan untuk tampilan: delapan karakter pertama. */
internal fun shortDeviceId(deviceId: String): String = deviceId.take(8)
