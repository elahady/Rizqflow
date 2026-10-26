package com.roziqrizal.rizqflow.ocr

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Membaca teks dari PDF e-statement dengan PdfBox-Android. Hanya PDF yang punya lapisan teks yang bisa
 * dibaca; PDF hasil scan (gambar saja) menghasilkan teks kosong, dan itu bukan galat. Tidak ada data
 * yang dikirim ke luar perangkat.
 */
class EkstraksiTeksPdf(private val context: Context) {

    /** Mengembalikan teks dari PDF di [uri]. Mengembalikan string kosong bila PDF tidak punya lapisan teks. */
    suspend fun ambilTeks(uri: Uri): String = withContext(Dispatchers.IO) {
        // PdfBox-Android wajib diinisialisasi sekali sebelum memuat dokumen (memuat sumber daya font).
        PDFBoxResourceLoader.init(context.applicationContext)
        val aliranMasuk = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Berkas PDF tidak bisa dibuka: $uri")
        aliranMasuk.use { aliran ->
            PDDocument.load(aliran).use { dokumen ->
                PDFTextStripper().getText(dokumen)
            }
        }
    }
}
