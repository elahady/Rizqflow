package com.roziqrizal.rizqflow.ui.report.pdf

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.OutputStream

/**
 * Menulis hasil [PdfPaginator] ke berkas PDF lewat `android.graphics.pdf.PdfDocument` bawaan Android:
 * tanpa pustaka tambahan, tanpa jaringan, dan datanya tidak pernah meninggalkan perangkat. Selalu
 * berlatar putih dan berwarna cetak, apa pun tema aplikasi sedang aktif.
 *
 * Tidak ada tes otomatis untuk kelas ini (PdfDocument butuh runtime Android); logika yang bisa salah
 * ada di [PdfPaginator] yang diuji. Gambar sungguhan perlu dicek di perangkat.
 */
object PdfReportWriter {

    /** [footer] menerima nomor halaman (mulai 1) dan jumlah halaman. */
    fun write(pages: List<List<PlacedItem>>, spec: PdfPageSpec = PdfPageSpec(), footer: (page: Int, total: Int) -> String, out: OutputStream) {
        val document = PdfDocument()
        try {
            val regular = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT }
            val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
            val rect = Paint()

            pages.forEachIndexed { index, items ->
                val info = PdfDocument.PageInfo.Builder(spec.width.toInt(), spec.height.toInt(), index + 1).create()
                val page = document.startPage(info)
                val canvas = page.canvas
                items.forEach { item ->
                    when (item) {
                        is PlacedItem.Text -> {
                            val paint = (if (item.bold) bold else regular).apply {
                                textSize = item.size
                                color = item.color
                                textAlign = if (item.rightAligned) Paint.Align.RIGHT else Paint.Align.LEFT
                            }
                            canvas.drawText(item.text, item.x, item.baseline, paint)
                        }

                        is PlacedItem.Rect -> {
                            rect.color = item.color
                            canvas.drawRect(item.x, item.top, item.x + item.width, item.top + item.height, rect)
                        }
                    }
                }
                regular.apply {
                    textSize = FOOTER_SIZE
                    color = PdfColors.MUTED
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText(footer(index + 1, pages.size), spec.width / 2f, spec.height - spec.margin + FOOTER_SIZE, regular)
                document.finishPage(page)
            }
            document.writeTo(out)
        } finally {
            document.close()
        }
    }

    private const val FOOTER_SIZE = 9f
}
