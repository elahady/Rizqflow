package com.roziqrizal.rizqflow.ui.zakat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.debt.ZakatDebtSuggestion
import com.roziqrizal.rizqflow.domain.ledger.LedgerError
import com.roziqrizal.rizqflow.domain.ledger.LedgerResult
import com.roziqrizal.rizqflow.domain.model.WealthKind
import com.roziqrizal.rizqflow.domain.money.Money
import com.roziqrizal.rizqflow.domain.zakat.GivingOverview
import com.roziqrizal.rizqflow.domain.zakat.NewWealthItem
import com.roziqrizal.rizqflow.domain.zakat.WealthItem
import com.roziqrizal.rizqflow.ui.RizqflowIcons
import com.roziqrizal.rizqflow.ui.formatRupiah
import com.roziqrizal.rizqflow.ui.theme.spacing
import com.roziqrizal.rizqflow.workspace.AccountWorkspace
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

/** Satu baris harta bebas (kind OTHER): "jenis harta bisa ditambah bebas" (docs/wireframe.md S15). */
private data class CustomRow(val key: String, val label: String, val value: String)

private fun digitsOnly(text: String): String = text.filter(Char::isDigit).take(14)

/**
 * S15 Profil harta: harga emas per gram, lalu baris bawaan (emas dalam gram, uang dan tabungan,
 * investasi, piutang lancar), harta bebas tambah (OTHER), dan pengurang (hutang jangka pendek).
 * Menyimpan mengganti seluruh daftar sekaligus dan mencatat pemeriksaan haul hari itu.
 */
@Composable
fun WealthScreen(workspace: AccountWorkspace, overview: GivingOverview, profileId: String?, onSaved: () -> Unit, onCancel: () -> Unit) {
    val spacing = MaterialTheme.spacing
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val today = remember { LocalDate.now() }

    fun of(kind: WealthKind) = overview.items.firstOrNull { it.kind == kind }

    var goldPrice by rememberSaveable { mutableStateOf(overview.goldPrice?.perGram?.minor?.toString().orEmpty()) }
    var goldGrams by rememberSaveable { mutableStateOf(of(WealthKind.GOLD)?.goldMilligrams?.let { it / 1000 }?.toString().orEmpty()) }
    var cash by rememberSaveable { mutableStateOf(of(WealthKind.CASH_SAVINGS)?.value?.minor?.toString().orEmpty()) }
    var investment by rememberSaveable { mutableStateOf(of(WealthKind.INVESTMENT)?.value?.minor?.toString().orEmpty()) }
    var receivable by rememberSaveable { mutableStateOf(of(WealthKind.RECEIVABLE)?.value?.minor?.toString().orEmpty()) }
    var debt by rememberSaveable { mutableStateOf(of(WealthKind.DEDUCTION)?.value?.minor?.toString().orEmpty()) }
    var customs by rememberSaveable(stateSaver = customsSaver) {
        mutableStateOf(overview.items.filter { it.kind == WealthKind.OTHER }.map { CustomRow(it.id, it.label, it.value.minor.toString()) })
    }
    var error by remember { mutableStateOf<LedgerError?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Saran dari Utang-piutang; tidak pernah masuk sendiri, hanya bila pengguna menekan Pakai.
    var suggestion by remember { mutableStateOf<ZakatDebtSuggestion?>(null) }
    LaunchedEffect(Unit) { suggestion = workspace.debts.zakatSuggestion(today) }

    val goldValue = goldPrice.toLongOrNull()?.let { price -> (goldGrams.toLongOrNull() ?: 0L) * price }

    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = spacing.s5).padding(bottom = spacing.s5)) {
        Text(stringResource(R.string.wealth_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = spacing.s4))

        OutlinedTextField(
            value = goldPrice,
            onValueChange = {
                goldPrice = digitsOnly(it)
                error = null
            },
            label = { Text(stringResource(R.string.wealth_gold_price)) },
            prefix = { Text("Rp ") },
            singleLine = true,
            isError = error == LedgerError.GOLD_PRICE_REQUIRED,
            supportingText = if (error == LedgerError.GOLD_PRICE_REQUIRED) {
                { Text(stringResource(R.string.wealth_err_gold_price)) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().padding(top = spacing.s3),
        )

        Text(stringResource(R.string.wealth_assets), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = spacing.s5, bottom = spacing.s2))
        WealthRow(stringResource(R.string.wealth_kind_gold), goldGrams, { goldGrams = digitsOnly(it) }, suffix = "g", hint = goldValue?.let { formatRupiah(it) })
        WealthRow(stringResource(R.string.wealth_kind_cash), cash, { cash = digitsOnly(it) })
        WealthRow(stringResource(R.string.wealth_kind_investment), investment, { investment = digitsOnly(it) })
        WealthRow(stringResource(R.string.wealth_kind_receivable), receivable, { receivable = digitsOnly(it) })
        suggestion?.receivable?.takeIf { it.isPositive && it.minor.toString() != receivable }?.let { suggested ->
            DebtSuggestion(stringResource(R.string.wealth_suggest_receivable, formatRupiah(suggested))) { receivable = suggested.minor.toString() }
        }
        customs.forEachIndexed { index, row ->
            CustomWealthRow(
                row = row,
                onChange = { updated -> customs = customs.toMutableList().also { it[index] = updated } },
                onRemove = { customs = customs.toMutableList().also { it.removeAt(index) } },
            )
        }
        TextButton(onClick = { customs = customs + CustomRow(UUID.randomUUID().toString(), "", "") }, modifier = Modifier.padding(top = spacing.s1)) {
            Icon(RizqflowIcons.Tambah, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.wealth_add), modifier = Modifier.padding(start = spacing.s1))
        }

        Text(stringResource(R.string.wealth_deductions), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = spacing.s4, bottom = spacing.s2))
        WealthRow(stringResource(R.string.wealth_kind_deduction), debt, { debt = digitsOnly(it) })
        suggestion?.shortTermDebt?.takeIf { it.isPositive && it.minor.toString() != debt }?.let { suggested ->
            DebtSuggestion(stringResource(R.string.wealth_suggest_debt, formatRupiah(suggested))) { debt = suggested.minor.toString() }
        }

        val net = (goldValue ?: 0) + (cash.toLongOrNull() ?: 0) + (investment.toLongOrNull() ?: 0) + (receivable.toLongOrNull() ?: 0) +
            customs.sumOf { it.value.toLongOrNull() ?: 0 } - (debt.toLongOrNull() ?: 0)
        HorizontalDivider(modifier = Modifier.padding(top = spacing.s3))
        Row(modifier = Modifier.fillMaxWidth().padding(top = spacing.s2), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.wealth_net), style = MaterialTheme.typography.titleMedium)
            Text(formatRupiah(net), style = MaterialTheme.typography.titleMedium)
        }

        if (error != null && error != LedgerError.GOLD_PRICE_REQUIRED) {
            Text(
                stringResource(R.string.wealth_err_item),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = spacing.s2),
            )
        }

        Button(
            onClick = {
                busy = true
                scope.launch {
                    val items = buildList {
                        add(NewWealthItem(of(WealthKind.GOLD)?.id, WealthKind.GOLD, resources.getString(R.string.wealth_kind_gold), Money.zero(), goldGrams.toLongOrNull()?.times(1000)))
                        add(NewWealthItem(of(WealthKind.CASH_SAVINGS)?.id, WealthKind.CASH_SAVINGS, resources.getString(R.string.wealth_kind_cash), Money.rupiah(cash.toLongOrNull() ?: 0)))
                        add(NewWealthItem(of(WealthKind.INVESTMENT)?.id, WealthKind.INVESTMENT, resources.getString(R.string.wealth_kind_investment), Money.rupiah(investment.toLongOrNull() ?: 0)))
                        add(NewWealthItem(of(WealthKind.RECEIVABLE)?.id, WealthKind.RECEIVABLE, resources.getString(R.string.wealth_kind_receivable), Money.rupiah(receivable.toLongOrNull() ?: 0)))
                        add(NewWealthItem(of(WealthKind.DEDUCTION)?.id, WealthKind.DEDUCTION, resources.getString(R.string.wealth_kind_deduction), Money.rupiah(debt.toLongOrNull() ?: 0)))
                        customs.filter { it.label.isNotBlank() }.forEach { add(NewWealthItem(null, WealthKind.OTHER, it.label, Money.rupiah(it.value.toLongOrNull() ?: 0))) }
                    }
                    val result = workspace.zakat.saveWealth(items, Money.rupiah(goldPrice.toLongOrNull() ?: 0), today, profileId)
                    busy = false
                    when (result) {
                        is LedgerResult.Success -> onSaved()
                        is LedgerResult.Failure -> error = result.error
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.padding(top = spacing.s5).fillMaxWidth().height(52.dp),
        ) { Text(stringResource(R.string.wealth_save)) }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.catat_date_cancel)) }
    }
}

