package com.roziqrizal.rizqflow.domain.ocr

import com.roziqrizal.rizqflow.domain.money.Money
import java.time.DateTimeException
import java.time.LocalDate

/**
 * Parser heuristik best-effort untuk teks mentah dari OCR gambar mutasi/struk dan dari PDF e-statement.
 *
 * Aturan yang sengaja sempit demi keamanan data uang:
 * - Nominal wajib diawali "Rp" (boleh dengan spasi atau titik). Pemisah ribuan titik ("Rp12.345",
 *   "Rp 1.000.000"). Desimal hanya ",00" yang diterima; desimal lain (mis. ",50") dianggap ambigu dan
 *   barisnya dilewati, karena rupiah tidak punya pecahan dan kita tidak mau membulatkan diam-diam.
 * - Nominal nol dilewati. Baris yang memuat kata "saldo" dilewati, karena itu saldo, bukan transaksi.
 * - Tanggal yang diterima: dd/mm/yyyy (pemisah / - .), yyyy-mm-dd, dan "dd Bulan yyyy" berbahasa
 *   Indonesia. Tanggal yang tidak valid (mis. 31/02) diabaikan. Bila sebuah baris tidak punya tanggal,
 *   dipakai tanggal pertama yang ditemukan di seluruh dokumen (judul mutasi biasanya memuat periode).
 * - Arah ditebak dari tanda "-"/"+" di depan Rp atau dari kata kunci umum. Bila tidak jelas, hasilnya
 *   [ArahKandidat.TIDAK_DIKETAHUI] dan pengguna yang memutuskan di layar tinjau.
 *
 * Tidak ada klaim akurasi di sini. Angka akurasi nyata baru bisa diukur dengan contoh asli dari pemilik
 * (item terpisah di docs/roadmap.md Tahap 11). Ubah aturan di sini hanya dengan tes baru yang menyertainya.
 */
object ParserTeksTransaksi {

    private val polaNominal = Regex(
        """Rp\.?\s?(\d{1,3}(?:\.\d{3})+|\d+)(,\d+)?""",
        RegexOption.IGNORE_CASE,
    )

