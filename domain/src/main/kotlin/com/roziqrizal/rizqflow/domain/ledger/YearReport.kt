package com.roziqrizal.rizqflow.domain.ledger

import com.roziqrizal.rizqflow.domain.money.Money
import java.time.LocalDate
import java.time.YearMonth

/*
 * Laporan tahunan (Tahap 7, Pro: `Feature.REPORTS_AND_INSIGHTS`). Seperti laporan bulanan (Report.kt)
 * semua angka diturunkan dari transaksi dan tidak ada yang disimpan; hanya pengeluaran dan pemasukan
 * yang dihitung (transfer, pinjaman, dan transaksi tagihan di luar jenis itu diabaikan). Bagian yang
 * Gratis tetap ringkasan bulanan S36 — tahunan, insight, dan PDF yang Pro.
 */

/** Pemasukan dan pengeluaran satu bulan. */
data class MonthTotals(val month: YearMonth, val income: Money, val expense: Money) {
    val net: Money get() = income - expense

    /** Bulan ini ada pemasukan atau pengeluaran sama sekali. */
    val hasData: Boolean get() = income.isPositive || expense.isPositive
}

/**
 * Laporan satu tahun kalender. [months] selalu 12 baris, Januari sampai Desember, termasuk bulan
 * kosong. [income] dan [expense] membandingkan total tahun ini dengan total tahun lalu (pasangan
 * yang sama dengan [MonthComparison], dipakai ulang karena bentuknya identik). [roomSpend] dan
 * [topCategories] hanya pengeluaran tahun ini, terbesar dulu; ruang dan kategori terarsip tetap
 * terhitung karena ini ringkasan yang sudah terjadi.
 */
data class YearlyReport(
    val year: Int,
    val months: List<MonthTotals>,
    val income: MonthComparison,
    val expense: MonthComparison,
    val roomSpend: List<RoomSpend>,
    val topCategories: List<ReportCategorySpend>,
) {
    val net: MonthComparison get() = MonthComparison(income.current - expense.current, income.previous - expense.previous)

    val hasData: Boolean get() = income.current.isPositive || expense.current.isPositive

    /** Bulan yang punya pengeluaran: dasar rata-rata dan tren (bulan kosong tidak boleh menurunkan rata-rata). */
    val activeExpenseMonths: List<MonthTotals> get() = months.filter { it.expense.isPositive }

    companion object {
        /** Kategori terbesar di laporan tahunan; lebih lega dari laporan bulanan karena setahun lebih beragam. */
        const val MAX_CATEGORIES = 10
    }
}

class YearlyReportLoader(
    private val rooms: RoomRepository,
    private val transactions: TransactionRepository,
) {
    suspend fun load(year: Int): YearlyReport {
        val currentTx = transactions.between(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))
        val previousTx = transactions.between(LocalDate.of(year - 1, 1, 1), LocalDate.of(year - 1, 12, 31))

        val roomsById = rooms.allRooms().associateBy { it.id }
        val categoriesById = rooms.allCategories().associateBy { it.id }
        val byMonth = currentTx.groupBy { YearMonth.from(it.occurredOn) }

        val months = (1..12).map { monthValue ->
            val month = YearMonth.of(year, monthValue)
            val txs = byMonth[month].orEmpty()
            MonthTotals(month, incomeOf(txs), expenseOf(txs))
        }

        return YearlyReport(
            year = year,
            months = months,
            income = MonthComparison(incomeOf(currentTx), incomeOf(previousTx)),
            expense = MonthComparison(expenseOf(currentTx), expenseOf(previousTx)),
            roomSpend = roomSpendOf(currentTx, roomsById),
            topCategories = topCategoriesOf(currentTx, roomsById, categoriesById, YearlyReport.MAX_CATEGORIES),
        )
    }
}
