package com.roziqrizal.rizqflow.ui.report.pdf

/*
 * Tata letak halaman laporan PDF, murni Kotlin tanpa Android supaya pemenggalan halaman dan pemotongan
 * baris bisa diuji dengan kotlin.test biasa. [PdfReportWriter] hanya menggambar hasilnya ke PdfDocument.
 */

/** Blok isi laporan, dari atas ke bawah. Warna berupa ARGB. */
sealed interface PdfBlock {
    data class Title(val text: String) : PdfBlock
    data class Subtitle(val text: String) : PdfBlock

    /** Judul bagian; selalu ikut halaman yang sama dengan blok sesudahnya. */
    data class Heading(val text: String) : PdfBlock

    /** Satu baris: [label] di kiri, [value] rata kanan. */
    data class Line(val label: String, val value: String, val strong: Boolean = false) : PdfBlock

    /** Satu baris dengan batang proporsional ([fraction] 0..1) di bawahnya. */
    data class Bar(val label: String, val value: String, val fraction: Float, val color: Int) : PdfBlock

    /** Paragraf pendek; dipotong per kata mengikuti lebar halaman. */
    data class Note(val text: String) : PdfBlock

    data class Spacer(val height: Float) : PdfBlock
}

/** Hasil tata letak: koordinat dalam poin PDF (1/72 inci), `baseline` untuk teks dan `top` untuk persegi. */
sealed interface PlacedItem {
    data class Text(
        val text: String,
        val x: Float,
        val baseline: Float,
        val size: Float,
        val bold: Boolean,
        val color: Int,
        val rightAligned: Boolean = false,
    ) : PlacedItem

    data class Rect(val x: Float, val top: Float, val width: Float, val height: Float, val color: Int) : PlacedItem
}

/** Ukuran halaman dalam poin; bawaan A4. [footerReserve] disisihkan di bawah untuk nomor halaman. */
data class PdfPageSpec(
    val width: Float = 595f,
    val height: Float = 842f,
    val margin: Float = 40f,
    val footerReserve: Float = 28f,
) {
    val contentWidth: Float get() = width - 2 * margin
    val bottomLimit: Float get() = height - margin - footerReserve
}

object PdfColors {
    const val INK = 0xFF1B1C1B.toInt()
    const val MUTED = 0xFF424845.toInt()
    const val TRACK = 0xFFE3E2E0.toInt()
}

class PdfPaginator(private val spec: PdfPageSpec = PdfPageSpec()) {

    /** Menata [blocks] jadi halaman; selalu menghasilkan minimal satu halaman. */
    fun paginate(blocks: List<PdfBlock>): List<List<PlacedItem>> {
        val units = blocks.flatMap(::expand)
        val pages = mutableListOf<MutableList<PlacedItem>>()
        var page = mutableListOf<PlacedItem>().also { pages += it }
        var y = spec.margin

        units.forEachIndexed { index, unit ->
            // Judul bagian butuh muat bersama blok sesudahnya, kalau tidak pindah halaman dulu.
            val needed = unit.height + if (unit.keepWithNext) units.getOrNull(index + 1)?.height ?: 0f else 0f
            if (y + needed > spec.bottomLimit && y > spec.margin) {
                page = mutableListOf<PlacedItem>().also { pages += it }
                y = spec.margin
            }
            unit.place(page, y, spec)
            y += unit.height
        }
        return pages
    }

    // ------------------------------------------------------------------ satuan tata letak

    private interface LayoutUnit {
        val height: Float
        val keepWithNext: Boolean get() = false
        fun place(page: MutableList<PlacedItem>, top: Float, spec: PdfPageSpec)
    }

    private fun expand(block: PdfBlock): List<LayoutUnit> = when (block) {
        is PdfBlock.Title -> listOf(TextUnit(block.text, size = 22f, bold = true, height = 34f))
        is PdfBlock.Subtitle -> listOf(TextUnit(block.text, size = 12f, bold = false, height = 22f, color = PdfColors.MUTED))
        is PdfBlock.Heading -> listOf(TextUnit(block.text, size = 14f, bold = true, height = 32f, topPad = 12f, keep = true))
        is PdfBlock.Line -> listOf(LineUnit(block.label, block.value, block.strong))
        is PdfBlock.Bar -> listOf(BarUnit(block))
        is PdfBlock.Spacer -> listOf(GapUnit(block.height))
        is PdfBlock.Note -> {
            val lines = wrap(block.text, maxChars(NOTE_SIZE, spec.contentWidth))
            lines.mapIndexed { i, text ->
                // Jarak tambahan hanya sesudah baris terakhir paragraf.
                TextUnit(text, size = NOTE_SIZE, bold = false, height = NOTE_LINE + if (i == lines.lastIndex) 6f else 0f, color = PdfColors.MUTED)
            }
        }
    }