    private val polaTanggalNumerik = Regex("""\b(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{4})\b""")
    private val polaTanggalIso = Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""")
    private val polaTanggalTeks = Regex(
        """\b(\d{1,2})\s+(jan|feb|mar|apr|mei|jun|jul|agu|agt|ags|sep|okt|nov|des)[a-z]*\.?\s+(\d{4})\b""",
        RegexOption.IGNORE_CASE,
    )

    private val bulanIndonesia = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "mei" to 5, "jun" to 6,
        "jul" to 7, "agu" to 8, "agt" to 8, "ags" to 8, "sep" to 9, "okt" to 10,
        "nov" to 11, "des" to 12,
    )

    private val kataKeluar = listOf("debit", "keluar", "bayar", "pembayaran", "transfer ke", "pembelian", "beli", "tarik")
    private val kataMasuk = listOf("kredit", "masuk", "terima", "diterima", "gaji", "transfer dari", "cashback", "refund")

    private val batasDeskripsi = 80

    /** Mengurai seluruh teks menjadi kandidat transaksi, satu kandidat per baris yang memuat nominal valid. */
    fun parse(teks: String): List<KandidatTransaksi> {
        val tanggalDokumen = cariTanggalPertama(teks)
        return teks.lines()
            .mapNotNull { baris -> uraiBaris(baris, tanggalDokumen) }
    }

    private fun uraiBaris(baris: String, tanggalDokumen: LocalDate?): KandidatTransaksi? {
        val teksBersih = baris.trim()
        if (teksBersih.isEmpty()) return null
        if (teksBersih.contains("saldo", ignoreCase = true)) return null

        val cocokNominal = polaNominal.find(teksBersih) ?: return null
        val pecahan = cocokNominal.groupValues[2]
        if (pecahan.isNotEmpty() && pecahan != ",00") return null

        val angka = cocokNominal.groupValues[1].replace(".", "").toLongOrNull() ?: return null
        if (angka == 0L) return null

        val cocokTanggal = cariTanggalDalamBaris(teksBersih)
        val tanggal = cocokTanggal?.second ?: tanggalDokumen
        val arah = tebakArah(teksBersih, cocokNominal)

        val deskripsi = buangTeksTerpakai(teksBersih, cocokNominal.range, cocokTanggal?.first)
            .take(batasDeskripsi)

        return KandidatTransaksi(
            nominal = Money.rupiah(angka),
            arah = arah,
            tanggal = tanggal,
            deskripsi = deskripsi,
            baris = teksBersih,
        )
    }

    private fun tebakArah(baris: String, cocokNominal: MatchResult): ArahKandidat {
        val awal = cocokNominal.range.first
        val sebelumNominal = baris.substring(0, awal).trimEnd()
        if (sebelumNominal.endsWith("-") || sebelumNominal.endsWith("(")) return ArahKandidat.KELUAR
        if (sebelumNominal.endsWith("+")) return ArahKandidat.MASUK

        val huruf = baris.lowercase()
        val adaKeluar = kataKeluar.any { huruf.contains(it) }
        val adaMasuk = kataMasuk.any { huruf.contains(it) }
        return when {
            adaKeluar && !adaMasuk -> ArahKandidat.KELUAR
            adaMasuk && !adaKeluar -> ArahKandidat.MASUK
            else -> ArahKandidat.TIDAK_DIKETAHUI
        }
    }

    /** Mengembalikan rentang tanggal yang ditemukan di baris beserta nilainya bila valid. */
    private fun cariTanggalDalamBaris(baris: String): Pair<IntRange, LocalDate>? {
        polaTanggalNumerik.find(baris)?.let { cocok ->
            val (hari, bulan, tahun) = cocok.destructured
            uraiTanggal(tahun.toInt(), bulan.toInt(), hari.toInt())?.let { return cocok.range to it }
        }
        polaTanggalIso.find(baris)?.let { cocok ->
            val (tahun, bulan, hari) = cocok.destructured
            uraiTanggal(tahun.toInt(), bulan.toInt(), hari.toInt())?.let { return cocok.range to it }
        }
        polaTanggalTeks.find(baris)?.let { cocok ->
            val (hari, namaBulan, tahun) = cocok.destructured
            val bulan = bulanIndonesia[namaBulan.lowercase()] ?: return null
            uraiTanggal(tahun.toInt(), bulan, hari.toInt())?.let { return cocok.range to it }
        }
        return null
    }

    private fun cariTanggalPertama(teks: String): LocalDate? {
        for (baris in teks.lines()) {
            cariTanggalDalamBaris(baris)?.let { return it.second }
        }
        return null
    }

    private fun uraiTanggal(tahun: Int, bulan: Int, hari: Int): LocalDate? =
        try {
            LocalDate.of(tahun, bulan, hari)
        } catch (_: DateTimeException) {
            null
        }

    /** Membuang nominal dan tanggal yang sudah terpakai, lalu merapikan sisa teks sebagai deskripsi. */
    private fun buangTeksTerpakai(baris: String, rentangNominal: IntRange, rentangTanggal: IntRange?): String {
        val tanpaNominal = baris.removeRange(rentangNominal.first, rentangNominal.last + 1)
        val tanpaTanggal = if (rentangTanggal == null) {
            tanpaNominal
        } else {
            val awalTanggal = rentangTanggal.first
            val akhirTanggal = rentangTanggal.last + 1
            // Indeks tanggal dihitung di baris asli; setelah nominal dibuang, geser bila tanggal berada sesudahnya.
            if (rentangTanggal.first > rentangNominal.last) {
                val geser = rentangNominal.last + 1 - rentangNominal.first
                tanpaNominal.removeRange(awalTanggal - geser, akhirTanggal - geser)
            } else {
                tanpaNominal.removeRange(awalTanggal, akhirTanggal)
            }
        }
        return tanpaTanggal
            .replace(Regex("""\s+"""), " ")
            .trim { it.isWhitespace() || it in setOf('-', '|', ':', ',', ';', '.', '(', ')', '+') }
    }
}
