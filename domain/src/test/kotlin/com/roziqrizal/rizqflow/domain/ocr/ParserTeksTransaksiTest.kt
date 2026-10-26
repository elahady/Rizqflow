package com.roziqrizal.rizqflow.domain.ocr

import com.roziqrizal.rizqflow.domain.money.Currency
import com.roziqrizal.rizqflow.domain.money.Money
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CATATAN PENTING: semua teks di berkas ini adalah CONTOH ILUSTRATIF yang disusun sendiri berdasarkan
 * pola umum notifikasi dan mutasi bank/e-wallet Indonesia. Ini BUKAN data nyata dan BUKAN hasil OCR
 * dari tangkapan layar sungguhan. Tes ini hanya membuktikan logika parsing bekerja pada pola yang
 * kita duga; tes ini tidak membuktikan akurasi di lapangan. Kalibrasi sungguhan menunggu sekitar 30
 * sampel asli dari pemilik (item terpisah di docs/roadmap.md Tahap 11).
 */
class ParserTeksTransaksiTest {

    @Test
    fun `mutasi e-wallet debit dengan tanda minus di depan Rp`() {
        // Contoh ilustratif, bukan data nyata.
        val hasil = ParserTeksTransaksi.parse("12/10/2026 Pembayaran QRIS Warung Kopi -Rp 25.000")

        assertEquals(1, hasil.size)
        val kandidat = hasil.single()
        assertEquals(Money.rupiah(25_000), kandidat.nominal)
        assertEquals(ArahKandidat.KELUAR, kandidat.arah)
        assertEquals(LocalDate.of(2026, 10, 12), kandidat.tanggal)
        assertEquals("Pembayaran QRIS Warung Kopi", kandidat.deskripsi)
    }

    @Test
    fun `mutasi bank transfer masuk dengan tanggal berbahasa Indonesia dan desimal ,00`() {
        // Contoh ilustratif, bukan data nyata.
        val hasil = ParserTeksTransaksi.parse("13 Okt 2026 Transfer dari Budi Rp 1.500.000,00")

        val kandidat = hasil.single()
        assertEquals(Money.rupiah(1_500_000), kandidat.nominal)
        assertEquals(ArahKandidat.MASUK, kandidat.arah)
        assertEquals(LocalDate.of(2026, 10, 13), kandidat.tanggal)
        assertEquals("Transfer dari Budi", kandidat.deskripsi)
    }

    @Test
    fun `tanggal format ISO dan tanda plus di depan Rp menandai pemasukan`() {
        // Contoh ilustratif, bukan data nyata.
        val kandidat = ParserTeksTransaksi.parse("2026-10-05 Gaji Kantor +Rp 8.500.000").single()

        assertEquals(Money.rupiah(8_500_000), kandidat.nominal)
        assertEquals(ArahKandidat.MASUK, kandidat.arah)
        assertEquals(LocalDate.of(2026, 10, 5), kandidat.tanggal)
        assertEquals("Gaji Kantor", kandidat.deskripsi)
    }

    @Test
    fun `nominal tanpa pemisah ribuan dan tanpa spasi setelah Rp`() {
        // Contoh ilustratif, bukan data nyata.
        val kandidat = ParserTeksTransaksi.parse("Rp12000 Pulsa").single()

        assertEquals(Money.rupiah(12_000), kandidat.nominal)
        assertEquals("Pulsa", kandidat.deskripsi)
    }

    @Test
    fun `struk belanja menghasilkan kandidat dari setiap baris yang memuat nominal`() {
        // Contoh ilustratif struk, bukan data nyata. Baris judul dan alamat tanpa "Rp" dilewati.
        val teks = """
            TOKO CONTOH SEJAHTERA
            Jl. Contoh No. 1
            Roti Tawar Rp 18.000
            Susu Kotak Rp 12.500,00
            Total Rp 30.500
        """.trimIndent()

        val hasil = ParserTeksTransaksi.parse(teks)

        // Baris "Susu Kotak" punya desimal ,00 yang valid. Baris dengan pecahan lain akan dilewati (lihat tes di bawah).
        assertEquals(listOf(18_000L, 12_500L, 30_500L), hasil.map { it.nominal.minor })
        assertTrue(hasil.all { it.nominal.currency == Currency.IDR })
    }

    @Test
    fun `baris saldo tidak dianggap transaksi`() {
        // Contoh ilustratif, bukan data nyata.
        val hasil = ParserTeksTransaksi.parse("Saldo akhir Rp 2.000.000")

        assertTrue(hasil.isEmpty())
    }

    @Test
    fun `nominal dengan pecahan selain ,00 dilewati karena rupiah tidak punya pecahan`() {
        // Contoh ilustratif, bukan data nyata.
        val hasil = ParserTeksTransaksi.parse("Pembelian Rp 12.345,50")

        assertTrue(hasil.isEmpty())
    }

    @Test
    fun `nominal nol dilewati`() {
        // Contoh ilustratif, bukan data nyata.
        assertTrue(ParserTeksTransaksi.parse("Biaya admin Rp 0").isEmpty())
    }

    @Test
    fun `nominal yang melampaui Long dilewati tanpa membungkus diam-diam`() {
        // Contoh ilustratif, bukan data nyata.
        assertTrue(ParserTeksTransaksi.parse("Rp 99999999999999999999").isEmpty())
    }

    @Test
    fun `baris tanpa tanggal memakai tanggal pertama dokumen`() {
        // Contoh ilustratif, bukan data nyata: judul periode di baris pertama, lalu satu transaksi tanpa tanggal.
        val teks = """
            Periode 01/10/2026 - 31/10/2026
            Indomaret Rp 45.000
        """.trimIndent()

        val kandidat = ParserTeksTransaksi.parse(teks).single()

        assertEquals(LocalDate.of(2026, 10, 1), kandidat.tanggal)
        assertEquals(ArahKandidat.TIDAK_DIKETAHUI, kandidat.arah)
        assertEquals("Indomaret", kandidat.deskripsi)
    }

    @Test
    fun `tanggal tidak valid seperti 31 Februari diabaikan`() {
        // Contoh ilustratif, bukan data nyata.
        val kandidat = ParserTeksTransaksi.parse("31/02/2026 Belanja Rp 10.000").single()

        assertEquals(Money.rupiah(10_000), kandidat.nominal)
        assertEquals(null, kandidat.tanggal)
    }

    @Test
    fun `teks kosong menghasilkan daftar kosong`() {
        assertTrue(ParserTeksTransaksi.parse("").isEmpty())
        assertTrue(ParserTeksTransaksi.parse("   \n  \n").isEmpty())
    }

    @Test
    fun `baris asal disimpan utuh untuk ditinjau pengguna`() {
        // Contoh ilustratif, bukan data nyata.
        val kandidat = ParserTeksTransaksi.parse("  12/10/2026 Pulsa Rp 50.000  ").single()

        assertEquals("12/10/2026 Pulsa Rp 50.000", kandidat.baris)
    }
}