/** Baris saran nilai dari Utang-piutang dengan tombol Pakai; nilainya baru dipakai setelah diketuk dan disimpan. */
@Composable
private fun DebtSuggestion(text: String, onUse: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        TextButton(onClick = onUse) { Text(stringResource(R.string.wealth_suggest_use)) }
    }
}

@Composable
private fun WealthRow(label: String, value: String, onChange: (String) -> Unit, suffix: String? = null, hint: String? = null) {
    val spacing = MaterialTheme.spacing
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = spacing.s1), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            prefix = if (suffix == null) ({ Text("Rp ") }) else null,
            suffix = if (suffix != null) ({ Text(suffix) }) else null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(160.dp),
        )
    }
}

@Composable
private fun CustomWealthRow(row: CustomRow, onChange: (CustomRow) -> Unit, onRemove: () -> Unit) {
    val spacing = MaterialTheme.spacing
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = spacing.s1), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        OutlinedTextField(
            value = row.label,
            onValueChange = { onChange(row.copy(label = it.take(WealthItem.LABEL_MAX))) },
            label = { Text(stringResource(R.string.wealth_label)) },
            singleLine = true,
            modifier = Modifier.weight(1f).padding(end = spacing.s2),
        )
        OutlinedTextField(
            value = row.value,
            onValueChange = { onChange(row.copy(value = digitsOnly(it))) },
            prefix = { Text("Rp ") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(140.dp),
        )
        IconButton(onClick = onRemove) { Icon(RizqflowIcons.Tutup, contentDescription = stringResource(R.string.wealth_delete)) }
    }
}

private val customsSaver = listSaver<List<CustomRow>, String>(
    save = { it.flatMap { row -> listOf(row.key, row.label, row.value) } },
    restore = { flat -> flat.chunked(3).map { CustomRow(it[0], it[1], it[2]) } },
)