    private class TextUnit(
        val text: String,
        val size: Float,
        val bold: Boolean,
        override val height: Float,
        val color: Int = PdfColors.INK,
        val topPad: Float = 0f,
        keep: Boolean = false,
    ) : LayoutUnit {
        override val keepWithNext: Boolean = keep

        override fun place(page: MutableList<PlacedItem>, top: Float, spec: PdfPageSpec) {
            page += PlacedItem.Text(text, spec.margin, top + topPad + size, size, bold, color)
        }
    }

    private class LineUnit(val label: String, val value: String, val strong: Boolean) : LayoutUnit {
        override val height: Float = 20f

        override fun place(page: MutableList<PlacedItem>, top: Float, spec: PdfPageSpec) {
            val baseline = top + 14f
            page += PlacedItem.Text(label, spec.margin, baseline, 11f, strong, PdfColors.INK)
            page += PlacedItem.Text(value, spec.margin + spec.contentWidth, baseline, 11f, strong, PdfColors.INK, rightAligned = true)
        }
    }

    private class BarUnit(val bar: PdfBlock.Bar) : LayoutUnit {
        override val height: Float = 32f

        override fun place(page: MutableList<PlacedItem>, top: Float, spec: PdfPageSpec) {
            val baseline = top + 12f
            page += PlacedItem.Text(bar.label, spec.margin, baseline, 11f, false, PdfColors.INK)
            page += PlacedItem.Text(bar.value, spec.margin + spec.contentWidth, baseline, 11f, false, PdfColors.MUTED, rightAligned = true)
            val barTop = top + 18f
            page += PlacedItem.Rect(spec.margin, barTop, spec.contentWidth, BAR_HEIGHT, PdfColors.TRACK)
            val filled = spec.contentWidth * bar.fraction.coerceIn(0f, 1f)
            if (filled > 0f) page += PlacedItem.Rect(spec.margin, barTop, filled, BAR_HEIGHT, bar.color)
        }
    }

    private class GapUnit(override val height: Float) : LayoutUnit {
        override fun place(page: MutableList<PlacedItem>, top: Float, spec: PdfPageSpec) = Unit
    }

    companion object {
        private const val NOTE_SIZE = 10f
        private const val NOTE_LINE = 14f
        private const val BAR_HEIGHT = 6f

        /**
         * Perkiraan jumlah karakter per baris: lebar rata-rata huruf font bawaan sekitar 0,55 kali
         * ukurannya (sengaja agak lebar supaya tidak melewati tepi kanan). Tanpa pengukuran Paint
         * sungguhan agar tetap murni; teks yang jauh lebih sempit hanya menyisakan margin.
         */
        internal fun maxChars(size: Float, width: Float): Int = (width / (size * 0.55f)).toInt().coerceAtLeast(1)

        /** Memenggal [text] per kata ke baris sepanjang paling banyak [maxChars]; kata terlalu panjang dipotong paksa. */
        internal fun wrap(text: String, maxChars: Int): List<String> {
            val lines = mutableListOf<String>()
            var current = StringBuilder()
            for (rawWord in text.split(' ').filter { it.isNotEmpty() }) {
                var word = rawWord
                while (word.length > maxChars) {
                    if (current.isNotEmpty()) {
                        lines += current.toString()
                        current = StringBuilder()
                    }
                    lines += word.take(maxChars)
                    word = word.drop(maxChars)
                }
                val separator = if (current.isEmpty()) 0 else 1
                if (current.length + separator + word.length > maxChars) {
                    lines += current.toString()
                    current = StringBuilder(word)
                } else {
                    if (separator == 1) current.append(' ')
                    current.append(word)
                }
            }
            if (current.isNotEmpty()) lines += current.toString()
            return lines
        }
    }
}
