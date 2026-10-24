package com.roziqrizal.rizqflow.domain.ledger

import com.roziqrizal.rizqflow.domain.money.Money
import java.math.BigInteger
import java.time.YearMonth

/*
 * Insight laporan tahunan (Tahap 7, Pro). Murni aritmetika atas [YearlyReport] — tanpa model
 * statistik, tanpa tebakan — sehingga setiap kalimat di layar bisa ditelusuri ke angka yang sama
 * dengan yang tertera di laporan. Insight hanya muncul bila datanya cukup bermakna (lihat syarat
 * di tiap aturan); lebih baik tidak berkata apa-apa daripada menyimpulkan dari satu bulan.
 */

enum class TrendDirection { UP, DOWN, FLAT }

sealed interface Insight {
    /** Pengeluaran 3 bulan aktif terakhir dibanding 3 bulan aktif sebelumnya (rata-rata bulanan). */
    data class ExpenseTrend(val direction: TrendDirection, val changeBp: Int) : Insight

    /** Sisa dibanding pemasukan setahun: positif berarti menabung, negatif berarti pengeluaran melebihi pemasukan. */
    data class SavingsRate(val basisPoints: Int) : Insight

    data class TopRoomShare(val room: Room, val basisPoints: Int) : Insight

    data class TopCategory(val category: Category, val room: Room, val amount: Money) : Insight

    data class HighestExpenseMonth(val month: YearMonth, val amount: Money) : Insight

    data class LowestExpenseMonth(val month: YearMonth, val amount: Money) : Insight

    /** Rata-rata pengeluaran per bulan yang punya pengeluaran ([monthsCounted] bulan). */
    data class AverageMonthlyExpense(val amount: Money, val monthsCounted: Int) : Insight
}

object ReportInsights {
    /** Selisih rata-rata di bawah ini dianggap datar, bukan naik atau turun. */
    const val FLAT_THRESHOLD_BP = 500

    /** Bulan aktif terakhir yang dibandingkan, dan jumlah minimal bulan pembanding sebelumnya. */
    private const val TREND_RECENT = 3
    private const val TREND_MIN_PREVIOUS = 2

    private val TEN_THOUSAND = BigInteger.valueOf(10_000)

    fun of(report: YearlyReport): List<Insight> {
        if (!report.hasData) return emptyList()
        val active = report.activeExpenseMonths
        return buildList {
            trend(active)?.let(::add)
            savingsRate(report)?.let(::add)
            topRoom(report)?.let(::add)
            report.topCategories.firstOrNull()?.let { add(Insight.TopCategory(it.category, it.room, it.amount)) }
            // Tertinggi dan terendah hanya bermakna bila ada pembanding: minimal dua bulan aktif.
            if (active.size >= 2) {
                val highest = active.maxBy { it.expense }
                val lowest = active.minBy { it.expense }
                add(Insight.HighestExpenseMonth(highest.month, highest.expense))
                add(Insight.LowestExpenseMonth(lowest.month, lowest.expense))
            }
            if (active.isNotEmpty()) {
                add(Insight.AverageMonthlyExpense(Money.rupiah(report.expense.current.minor / active.size), active.size))
            }
        }
    }

    private fun trend(active: List<MonthTotals>): Insight.ExpenseTrend? {
        if (active.size < TREND_RECENT + TREND_MIN_PREVIOUS) return null
        val recent = active.takeLast(TREND_RECENT)
        val previous = active.dropLast(TREND_RECENT).takeLast(TREND_RECENT)
        val recentAvg = recent.sumOf { it.expense.minor } / recent.size
        val previousAvg = previous.sumOf { it.expense.minor } / previous.size
        if (previousAvg <= 0) return null
        val changeBp = basisPoints(recentAvg - previousAvg, previousAvg)
        val direction = when {
            changeBp > FLAT_THRESHOLD_BP -> TrendDirection.UP
            changeBp < -FLAT_THRESHOLD_BP -> TrendDirection.DOWN
            else -> TrendDirection.FLAT
        }
        return Insight.ExpenseTrend(direction, changeBp)
    }

    private fun savingsRate(report: YearlyReport): Insight.SavingsRate? {
        val income = report.income.current
        if (!income.isPositive) return null
        return Insight.SavingsRate(basisPoints(report.net.current.minor, income.minor))
    }

    private fun topRoom(report: YearlyReport): Insight.TopRoomShare? {
        val total = report.expense.current
        val top = report.roomSpend.firstOrNull() ?: return null
        if (!total.isPositive) return null
        return Insight.TopRoomShare(top.room, basisPoints(top.amount.minor, total.minor))
    }

    /** [part] per [whole] dalam basis poin, dibulatkan ke nol, tanpa luapan Long. */
    private fun basisPoints(part: Long, whole: Long): Int {
        val bp = BigInteger.valueOf(part).multiply(TEN_THOUSAND).divide(BigInteger.valueOf(whole))
        return bp.coerceIn(BigInteger.valueOf(Int.MIN_VALUE.toLong()), BigInteger.valueOf(Int.MAX_VALUE.toLong())).toInt()
    }
}
