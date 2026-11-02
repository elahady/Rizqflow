package com.roziqrizal.rizqflow.domain.ledger

import com.roziqrizal.rizqflow.domain.auth.runSuspend
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.money.Money
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomDetailTest {

    private fun rupiah(n: Long) = Money.rupiah(n)

    private val sept = YearMonth.of(2026, 9)

    private fun load(f: LedgerFixture, room: String, month: YearMonth = sept, today: LocalDate = f.today) =
        runSuspend { RoomDetailLoader(f.store, f.store, f.store).load(f.room(room).id, month, today) }!!

    private fun expense(f: LedgerFixture, room: String, category: String, amount: Long, day: Int, month: Int = 9, note: String? = null) =
        runSuspend { f.ledger.recordExpense(NewExpense(rupiah(amount), f.account.id, f.room(room).id, f.category(room, category).id, LocalDate.of(2026, month, day), note)) }

    @Test
    fun `ruang yang tidak ada menghasilkan null`() {
        val f = LedgerFixture().standard()

        assertNull(runSuspend { RoomDetailLoader(f.store, f.store, f.store).load(RoomId("tidak-ada"), sept, f.today) })
    }

    @Test
    fun `kartu memuat jatah terpakai dan status ruang itu`() {
        val f = LedgerFixture().standard()
        f.income(5_000_000)
        expense(f, "Keluarga", "Belanja bulanan", 2_550_000, 3)

        val detail = load(f, "Keluarga")

        assertEquals(rupiah(3_000_000), detail.card.allocated)
        assertEquals(rupiah(2_550_000), detail.card.spent)
        assertEquals(8_500, detail.card.progressBp)
        assertEquals(RoomStatus.PERLU_PERHATIAN, detail.card.status)
    }

    @Test
    fun `pos tanpa bobot dibandingkan dengan jatah ruang dan diurutkan menurut pemakaian`() {
        val f = LedgerFixture().standard()
        f.income(5_000_000)
        expense(f, "Keluarga", "Belanja bulanan", 1_200_000, 3)
        expense(f, "Keluarga", "Listrik dan air", 300_000, 5)
        expense(f, "Keluarga", "Belanja bulanan", 300_000, 9)

        val pos = load(f, "Keluarga").categories

        assertEquals(listOf("Belanja bulanan", "Listrik dan air", "Sekolah", "Lain-lain"), pos.map { it.category.name })
        assertEquals(rupiah(1_500_000), pos.first().spent)
        assertEquals(rupiah(3_000_000), pos.first().budget)
        assertEquals(5_000, pos.first().progressBp)
        assertEquals(0, pos.last().progressBp)
    }

    @Test
    fun `pos dengan bobot mendapat irisan jatah menurut bobotnya`() {
        val f = LedgerFixture().standard()
        f.income(5_000_000)
        // Jatah Keluarga Rp 3.000.000; bobot 3:1 memberi Rp 2.250.000 dan Rp 750.000.
        val belanja = f.category("Keluarga", "Belanja bulanan")
        val listrik = f.category("Keluarga", "Listrik dan air")
        f.store.categoryRows[belanja.id] = belanja.copy(weight = 3)
        f.store.categoryRows[listrik.id] = listrik.copy(weight = 1)
        expense(f, "Keluarga", "Listrik dan air", 750_000, 5)

        val pos = load(f, "Keluarga").categories

        val l = pos.first { it.category.name == "Listrik dan air" }
        assertEquals(rupiah(750_000), l.budget)
        assertEquals(10_000, l.progressBp)
        assertEquals(rupiah(2_250_000), pos.first { it.category.name == "Belanja bulanan" }.budget)
    }

    @Test
    fun `pos sistem hanya tampil bila dipakai bulan itu`() {
        val f = LedgerFixture().standard()
        f.income(5_000_000)

        assertTrue(load(f, "Memberi").categories.none { it.category.isSystem })

        expense(f, "Memberi", "Zakat mal", 100_000, 4)

        assertEquals("Zakat mal", load(f, "Memberi").categories.first().category.name)
    }

    @Test
    fun `pos yang diarsipkan tampil bila bulan itu memakainya`() {
        val f = LedgerFixture().standard()
        f.income(5_000_000)
        expense(f, "Keluarga", "Sekolah", 400_000, 4)
        val sekolah = f.category("Keluarga", "Sekolah")
        f.store.categoryRows[sekolah.id] = sekolah.copy(archived = true)

        val pos = load(f, "Keluarga").categories

        assertTrue(pos.any { it.category.name == "Sekolah" && it.spent == rupiah(400_000) })
        assertTrue(load(f, "Keluarga", YearMonth.of(2026, 8)).categories.none { it.category.name == "Sekolah" })
    }

    @Test
    fun `transaksi terbaru paling banyak lima terbaru dulu dan hanya pengeluaran ruang itu`() {
        val f = LedgerFixture().standard()
        f.income(5_000_000)
        (1..7).forEach { expense(f, "Keluarga", "Belanja bulanan", 10_000L * it, it, note = "n$it") }
        expense(f, "Diri", "Investasi", 999_000, 8, note = "bukan")

        val recent = load(f, "Keluarga").recent

        assertEquals(RoomDetailLoader.RECENT_LIMIT, recent.size)
        assertEquals(listOf("n7", "n6", "n5", "n4", "n3"), recent.map { it.transaction.note })
        assertTrue(recent.all { it.categoryName == "Belanja bulanan" })
    }

    @Test
    fun `bulan lain hanya memuat isinya dan bulan lalu dinilai di akhir bulan`() {
        val f = LedgerFixture().standard()
        runSuspend { f.ledger.recordIncome(NewIncome(rupiah(1_000_000), f.account.id, "Gaji", LocalDate.of(2026, 8, 1))) }
        expense(f, "Keluarga", "Belanja bulanan", 590_000, 20, month = 8)
        expense(f, "Keluarga", "Belanja bulanan", 100_000, 2)

        val agustus = load(f, "Keluarga", YearMonth.of(2026, 8))

        assertEquals(rupiah(590_000), agustus.card.spent)
        assertEquals(RoomStatus.TERPENUHI, agustus.card.status)
        assertEquals(1, agustus.recent.size)
        assertEquals(RoomStatus.BELUM_TERCAPAI, load(f, "Memberi", YearMonth.of(2026, 8)).card.status)
    }

    @Test
    fun `ruang tanpa jatah menunggu dan pos tanpa persentase`() {
        val f = LedgerFixture().standard()

        val detail = load(f, "Keluarga")

        assertEquals(RoomStatus.MENUNGGU, detail.card.status)
        assertNull(detail.card.progressBp)
        assertTrue(detail.categories.all { it.progressBp == null })
        assertTrue(detail.recent.isEmpty())
    }

    @Test
    fun `ruang terarsip tetap bisa dilihat`() {
        val f = LedgerFixture().standard()
        f.income(1_000_000)
        runSuspend { f.rules.archiveRoom(f.room("Diri").id) }

        val detail = load(f, "Diri")

        assertTrue(detail.card.room.archived)
        assertEquals(rupiah(300_000), detail.card.allocated)
    }
}
