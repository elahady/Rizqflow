package com.roziqrizal.rizqflow.ui.report

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.entitlement.Entitlements
import com.roziqrizal.rizqflow.domain.entitlement.Feature
import com.roziqrizal.rizqflow.domain.ledger.Insight
import com.roziqrizal.rizqflow.domain.ledger.MonthComparison
import com.roziqrizal.rizqflow.domain.ledger.MonthlyReport
import com.roziqrizal.rizqflow.domain.ledger.ReportInsights
import com.roziqrizal.rizqflow.domain.ledger.ReportLoader
import com.roziqrizal.rizqflow.domain.ledger.YearlyReport
import com.roziqrizal.rizqflow.domain.ledger.YearlyReportLoader
import com.roziqrizal.rizqflow.domain.money.Money
import com.roziqrizal.rizqflow.ui.Notifier
import com.roziqrizal.rizqflow.ui.RizqflowIcons
import com.roziqrizal.rizqflow.ui.formatMonth
import com.roziqrizal.rizqflow.ui.formatRupiah
import com.roziqrizal.rizqflow.ui.report.pdf.PdfPaginator
import com.roziqrizal.rizqflow.ui.report.pdf.PdfReportWriter
import com.roziqrizal.rizqflow.ui.theme.rizqflow
import com.roziqrizal.rizqflow.ui.theme.rizqflowFilterChipColors
import com.roziqrizal.rizqflow.ui.theme.spacing
import com.roziqrizal.rizqflow.workspace.AccountWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.YearMonth

private val MonthSaver = Saver<YearMonth, String>(save = { it.toString() }, restore = { YearMonth.parse(it) })

private enum class ExportKind { MONTH, YEAR }

/**
 * Laporan (S36). Ringkasan **bulanan** dengan perbandingan bulan lalu tetap Gratis (Tahap 10).
 * **Tahunan**, insight, dan unduhan PDF adalah Pro (Tahap 7, `Feature.REPORTS_AND_INSIGHTS`, lewat
 * [entitlements]); tanpa Pro, tab Tahunan dan tombol PDF membuka paywall lewat [onOpenPaywall] dan
 * data tahunan tidak dimuat sama sekali.
 */
