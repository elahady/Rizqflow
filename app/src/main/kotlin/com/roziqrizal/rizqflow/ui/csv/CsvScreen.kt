package com.roziqrizal.rizqflow.ui.csv

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.ledger.CsvExporter
import com.roziqrizal.rizqflow.domain.ledger.CsvImportPreviewBuilder
import com.roziqrizal.rizqflow.domain.ledger.CsvImportService
import com.roziqrizal.rizqflow.domain.ledger.ImportCandidate
import com.roziqrizal.rizqflow.domain.ledger.ImportExpense
import com.roziqrizal.rizqflow.domain.ledger.ImportIncome
import com.roziqrizal.rizqflow.domain.ledger.ImportPreview
import com.roziqrizal.rizqflow.domain.ledger.ImportSkipped
import com.roziqrizal.rizqflow.domain.ledger.ImportTransfer
import com.roziqrizal.rizqflow.domain.ledger.TransaksiHarianCsvParser
import com.roziqrizal.rizqflow.ui.Notifier
import com.roziqrizal.rizqflow.ui.RizqflowIcons
import com.roziqrizal.rizqflow.ui.formatRupiah
import com.roziqrizal.rizqflow.ui.theme.rizqflow
import com.roziqrizal.rizqflow.ui.theme.spacing
import com.roziqrizal.rizqflow.workspace.AccountWorkspace
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Ekspor dan impor CSV (Tahap 6). Ekspor menulis seluruh transaksi apa adanya; impor membaca
 * ekspor CSV Notion "Transaksi Harian" dan menampilkan pratinjau (siap diimpor, dilewati dengan
 * alasan, pasangan transfer yang disatukan) sebelum benar-benar menyimpan apa pun.
 */
@Composable
fun CsvScreen(workspace: AccountWorkspace, notifier: Notifier, onClose: () -> Unit) {
    val spacing = MaterialTheme.spacing
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onClose)

    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val repo = workspace.repositories
            val transactions = repo.transactions.between(LocalDate.MIN, LocalDate.MAX)
            val csv = CsvExporter.export(transactions, repo.accounts.allAccounts(), repo.rooms.allRooms(), repo.rooms.allCategories())
            context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
            busy = false
            notifier.show(resources.getString(R.string.csv_export_done, transactions.size))
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        importError = null
        scope.launch {
            val text = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            if (text.isNullOrBlank()) {
                busy = false
                importError = resources.getString(R.string.csv_import_read_error)
                return@launch
            }
            val rows = TransaksiHarianCsvParser.parse(text)
            val repo = workspace.repositories
            preview = CsvImportPreviewBuilder(repo.accounts.allAccounts(), repo.rooms.allCategories()).build(rows)
            busy = false
        }
    }

    val activePreview = preview
    if (activePreview != null) {
        ImportPreviewScreen(
            preview = activePreview,
            busy = busy,
            onCancel = { preview = null },
            onConfirm = {
                busy = true
                scope.launch {
                    val result = CsvImportService(workspace.ledger).commit(activePreview)
                    busy = false
                    preview = null
                    notifier.show(resources.getString(R.string.csv_import_done, result.imported, result.skipped))
                }
            },
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        TextButton(onClick = onClose, modifier = Modifier.padding(start = spacing.s3, top = spacing.s2)) { Text(stringResource(R.string.onb_back)) }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.s5)
                .padding(bottom = spacing.s5),
        ) {
            Text(stringResource(R.string.csv_title), style = MaterialTheme.typography.headlineMedium)

            Text(stringResource(R.string.csv_export_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = spacing.s5))
            Text(
                stringResource(R.string.csv_export_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.s1),
            )
            Button(
                onClick = { exportLauncher.launch(defaultCsvName()) },
                enabled = !busy,
                modifier = Modifier.padding(top = spacing.s3).fillMaxWidth().height(52.dp),
            ) { Text(stringResource(R.string.csv_export_action)) }

            Text(stringResource(R.string.csv_import_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = spacing.s5))
            Text(
                stringResource(R.string.csv_import_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.s1),
            )
            importError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = spacing.s2))
            }
            Button(
                onClick = { importLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "*/*")) },
                enabled = !busy,
                modifier = Modifier.padding(top = spacing.s3).fillMaxWidth().height(52.dp),
            ) { Text(stringResource(R.string.csv_import_action)) }
        }
    }
}

private fun defaultCsvName(): String = "rizqflow-${LocalDate.now()}.csv"

@Composable
private fun ImportPreviewScreen(preview: ImportPreview, busy: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val spacing = MaterialTheme.spacing
    BackHandler(onBack = onCancel)

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        TextButton(onClick = onCancel, modifier = Modifier.padding(start = spacing.s3, top = spacing.s2)) { Text(stringResource(R.string.catat_date_cancel)) }
        Text(stringResource(R.string.csv_import_preview_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = spacing.s5, vertical = spacing.s2))
        Text(
            stringResource(R.string.csv_import_summary, preview.ready.size, preview.skipped.size),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = spacing.s5),
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = spacing.s5, vertical = spacing.s3),
        ) {
            items(preview.candidates, key = { it.lines.joinToString() }) { candidate -> CandidateRow(candidate) }
        }
        Button(
            onClick = onConfirm,
            enabled = !busy && preview.ready.isNotEmpty(),
            modifier = Modifier.padding(horizontal = spacing.s5).padding(bottom = spacing.s5).fillMaxWidth().height(52.dp),
        ) { Text(stringResource(R.string.csv_import_confirm, preview.ready.size)) }
    }
}

@Composable
private fun CandidateRow(candidate: ImportCandidate) {
    val spacing = MaterialTheme.spacing
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = spacing.s1), verticalAlignment = Alignment.Top) {
        if (candidate is ImportSkipped) {
            Icon(RizqflowIcons.Peringatan, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
        } else {
            Icon(RizqflowIcons.Centang, contentDescription = null, tint = MaterialTheme.rizqflow.statusGood, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(spacing.s2))
        Column {
            Text(describe(candidate), style = MaterialTheme.typography.bodyMedium)
            if (candidate is ImportSkipped) {
                Text(candidate.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun describe(candidate: ImportCandidate): String {
    val lines = candidate.lines.joinToString()
    return when (candidate) {
        is ImportIncome -> stringResource(R.string.csv_import_row_income, lines, formatRupiah(candidate.income.amount))
        is ImportExpense -> stringResource(R.string.csv_import_row_expense, lines, formatRupiah(candidate.expense.amount))
        is ImportTransfer -> stringResource(R.string.csv_import_row_transfer, lines, formatRupiah(candidate.transfer.amount))
        is ImportSkipped -> stringResource(R.string.csv_import_row_skipped, lines)
    }
}
