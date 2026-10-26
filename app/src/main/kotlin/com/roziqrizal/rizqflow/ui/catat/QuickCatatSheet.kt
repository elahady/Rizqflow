package com.roziqrizal.rizqflow.ui.catat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.ledger.CatatContext
import com.roziqrizal.rizqflow.domain.ledger.CatatContextLoader
import com.roziqrizal.rizqflow.domain.ledger.CatatDraft
import com.roziqrizal.rizqflow.domain.ledger.CatatMode
import com.roziqrizal.rizqflow.domain.ledger.DenahLoader
import com.roziqrizal.rizqflow.domain.ledger.DuplicateTransactionDetector
import com.roziqrizal.rizqflow.domain.ledger.toDuplicateProbe
import com.roziqrizal.rizqflow.domain.ledger.FavoriteRow
import com.roziqrizal.rizqflow.domain.ledger.FavoriteUse
import com.roziqrizal.rizqflow.domain.ledger.LedgerError
import com.roziqrizal.rizqflow.domain.ledger.LedgerResult
import com.roziqrizal.rizqflow.domain.ledger.QuickFavorite
import com.roziqrizal.rizqflow.domain.ledger.AmountPad
import com.roziqrizal.rizqflow.domain.ledger.SafeToSpend
import com.roziqrizal.rizqflow.domain.model.AccountId
import com.roziqrizal.rizqflow.domain.model.RoomKind
import com.roziqrizal.rizqflow.domain.model.TransactionKind
import com.roziqrizal.rizqflow.ui.formatRupiah
import com.roziqrizal.rizqflow.ui.kelola.manageErrorText
import com.roziqrizal.rizqflow.ui.onboarding.AmountKeypad
import com.roziqrizal.rizqflow.ui.theme.rizqflowFilterChipColors
import com.roziqrizal.rizqflow.ui.theme.spacing
import com.roziqrizal.rizqflow.workspace.AccountWorkspace
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * S24 Catat kilat: bottom sheet pengeluaran cepat yang dibuka dari pintasan ikon atau tile Quick
 * Settings. Mengetuk favorit langsung menyimpan (satu ketukan); nominal yang diketik memakai ruang dan
 * kategori terakhir dipakai serta akun yang dipilih. Hanya pengeluaran: pemasukan harus melewati
 * pratinjau alokasi (S07).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickCatatSheet(
    workspace: AccountWorkspace,
    onClose: () -> Unit,
    onSaved: (SavedInfo) -> Unit,
    onFavoriteUsed: (FavoriteUse) -> Unit,
) {
    val today = remember { LocalDate.now() }
    var context by remember { mutableStateOf<CatatContext?>(null) }
    var favorites by remember { mutableStateOf<List<FavoriteRow>>(emptyList()) }
    var safe by remember { mutableStateOf<SafeToSpend?>(null) }
    var digits by rememberSaveable { mutableStateOf("") }
    var accountId by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<LedgerError?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Deteksi transaksi ganda untuk tombol Simpan (bukan ketukan favorit yang langsung menyimpan).
    val duplicates = remember(workspace) { DuplicateTransactionDetector(workspace.repositories.transactions, System::currentTimeMillis) }
    var duplicateHold by remember { mutableStateOf<DuplicateHold?>(null) }
    val scope = rememberCoroutineScope()
    val spacing = MaterialTheme.spacing
    val android = LocalContext.current

    LaunchedEffect(workspace) {
        val repos = workspace.repositories
        context = CatatContextLoader(repos.accounts, repos.rooms, repos.transactions).load()
        favorites = workspace.favorites.list().filter { it.usable }.take(QuickFavorite.MAX_COUNT)
        safe = DenahLoader(repos.rooms, repos.transactions, repos.accounts).safeToSpend(today)
    }
    val ctx = context ?: return

    val base = CatatDraft.start(ctx, today, CatatMode.EXPENSE).copy(digits = digits)
    val draft = accountId?.let { base.withAccount(AccountId(it), ctx) } ?: base
    val room = ctx.rooms.firstOrNull { it.id == draft.roomId }
    val category = draft.roomId?.let { ctx.categoriesOf(it) }?.firstOrNull { it.id == draft.categoryId }

    fun saveExpense(checked: Boolean) {
        if (busy || draft.issue(ctx) != null) return
        busy = true
        scope.launch {
            if (!checked) {
                val match = duplicates.findDuplicate(draft.toDuplicateProbe())
                if (match != null) {
                    busy = false
                    duplicateHold = DuplicateHold(match) { saveExpense(checked = true) }
                    return@launch
                }
            }
            when (val result = workspace.ledger.recordExpense(draft.toExpense())) {
                is LedgerResult.Success -> {
                    val tx = result.value
                    onSaved(SavedInfo(tx.id, TransactionKind.EXPENSE, tx.amount, 0))
                    onClose()
                }

                is LedgerResult.Failure -> {
                    error = result.error
                    busy = false
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.s5)
            .padding(bottom = spacing.s4),
    ) {
        Text(stringResource(R.string.quick_title), style = MaterialTheme.typography.titleLarge)
        Text(
            formatRupiah(draft.amount),
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.padding(top = spacing.s2),
        )
        if (room != null && category != null) {
            Text(
                stringResource(R.string.quick_destination, room.name, category.name),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        safe?.let { s ->
            // Seperti di layar Catat: setelah nominal diisi di ruang Mencukupi, tampil sisa setelah pengeluaran ini.
            val after = if (draft.amount.isPositive && room?.kind == RoomKind.MENCUKUPI) s.remainingAfter(draft.amount) else null
            Text(
                stringResource(if (after != null) R.string.catat_safe_after else R.string.catat_safe_now, formatRupiah(after ?: s.remaining)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        if (favorites.isNotEmpty()) {
            Text(stringResource(R.string.quick_favorites), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = spacing.s3, bottom = spacing.s1))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.s2)) {
                favorites.forEach { row ->
                    SuggestionChip(
                        onClick = {
                            if (busy) return@SuggestionChip
                            busy = true
                            scope.launch {
                                when (val result = workspace.favorites.use(row.favorite.id, today)) {
                                    is LedgerResult.Success -> {
                                        onFavoriteUsed(result.value)
                                        onClose()
                                    }

                                    is LedgerResult.Failure -> {
                                        error = result.error
                                        busy = false
                                    }
                                }
                            }
                        },
                        enabled = !busy,
                        label = { Text("${row.favorite.name} ${formatRupiah(row.favorite.amount)}") },
                    )
                }
            }
        }

        if (ctx.accounts.size > 1) {
            Text(stringResource(R.string.catat_account), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = spacing.s3, bottom = spacing.s1))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.s2)) {
                ctx.accounts.forEach { account ->
                    FilterChip(
                        selected = draft.accountId == account.id,
                        onClick = { accountId = account.id.value },
                        label = { Text(account.name) },
                        colors = rizqflowFilterChipColors(),
                    )
                }
            }
        }

        error?.let {
            Text(manageErrorText(android, it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = spacing.s2))
        }

        AmountKeypad(
            enabled = !busy,
            onKey = {
                digits = AmountPad.apply(digits, it)
                error = null
            },
            modifier = Modifier.padding(top = spacing.s3),
        )

        Button(
            onClick = { saveExpense(checked = false) },
            enabled = draft.issue(ctx) == null && !busy,
            modifier = Modifier.padding(top = spacing.s3).fillMaxWidth().height(52.dp),
        ) { Text(stringResource(R.string.catat_save)) }
    }

    duplicateHold?.let { hold ->
        DuplicateWarningDialog(
            match = hold.match,
            onKeep = {
                duplicateHold = null
                hold.proceed()
            },
            onCancel = {
                duplicateHold = null
                onClose()
            },
            onDismiss = { duplicateHold = null },
        )
    }
}
