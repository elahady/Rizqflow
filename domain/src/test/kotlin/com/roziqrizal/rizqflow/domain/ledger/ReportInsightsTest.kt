package com.roziqrizal.rizqflow.domain.ledger

import com.roziqrizal.rizqflow.domain.money.Money
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportInsightsTest {

    private fun rupiah(n: Long) = Money.rupiah(n)

    private val fixture = LedgerFixture().standard()
    private val keluarga = fixture.room("Keluarga")
    private val diri = fixture.room("Diri")
    private val kategoriKeluarga = fixture.store.categoryRows.values.first { it.roomId == keluarga.id && !it.isSystem }

    /** Laporan rakitan tangan: [expenses] pengeluaran per bulan (Januari dst.), pemasukan tetap [incomePerMonth] di tiap bulan berpengeluaran. */
    private fun report(
        expenses: List<Long>,
        incomePerMonth: Long = 0,
        roomSpend: List<RoomSpend> = emptyList(),
        categories: List<ReportCategorySpend> = emptyList(),
    ): YearlyReport {
        val months = (1..12).map { m ->
            val expense = expenses.getOrNull(m - 1) ?: 0L
            MonthTotals(YearMonth.of(2026, m), rupiah(if (expense > 0) incomePerMonth else 0), rupiah(expense))
        }
        val income = months.fold(rupiah(0)) { acc, m -> acc + m.income }
        val expense = months.fold(rupiah(0)) { acc, m -> acc + m.expense }
        return YearlyReport(2026, months, MonthComparison(income, rupiah(0)), MonthComparison(expense, rupiah(0)), roomSpend, categories)
    }

    private inline fun <reified T : Insight> List<Insight>.one(): T? = filterIsInstance<T>().singleOrNull()

    @Test
    fun `tahun tanpa data tidak menghasilkan insight`() {
        assertTrue(ReportInsights.of(report(emptyList())).isEmpty())
    }

    @Test
    fun `satu bulan saja tidak menyimpulkan tertinggi terendah atau tren tapi rata-rata tetap ada`() {
        val insights = ReportInsights.of(report(listOf(0, 0, 500_000)))

        assertNull(insights.one<Insight.HighestExpenseMonth>())
        assertNull(insights.one<Insight.LowestExpenseMonth>())
        assertNull(insights.one<Insight.ExpenseTrend>())
        assertEquals(rupiah(500_000), insights.one<Insight.AverageMonthlyExpense>()?.amount)
    }

    @Test
    fun `bulan tertinggi dan terendah hanya dari bulan yang punya pengeluaran`() {
        val insights = ReportInsights.of(report(listOf(300_000, 0, 900_000, 100_000)))

        val high = assertNotNull(insights.one<Insight.HighestExpenseMonth>())
        val low = assertNotNull(insights.one<Insight.LowestExpenseMonth>())
        assertEquals(YearMonth.of(2026, 3), high.month)
        assertEquals(rupiah(900_000), high.amount)
        // Februari kosong (Rp 0) tidak dihitung sebagai "terendah": hanya bulan berpengeluaran, jadi April.
        assertEquals(YearMonth.of(2026, 4), low.month)
        assertEquals(rupiah(100_000), low.amount)
    }

    @Test
    fun `rata-rata dibagi bulan berpengeluaran bukan dua belas`() {
        val insights = ReportInsights.of(report(listOf(300_000, 0, 900_000)))

        val avg = assertNotNull(insights.one<Insight.AverageMonthlyExpense>())
        assertEquals(rupiah(600_000), avg.amount) // 1.200.000 / 2 bulan aktif
        assertEquals(2, avg.monthsCounted)
    }

    @Test
    fun `tren naik bila rata-rata tiga bulan terakhir melebihi ambang`() {
        // Sebelumnya 100rb x3 = 100rb, terakhir 200rb x3 = 200rb: naik 100%.
        val trend = assertNotNull(ReportInsights.of(report(listOf(100_000, 100_000, 100_000, 200_000, 200_000, 200_000))).one<Insight.ExpenseTrend>())

        assertEquals(TrendDirection.UP, trend.direction)
        assertEquals(10_000, trend.changeBp)
    }

    @Test
    fun `tren turun dan datar mengikuti ambang`() {
        val down = assertNotNull(ReportInsights.of(report(listOf(400_000, 400_000, 400_000, 200_000, 200_000, 200_000))).one<Insight.ExpenseTrend>())
        assertEquals(TrendDirection.DOWN, down.direction)
        assertEquals(-5_000, down.changeBp)

        // 103rb vs 100rb = +3%, di bawah ambang 5%: datar.
        val flat = assertNotNull(ReportInsights.of(report(listOf(100_000, 100_000, 100_000, 103_000, 103_000, 103_000))).one<Insight.ExpenseTrend>())
        assertEquals(TrendDirection.FLAT, flat.direction)
    }

    @Test
    fun `tren butuh cukup bulan pembanding`() {
        // Empat bulan aktif: tiga terakhir + hanya satu sebelumnya, belum cukup.
        assertNull(ReportInsights.of(report(listOf(100_000, 200_000, 300_000, 400_000))).one<Insight.ExpenseTrend>())
        // Lima bulan aktif: dua pembanding, cukup.
        assertNotNull(ReportInsights.of(report(listOf(100_000, 100_000, 200_000, 200_000, 200_000))).one<Insight.ExpenseTrend>())
    }

    @Test
    fun `tren memakai bulan aktif terakhir, bulan kosong di antaranya dilewati`() {
        val trend = assertNotNull(
            ReportInsights.of(report(listOf(100_000, 100_000, 100_000, 0, 0, 200_000, 200_000, 200_000))).one<Insight.ExpenseTrend>(),
        )
        assertEquals(TrendDirection.UP, trend.direction)
        assertEquals(10_000, trend.changeBp)
    }

    @Test
    fun `tingkat tabungan positif dan negatif dalam basis poin`() {
        // Pemasukan 1.000.000 x2 = 2.000.000, pengeluaran 500.000 + 300.000 = 800.000, sisa 1.200.000 = 60%.
        val saving = assertNotNull(ReportInsights.of(report(listOf(500_000, 300_000), incomePerMonth = 1_000_000)).one<Insight.SavingsRate>())
        assertEquals(6_000, saving.basisPoints)

        // Pemasukan 400rb, pengeluaran 600rb: sisa -200rb = -50%.
        val overspent = assertNotNull(ReportInsights.of(report(listOf(600_000), incomePerMonth = 400_000)).one<Insight.SavingsRate>())
        assertEquals(-5_000, overspent.basisPoints)
    }

    @Test
    fun `tanpa pemasukan tidak ada tingkat tabungan`() {
        assertNull(ReportInsights.of(report(listOf(500_000), incomePerMonth = 0)).one<Insight.SavingsRate>())
    }

    @Test
    fun `porsi ruang terbesar dan kategori terbesar mengikuti laporan`() {
        val insights = ReportInsights.of(
            report(
                expenses = listOf(1_000_000),
                roomSpend = listOf(RoomSpend(keluarga, rupiah(750_000)), RoomSpend(diri, rupiah(250_000))),
                categories = listOf(ReportCategorySpend(kategoriKeluarga, keluarga, rupiah(750_000))),
            ),
        )

        val share = assertNotNull(insights.one<Insight.TopRoomShare>())
        assertEquals(keluarga.id, share.room.id)
        assertEquals(7_500, share.basisPoints)
        val top = assertNotNull(insights.one<Insight.TopCategory>())
        assertEquals(kategoriKeluarga.id, top.category.id)
        assertEquals(rupiah(750_000), top.amount)
    }
}
