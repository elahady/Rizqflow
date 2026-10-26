package com.roziqrizal.rizqflow.ocr

import android.content.Context
import android.net.Uri
import com.roziqrizal.rizqflow.domain.ocr.KandidatTransaksi
import com.roziqrizal.rizqflow.domain.ocr.ParserTeksTransaksi

/**
 * Titik masuk tunggal input cerdas on-device (Tahap 11): gambar atau PDF masuk, kandidat transaksi keluar.
 * Dipanggil nanti oleh layar Draf/Tangkap otomatis (S27/S28). Layar itu belum ada; kandidat yang
 * dihasilkan di sini wajib ditinjau pengguna sebelum disimpan.
 *
 * Alur: gambar -> ML Kit (teks mentah) -> ParserTeksTransaksi.
 *       PDF    -> PdfBox (teks mentah) -> ParserTeksTransaksi.
 */
class PemindaiTransaksi(context: Context) {

    private val appContext = context.applicationContext
    private val pengenalGambar = PengenalanTeksGambar(appContext)
    private val pembacaPdf = EkstraksiTeksPdf(appContext)

    /** Kandidat dari screenshot mutasi e-wallet atau bank, atau struk. */
    suspend fun dariGambar(uri: Uri): List<KandidatTransaksi> =
        ParserTeksTransaksi.parse(pengenalGambar.kenali(uri))

    /** Kandidat dari PDF e-statement. PDF tanpa lapisan teks menghasilkan daftar kosong. */
    suspend fun dariPdf(uri: Uri): List<KandidatTransaksi> =
        ParserTeksTransaksi.parse(pembacaPdf.ambilTeks(uri))
}