@Composable
fun ReportScreen(
    workspace: AccountWorkspace,
    refreshKey: Int,
    entitlements: Entitlements,
    notifier: Notifier,
    onOpenPaywall: () -> Unit,
    onClose: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentMonth = remember { YearMonth.now() }
    val currentYear = currentMonth.year
    val pro = entitlements.isEnabled(Feature.REPORTS_AND_INSIGHTS)

    var yearly by rememberSaveable { mutableStateOf(false) }
    var month by rememberSaveable(stateSaver = MonthSaver) { mutableStateOf(currentMonth) }
    var year by rememberSaveable { mutableIntStateOf(currentYear) }
    var monthlyReport by remember { mutableStateOf<MonthlyReport?>(null) }
    var yearlyReport by remember { mutableStateOf<YearlyReport?>(null) }
    var insights by remember { mutableStateOf<List<Insight>>(emptyList()) }
    var pendingExport by remember { mutableStateOf<ExportKind?>(null) }
    BackHandler(onBack = onClose)

    // Tab Tahunan tanpa Pro tidak boleh tersisa dari sesi sebelumnya (mis. setelah turun paket).
    val showYearly = yearly && pro

    LaunchedEffect(month, refreshKey) {
        val repos = workspace.repositories
        monthlyReport = ReportLoader(repos.rooms, repos.transactions).load(month)
    }
    LaunchedEffect(year, refreshKey, showYearly) {
        if (!showYearly) return@LaunchedEffect
        val repos = workspace.repositories
        val loaded = YearlyReportLoader(repos.rooms, repos.transactions).load(year)
        yearlyReport = loaded
        insights = ReportInsights.of(loaded)
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val kind = pendingExport
        pendingExport = null
        if (uri == null || kind == null) return@rememberLauncherForActivityResult
        scope.launch {
            val resources = context.resources
            val saved = runCatching {
                withContext(Dispatchers.IO) {
                    val blocks = when (kind) {
                        ExportKind.MONTH -> monthlyReport?.let { monthlyBlocks(resources, it) }
                        ExportKind.YEAR -> yearlyReport?.let { yearlyBlocks(resources, it, insights) }
                    } ?: error("laporan belum dimuat")
                    val pages = PdfPaginator().paginate(blocks)
                    val stream = context.contentResolver.openOutputStream(uri) ?: error("tidak bisa membuka berkas tujuan")
                    stream.use { out ->
                        PdfReportWriter.write(pages, footer = { page, total -> resources.getString(R.string.report_pdf_footer, page, total) }, out = out)
                    }
                }
            }.isSuccess
            notifier.show(context.getString(if (saved) R.string.report_pdf_saved else R.string.report_pdf_failed))
        }
    }

    fun exportPdf(kind: ExportKind) {
        if (!pro) {
            onOpenPaywall()
            return
        }
        pendingExport = kind
        val stamp = if (kind == ExportKind.YEAR) year.toString() else month.toString()
        exportLauncher.launch("laporan-rizqflow-$stamp.pdf")
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = spacing.s2)) {
            TextButton(onClick = onClose, modifier = Modifier.padding(start = spacing.s3)) { Text(stringResource(R.string.onb_back)) }
            Text(
                stringResource(R.string.report_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f).padding(horizontal = spacing.s2),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(spacing.s2), modifier = Modifier.padding(horizontal = spacing.s4, vertical = spacing.s2)) {
            FilterChip(
                selected = !showYearly,
                onClick = { yearly = false },
                label = { Text(stringResource(R.string.report_tab_monthly)) },
                colors = rizqflowFilterChipColors(),
            )
            FilterChip(
                // Memilih Tahunan tanpa Pro membuka paywall, bukan sekadar menolak; chip tetap menyala di Bulanan.
                selected = showYearly,
                onClick = { if (pro) yearly = true else onOpenPaywall() },
                label = { Text(stringResource(if (pro) R.string.report_tab_yearly else R.string.report_tab_yearly_pro)) },
                colors = rizqflowFilterChipColors(),
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.s2)) {
            if (showYearly) {
                IconButton(onClick = { year -= 1 }, enabled = year > MIN_YEAR) {
                    Icon(RizqflowIcons.PanahKiri, contentDescription = stringResource(R.string.report_year_prev))
                }
                Text(year.toString(), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                IconButton(onClick = { year += 1 }, enabled = year < currentYear) {
                    Icon(RizqflowIcons.PanahKanan, contentDescription = stringResource(R.string.report_year_next))
                }
            } else {
                IconButton(onClick = { month = month.minusMonths(1) }) {
                    Icon(RizqflowIcons.PanahKiri, contentDescription = stringResource(R.string.tx_month_prev))
                }
                Text(formatMonth(month), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                IconButton(onClick = { month = month.plusMonths(1) }, enabled = month < currentMonth) {
                    Icon(RizqflowIcons.PanahKanan, contentDescription = stringResource(R.string.tx_month_next))
                }
            }
        }

        val scrollModifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.s4)
            .padding(top = spacing.s2, bottom = spacing.s5)

        if (showYearly) {
            val data = yearlyReport?.takeIf { it.year == year }
            if (data == null) {
                Box(Modifier.fillMaxSize())
            } else {
                Column(modifier = scrollModifier) { YearlyContent(data, insights, onExport = { exportPdf(ExportKind.YEAR) }) }
            }
        } else {
            val data = monthlyReport?.takeIf { it.month == month }
            if (data == null) {
                Box(Modifier.fillMaxSize())
            } else {
                Column(modifier = scrollModifier) { MonthlyContent(data, pro, onExport = { exportPdf(ExportKind.MONTH) }) }
            }
        }
    }
}

private const val MIN_YEAR = 2000

@Composable
private fun MonthlyContent(data: MonthlyReport, pro: Boolean, onExport: () -> Unit) {
    val spacing = MaterialTheme.spacing
    if (!data.hasData) {
        Text(stringResource(R.string.report_empty), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.report_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.s2),
        )
        return
    }

    SummaryRow(stringResource(R.string.report_income), data.income, ComparePeriod.MONTH)
    HorizontalDivider(modifier = Modifier.padding(vertical = spacing.s3))
    SummaryRow(stringResource(R.string.report_expense), data.expense, ComparePeriod.MONTH)
    HorizontalDivider(modifier = Modifier.padding(vertical = spacing.s3))
    SummaryRow(stringResource(R.string.report_net), data.net, ComparePeriod.MONTH)

    if (data.roomSpend.isNotEmpty()) {
        SectionTitle(stringResource(R.string.report_by_room))
        val maxRoom = data.roomSpend.first().amount
        data.roomSpend.forEach { BarRow(it.room.name, it.amount, maxRoom, MaterialTheme.rizqflow.room(it.room.colorSlot)) }
    }

    if (data.topCategories.isNotEmpty()) {
        SectionTitle(stringResource(R.string.report_by_category))
        val maxCategory = data.topCategories.first().amount
        data.topCategories.forEach {
            BarRow("${it.category.name} · ${it.room.name}", it.amount, maxCategory, MaterialTheme.rizqflow.room(it.room.colorSlot))
        }
    }

    OutlinedButton(onClick = onExport, modifier = Modifier.padding(top = spacing.s5).fillMaxWidth().height(48.dp)) {
        Text(stringResource(if (pro) R.string.report_pdf_download else R.string.report_pdf_download_pro))
    }
}

