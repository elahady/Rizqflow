package com.roziqrizal.rizqflow.ui.report

import android.content.res.Resources
import androidx.compose.ui.graphics.toArgb
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.ledger.Insight
import com.roziqrizal.rizqflow.domain.ledger.MonthComparison
import com.roziqrizal.rizqflow.domain.ledger.MonthlyReport
import com.roziqrizal.rizqflow.domain.ledger.TrendDirection
import com.roziqrizal.rizqflow.domain.ledger.YearlyReport
import com.roziqrizal.rizqflow.domain.money.Money
import com.roziqrizal.rizqflow.ui.formatMonthWith
import com.roziqrizal.rizqflow.ui.formatPercent
import com.roziqrizal.rizqflow.ui.formatRupiah
import com.roziqrizal.rizqflow.ui.report.pdf.PdfBlock
import com.roziqrizal.rizqflow.ui.theme.RizqflowLightExtra
import kotlin.math.abs

/*
 * Isi teks laporan: kalimat insight dan dokumen PDF. Dipakai layar Laporan dan penulis PDF sehingga
 * kalimat yang sama muncul di layar dan di kertas. Memakai [Resources] (bukan composable) supaya
 * mengikuti bahasa tampilan dan bisa dipanggil dari luar komposisi.
 */

private fun Resources.monthNames(): List<String> = getStringArray(R.array.month_abbrev).toList()

/** Kalimat satu insight, bahasa mengikuti [res]. */
internal fun Insight.describe(res: Resources): String {
    val months = res.monthNames()
    return when (this) {
        is Insight.ExpenseTrend -> when (direction) {
            TrendDirection.UP -> res.getString(R.string.insight_trend_up, formatPercent(abs(changeBp)))
            TrendDirection.DOWN -> res.getString(R.string.insight_trend_down, formatPercent(abs(changeBp)))
            TrendDirection.FLAT -> res.getString(R.string.insight_trend_flat)
        }

        is Insight.SavingsRate -> when {
            basisPoints > 0 -> res.getString(R.string.insight_savings_positive, formatPercent(basisPoints))
            basisPoints < 0 -> res.getString(R.string.insight_savings_negative, formatPercent(abs(basisPoints)))
            else -> res.getString(R.string.insight_savings_zero)
        }

        is Insight.TopRoomShare -> res.getString(R.string.insight_top_room, room.name, formatPercent(basisPoints))
        is Insight.TopCategory -> res.getString(R.string.insight_top_category, category.name, room.name, formatRupiah(amount))
        is Insight.HighestExpenseMonth -> res.getString(R.string.insight_highest_month, formatMonthWith(month, months), formatRupiah(amount))
        is Insight.LowestExpenseMonth -> res.getString(R.string.insight_lowest_month, formatMonthWith(month, months), formatRupiah(amount))
        is Insight.AverageMonthlyExpense -> res.getString(R.string.insight_average, formatRupiah(amount), monthsCounted)
    }
}

/** Satuan perbandingan: bulan lalu untuk laporan bulanan, tahun lalu untuk tahunan. */
internal enum class ComparePeriod { MONTH, YEAR }

/** Kalimat perbandingan dengan periode sebelumnya, mis. "Naik 25% dari bulan lalu (Rp 4.000.000)". */
internal fun comparisonText(res: Resources, comparison: MonthComparison, period: ComparePeriod): String {
    val yearly = period == ComparePeriod.YEAR
    val percent = comparison.percentBp
    return when {
        comparison.previous.isZero && percent == null ->
            res.getString(if (yearly) R.string.report_no_previous_year else R.string.report_no_previous_data)

        comparison.delta.isZero ->
            res.getString(if (yearly) R.string.report_same_as_last_year else R.string.report_same_as_last_month, formatRupiah(comparison.previous))

        else -> {
            val up = comparison.delta.isPositive
            val direction = res.getString(
                if (up) R.string.report_up else R.string.report_down,
                percent?.let { formatPercent(abs(it)) } ?: formatRupiah(comparison.delta.abs()),
            )
            res.getString(if (yearly) R.string.report_vs_last_year else R.string.report_vs_last_month, direction, formatRupiah(comparison.previous))
        }
    }
}

private fun barFraction(amount: Money, max: Money): Float =
    if (max.isPositive) (amount.minor.toFloat() / max.minor.toFloat()).coerceIn(0f, 1f) else 0f

