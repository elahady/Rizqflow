package com.roziqrizal.rizqflow.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Ikon garis 24 dp dengan tebal 1,75, persis sama dengan prototipe HTML (docs/design/prototype.js),
 * dibuat dari data path SVG yang sama supaya tidak menambah pustaka ikon. Warna diambil dari
 * `LocalContentColor` lewat `Icon`, jadi warna di sini hanya penanda.
 */
private fun strokeIcon(name: String, vararg pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        pathData.forEach { data ->
            addPath(
                pathData = addPathNodes(data),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.75f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

object RizqflowIcons {
    val Denah: ImageVector = strokeIcon(
        "Denah",
        "M5.5 4h4A1.5 1.5 0 0 1 11 5.5v4A1.5 1.5 0 0 1 9.5 11h-4A1.5 1.5 0 0 1 4 9.5v-4A1.5 1.5 0 0 1 5.5 4z",
        "M14.5 4h4A1.5 1.5 0 0 1 20 5.5v4a1.5 1.5 0 0 1-1.5 1.5h-4A1.5 1.5 0 0 1 13 9.5v-4A1.5 1.5 0 0 1 14.5 4z",
        "M5.5 13h4A1.5 1.5 0 0 1 11 14.5v4A1.5 1.5 0 0 1 9.5 20h-4A1.5 1.5 0 0 1 4 18.5v-4A1.5 1.5 0 0 1 5.5 13z",
        "M14.5 13h4a1.5 1.5 0 0 1 1.5 1.5v4a1.5 1.5 0 0 1-1.5 1.5h-4a1.5 1.5 0 0 1-1.5-1.5v-4a1.5 1.5 0 0 1 1.5-1.5z",
    )

    val Transaksi: ImageVector = strokeIcon(
        "Transaksi",
        "M8 6.5h12M8 12h12M8 17.5h12",
        "M4 6.5h.01M4 12h.01M4 17.5h.01",
    )

    val Ruang: ImageVector = strokeIcon(
        "Ruang",
        "M4 20V5.5A1.5 1.5 0 0 1 5.5 4h9A1.5 1.5 0 0 1 16 5.5V20",
        "M2.5 20h19",
        "M13 12h.01",
        "M16 9h3.5A1.5 1.5 0 0 1 21 10.5V20",
    )

    val Lainnya: ImageVector = strokeIcon("Lainnya", "M5 12h.01M12 12h.01M19 12h.01")

    val Tambah: ImageVector = strokeIcon("Tambah", "M12 5v14M5 12h14")

    val Tutup: ImageVector = strokeIcon("Tutup", "M6 6l12 12M18 6L6 18")

    val PanahKiri: ImageVector = strokeIcon("PanahKiri", "m15 5-7 7 7 7")

    val PanahKanan: ImageVector = strokeIcon("PanahKanan", "m9 5 7 7-7 7")

    val PanahAtas: ImageVector = strokeIcon("PanahAtas", "M12 19V5", "M6 11l6-6 6 6")

    val PanahBawah: ImageVector = strokeIcon("PanahBawah", "M12 5v14", "M6 13l6 6 6-6")

    val Transfer: ImageVector = strokeIcon("Transfer", "M4 8h14", "M14 4l4 4-4 4", "M20 16H6", "M10 12l-4 4 4 4")

    val Cari: ImageVector = strokeIcon("Cari", "M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14z", "M20 20l-4-4")

    val Saring: ImageVector = strokeIcon("Saring", "M4 5h16l-6.5 8v5.5l-3-1.5V13z")

    // Ikon ruang dan status, sama dengan prototipe HTML.
    val Hati: ImageVector = strokeIcon(
        "Hati",
        "M12 20.5s-7.5-4.6-7.5-10.3A4.2 4.2 0 0 1 12 7.6a4.2 4.2 0 0 1 7.5 2.6c0 5.7-7.5 10.3-7.5 10.3z",
    )

    val Tunas: ImageVector = strokeIcon(
        "Tunas",
        "M12 21v-9",
        "M12 13c0-4.2-3-6.5-7-6.5 0 4.2 3 6.5 7 6.5z",
        "M12 11c0-3.4 2.4-5.4 6-5.4 0 3.4-2.4 5.4-6 5.4z",
    )

    val Bintang: ImageVector = strokeIcon("Bintang", "m12 3.5 2.5 5.2 5.7.8-4.1 4 1 5.7L12 16.5l-5.1 2.7 1-5.7-4.1-4 5.7-.8z")

    val Petir: ImageVector = strokeIcon("Petir", "M13 3 5 13.5h6L10 21l8-10.5h-6z")

    val Mikrofon: ImageVector = strokeIcon(
        "Mikrofon",
        "M12 3.5a3 3 0 0 0-3 3v5.5a3 3 0 0 0 6 0V6.5a3 3 0 0 0-3-3z",
        "M5.5 11.5a6.5 6.5 0 0 0 13 0",
        "M12 18v2.5",
    )

    val Rumah: ImageVector = strokeIcon(
        "Rumah",
        "M3.5 11.2 12 3.8l8.5 7.4",
        "M5.5 10v10h13V10",
        "M10 20v-5.5h4V20",
    )

    val Jam: ImageVector = strokeIcon("Jam", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0z", "M12 7v5l3 2")

    val Lingkaran: ImageVector = strokeIcon("Lingkaran", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0z")

    val Centang: ImageVector = strokeIcon(
        "Centang",
        "M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0z",
        "m8 12.4 2.8 2.8 5.4-5.6",
    )

    val Peringatan: ImageVector = strokeIcon(
        "Peringatan",
        "M12 3.6 21.4 20H2.6z",
        "M12 10v4.6",
        "M12 17.4h.01",
    )

    /** Haul dan kalender Hijriyah (S14, S16). */
    val Bulan: ImageVector = strokeIcon("Bulan", "M15.5 3.5a8.5 8.5 0 1 0 5 8.3A6.8 6.8 0 0 1 15.5 3.5z")
}