@Composable
private fun YearlyContent(data: YearlyReport, insights: List<Insight>, onExport: () -> Unit) {
    val spacing = MaterialTheme.spacing
    val context = LocalContext.current
    if (!data.hasData) {
        Text(stringResource(R.string.report_empty_year), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.report_empty_year_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.s2),
        )
        return
    }

    SummaryRow(stringResource(R.string.report_income), data.income, ComparePeriod.YEAR)
    HorizontalDivider(modifier = Modifier.padding(vertical = spacing.s3))
    SummaryRow(stringResource(R.string.report_expense), data.expense, ComparePeriod.YEAR)
    HorizontalDivider(modifier = Modifier.padding(vertical = spacing.s3))
    SummaryRow(stringResource(R.string.report_net_year), data.net, ComparePeriod.YEAR)

    SectionTitle(stringResource(R.string.report_by_month))
    MonthBars(data)
    val monthNames = stringArrayResource(R.array.month_abbrev)
    data.months.filter { it.hasData }.forEach { month ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = spacing.s2),
        ) {
            Text(monthNames[month.month.monthValue - 1], style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(48.dp))
            Text(
                context.getString(R.string.report_month_in_out, formatRupiah(month.income), formatRupiah(month.expense)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(formatRupiah(month.net), style = MaterialTheme.typography.bodyMedium)
        }
        HorizontalDivider()
    }

    if (data.roomSpend.isNotEmpty()) {
        SectionTitle(stringResource(R.string.report_by_room))
        val maxRoom = data.roomSpend.first().amount
        data.roomSpend.forEach { BarRow(it.room.name, it.amount, maxRoom, MaterialTheme.rizqflow.room(it.room.colorSlot)) }
    }

    if (data.topCategories.isNotEmpty()) {
        SectionTitle(stringResource(R.string.report_by_category))
        val maxCategory = data.topCategories.first().amount
        data.topCategories.forEach {
            BarRow("${it.category.name} · ${it.room.name}", it.amount, maxCategory, MaterialTheme.rizqflow.room(it.room.colorSlot))
        }
    }

    if (insights.isNotEmpty()) {
        SectionTitle(stringResource(R.string.report_insights_title))
        insights.forEach { insight ->
            Text(
                insight.describe(context.resources),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().padding(vertical = spacing.s1),
            )
        }
    }

    Button(onClick = onExport, modifier = Modifier.padding(top = spacing.s5).fillMaxWidth().height(52.dp)) {
        Text(stringResource(R.string.report_pdf_download))
    }
}

@Composable
private fun SectionTitle(text: String) {
    val spacing = MaterialTheme.spacing
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = spacing.s5, bottom = spacing.s2))
}

/**
 * Dua belas kolom pemasukan (garis tepi) dan pengeluaran (warna utama) per bulan, setinggi relatif
 * terhadap nilai terbesar tahun itu. Hanya pengantar visual: angka lengkapnya ada di baris per bulan di
 * bawahnya, jadi grafik disembunyikan dari pembaca layar.
 */
@Composable
private fun MonthBars(data: YearlyReport) {
    val spacing = MaterialTheme.spacing
    val monthNames = stringArrayResource(R.array.month_abbrev)
    val maxValue = data.months.maxOf { maxOf(it.income, it.expense) }
    val chartHeight = 96.dp
    Row(
        horizontalArrangement = Arrangement.spacedBy(spacing.s1),
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
    ) {
        data.months.forEach { month ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.height(chartHeight).fillMaxWidth(),
                ) {
                    Bar(month.income, maxValue, chartHeight, MaterialTheme.colorScheme.outlineVariant, Modifier.weight(1f))
                    Bar(month.expense, maxValue, chartHeight, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                }
                Text(monthNames[month.month.monthValue - 1].take(1), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Bar(amount: Money, max: Money, height: androidx.compose.ui.unit.Dp, color: Color, modifier: Modifier) {
    val fraction = if (max.isPositive) (amount.minor.toFloat() / max.minor.toFloat()).coerceIn(0f, 1f) else 0f
    Box(modifier = modifier.height(if (amount.isPositive) maxOf(height * fraction, 2.dp) else 0.dp).background(color))
}

@Composable
private fun SummaryRow(label: String, comparison: MonthComparison, period: ComparePeriod) {
    val spacing = MaterialTheme.spacing
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(formatRupiah(comparison.current), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = spacing.s1))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = spacing.s1)) {
            // Tanpa pembanding atau tanpa selisih: lingkaran netral; selain itu panah naik/turun (selalu bersama teks).
            val icon = when {
                comparison.previous.isZero && comparison.percentBp == null -> RizqflowIcons.Lingkaran
                comparison.delta.isZero -> RizqflowIcons.Lingkaran
                comparison.delta.isPositive -> RizqflowIcons.PanahAtas
                else -> RizqflowIcons.PanahBawah
            }
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = spacing.s1))
            Text(
                comparisonText(context.resources, comparison, period),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BarRow(label: String, amount: Money, max: Money, color: Color) {
    val spacing = MaterialTheme.spacing
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = spacing.s2)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(formatRupiah(amount), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val fraction = if (max.isPositive) (amount.minor.toFloat() / max.minor.toFloat()).coerceIn(0f, 1f) else 0f
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.padding(top = spacing.s1).fillMaxWidth().height(8.dp),
            color = color,
            trackColor = color.copy(alpha = 0.2f),
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}