/** Warna ruang versi terang (cetak), tak bergantung pada tema yang sedang aktif. */
private fun roomColor(slot: Int): Int = RizqflowLightExtra.room(slot).toArgb()

private fun summaryBlocks(res: Resources, income: MonthComparison, expense: MonthComparison, net: MonthComparison, netLabel: Int, period: ComparePeriod): List<PdfBlock> = buildList {
    add(PdfBlock.Line(res.getString(R.string.report_income), formatRupiah(income.current), strong = true))
    add(PdfBlock.Note(comparisonText(res, income, period)))
    add(PdfBlock.Line(res.getString(R.string.report_expense), formatRupiah(expense.current), strong = true))
    add(PdfBlock.Note(comparisonText(res, expense, period)))
    add(PdfBlock.Line(res.getString(netLabel), formatRupiah(net.current), strong = true))
    add(PdfBlock.Note(comparisonText(res, net, period)))
}

/** Dokumen PDF laporan satu bulan (S36). */
internal fun monthlyBlocks(res: Resources, report: MonthlyReport): List<PdfBlock> = buildList {
    add(PdfBlock.Title(res.getString(R.string.report_pdf_title)))
    add(PdfBlock.Subtitle(formatMonthWith(report.month, res.monthNames())))
    addAll(summaryBlocks(res, report.income, report.expense, report.net, R.string.report_net, ComparePeriod.MONTH))
    if (report.roomSpend.isNotEmpty()) {
        add(PdfBlock.Heading(res.getString(R.string.report_by_room)))
        val max = report.roomSpend.first().amount
        report.roomSpend.forEach { add(PdfBlock.Bar(it.room.name, formatRupiah(it.amount), barFraction(it.amount, max), roomColor(it.room.colorSlot))) }
    }
    if (report.topCategories.isNotEmpty()) {
        add(PdfBlock.Heading(res.getString(R.string.report_by_category)))
        val max = report.topCategories.first().amount
        report.topCategories.forEach {
            add(PdfBlock.Bar("${it.category.name} · ${it.room.name}", formatRupiah(it.amount), barFraction(it.amount, max), roomColor(it.room.colorSlot)))
        }
    }
}

/** Dokumen PDF laporan setahun: ringkasan, rincian per bulan, per ruang, kategori, dan insight. */
internal fun yearlyBlocks(res: Resources, report: YearlyReport, insights: List<Insight>): List<PdfBlock> = buildList {
    val months = res.monthNames()
    add(PdfBlock.Title(res.getString(R.string.report_pdf_title)))
    add(PdfBlock.Subtitle(report.year.toString()))
    addAll(summaryBlocks(res, report.income, report.expense, report.net, R.string.report_net_year, ComparePeriod.YEAR))

    add(PdfBlock.Heading(res.getString(R.string.report_by_month)))
    val maxMonth = report.months.maxOf { it.expense }
    report.months.forEach { month ->
        add(
            PdfBlock.Bar(
                label = months[month.month.monthValue - 1],
                value = res.getString(R.string.report_month_in_out, formatRupiah(month.income), formatRupiah(month.expense)),
                fraction = barFraction(month.expense, maxMonth),
                color = INK_BAR,
            ),
        )
    }

    if (report.roomSpend.isNotEmpty()) {
        add(PdfBlock.Heading(res.getString(R.string.report_by_room)))
        val max = report.roomSpend.first().amount
        report.roomSpend.forEach { add(PdfBlock.Bar(it.room.name, formatRupiah(it.amount), barFraction(it.amount, max), roomColor(it.room.colorSlot))) }
    }
    if (report.topCategories.isNotEmpty()) {
        add(PdfBlock.Heading(res.getString(R.string.report_by_category)))
        val max = report.topCategories.first().amount
        report.topCategories.forEach {
            add(PdfBlock.Bar("${it.category.name} · ${it.room.name}", formatRupiah(it.amount), barFraction(it.amount, max), roomColor(it.room.colorSlot)))
        }
    }
    if (insights.isNotEmpty()) {
        add(PdfBlock.Heading(res.getString(R.string.report_insights_title)))
        insights.forEach { add(PdfBlock.Note("• ${it.describe(res)}")) }
    }
}

/** Warna batang bulan di PDF (netral gelap, supaya tak dikira warna ruang). */
private const val INK_BAR = 0xFF4D6359.toInt()
