package com.roziqrizal.rizqflow.ui.report.pdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfPaginatorTest {

    private val spec = PdfPageSpec()
    private val paginator = PdfPaginator(spec)

    private fun List<List<PlacedItem>>.texts(page: Int) = this[page].filterIsInstance<PlacedItem.Text>()

    @Test
    fun `tanpa blok tetap menghasilkan satu halaman kosong`() {
        val pages = paginator.paginate(emptyList())

        assertEquals(1, pages.size)
        assertTrue(pages.single().isEmpty())
    }

    @Test
    fun `judul dan baris ditempatkan di margin, nilai rata kanan di tepi isi`() {
        val pages = paginator.paginate(listOf(PdfBlock.Title("Laporan"), PdfBlock.Line("Pemasukan", "Rp 1.000", strong = true)))

        val title = pages.texts(0)[0]
        assertEquals("Laporan", title.text)
        assertEquals(spec.margin, title.x)
        assertEquals(spec.margin + 22f, title.baseline) // atas + ukuran huruf judul

        val label = pages.texts(0)[1]
        val value = pages.texts(0)[2]
        assertEquals("Pemasukan", label.text)
        assertEquals(spec.margin, label.x)
        assertEquals("Rp 1.000", value.text)
        assertTrue(value.rightAligned)
        assertEquals(spec.margin + spec.contentWidth, value.x)
        assertTrue(label.bold && value.bold)
        // Baris berada di bawah judul (34pt tinggi judul).
        assertEquals(spec.margin + 34f + 14f, label.baseline)
    }

    @Test
    fun `isi yang panjang berpindah halaman dan tidak ada yang melewati batas bawah`() {
        val blocks = (1..120).map { PdfBlock.Line("Baris $it", "Rp $it") }

        val pages = paginator.paginate(blocks)

        assertTrue(pages.size > 1, "120 baris harus lebih dari satu halaman")
        pages.forEachIndexed { index, items ->
            items.filterIsInstance<PlacedItem.Text>().forEach {
                assertTrue(it.baseline <= spec.bottomLimit, "halaman ${index + 1}: baseline ${it.baseline} melewati batas ${spec.bottomLimit}")
                assertTrue(it.baseline >= spec.margin, "baseline di atas margin atas")
            }
        }
        // Tidak ada baris yang hilang atau ganda.
        val labels = pages.flatten().filterIsInstance<PlacedItem.Text>().filter { !it.rightAligned }.map { it.text }
        assertEquals((1..120).map { "Baris $it" }, labels)
    }

    @Test
    fun `halaman kedua dimulai lagi dari margin atas`() {
        val pages = paginator.paginate((1..120).map { PdfBlock.Line("Baris $it", "Rp $it") })

        val firstOnSecond = pages.texts(1).first()
        assertEquals(spec.margin + 14f, firstOnSecond.baseline)
    }

    @Test
    fun `judul bagian tidak ditinggal sendirian di dasar halaman`() {
        val small = PdfPageSpec(width = 300f, height = 200f, margin = 20f, footerReserve = 0f) // batas bawah 180
        // Enam baris (tinggi 20) memenuhi sampai y=140. Judul bagian (32) muat sendiri (172),
        // tapi bersama batang sesudahnya (32) tidak (204), jadi harus pindah halaman.
        val blocks = (1..6).map { PdfBlock.Line("Baris $it", "x") } +
            PdfBlock.Heading("Per ruang") +
            PdfBlock.Bar("Keluarga", "Rp 1", 0.5f, 0xFF000000.toInt())

        val pages = PdfPaginator(small).paginate(blocks)

        assertEquals(2, pages.size)
        assertEquals("Per ruang", pages.texts(1).first().text)
        assertTrue(pages.texts(0).none { it.text == "Per ruang" })
    }

    @Test
    fun `judul bagian di akhir tanpa blok sesudahnya tidak bermasalah`() {
        val pages = paginator.paginate(listOf(PdfBlock.Heading("Sendirian")))

        assertEquals(1, pages.size)
        assertEquals("Sendirian", pages.texts(0).single().text)
    }

    @Test
    fun `batang diisi sebanding fraction, dijepit ke nol sampai satu`() {
        fun fills(fraction: Float): List<PlacedItem.Rect> =
            paginator.paginate(listOf(PdfBlock.Bar("x", "y", fraction, 0xFF123456.toInt()))).flatten().filterIsInstance<PlacedItem.Rect>()

        val half = fills(0.5f)
        assertEquals(2, half.size) // lintasan + isi
        assertEquals(PdfColors.TRACK, half[0].color)
        assertEquals(spec.contentWidth, half[0].width)
        assertEquals(spec.contentWidth * 0.5f, half[1].width)

        assertEquals(spec.contentWidth, fills(3f)[1].width)
        assertEquals(1, fills(0f).size) // tanpa isi, hanya lintasan
        assertEquals(1, fills(-1f).size)
    }

    @Test
    fun `paragraf dipenggal per kata tanpa kehilangan kata`() {
        val text = (1..80).joinToString(" ") { "kata$it" }

        val pages = paginator.paginate(listOf(PdfBlock.Note(text)))

        val lines = pages.texts(0).map { it.text }
        assertTrue(lines.size > 1, "paragraf panjang harus lebih dari satu baris")
        val limit = PdfPaginator.maxChars(10f, spec.contentWidth)
        assertTrue(lines.all { it.length <= limit }, "tidak ada baris melebihi $limit karakter")
        assertEquals(text, lines.joinToString(" "))
    }

    @Test
    fun `kata yang lebih panjang dari satu baris dipotong paksa`() {
        val wrapped = PdfPaginator.wrap("a".repeat(25), maxChars = 10)

        assertEquals(listOf("a".repeat(10), "a".repeat(10), "a".repeat(5)), wrapped)
    }

    @Test
    fun `memenggal teks kosong atau hanya spasi menghasilkan tanpa baris`() {
        assertTrue(PdfPaginator.wrap("", 10).isEmpty())
        assertTrue(PdfPaginator.wrap("   ", 10).isEmpty())
    }
}
