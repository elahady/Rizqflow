package com.roziqrizal.rizqflow.ui.kelola

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.ledger.AccountOverview
import com.roziqrizal.rizqflow.domain.ledger.AccountRow
import com.roziqrizal.rizqflow.domain.ledger.Account
import com.roziqrizal.rizqflow.domain.ledger.Category
import com.roziqrizal.rizqflow.domain.ledger.FavoriteRow
import com.roziqrizal.rizqflow.domain.ledger.LedgerError
import com.roziqrizal.rizqflow.domain.ledger.LedgerResult
import com.roziqrizal.rizqflow.domain.ledger.LOCAL_CATEGORY_TEMPLATES
import com.roziqrizal.rizqflow.domain.ledger.NewAccount
import com.roziqrizal.rizqflow.domain.ledger.QuickFavorite
import com.roziqrizal.rizqflow.domain.ledger.RoomCategories
import com.roziqrizal.rizqflow.domain.model.AccountKind
import com.roziqrizal.rizqflow.domain.money.Money
import com.roziqrizal.rizqflow.ui.Notifier
import com.roziqrizal.rizqflow.ui.RizqflowIcons
import com.roziqrizal.rizqflow.ui.RoomTile
import com.roziqrizal.rizqflow.ui.formatRupiah
import com.roziqrizal.rizqflow.ui.theme.rizqflowFilterChipColors
import com.roziqrizal.rizqflow.ui.theme.spacing
import com.roziqrizal.rizqflow.workspace.AccountWorkspace
import kotlinx.coroutines.launch

/**
 * S13 Kelola akun, kategori, dan favorit. Tiga tab; akun dan kategori hanya diarsipkan, tidak dihapus,
 * karena riwayat transaksi tetap memakai namanya. Perubahan diteruskan lewat [onChanged] supaya layar
 * lain (Denah, Transaksi, Catat) memuat ulang. Jumlah akun tidak dibatasi.
 */
@Composable
fun KelolaScreen(workspace: AccountWorkspace, notifier: Notifier, onClose: () -> Unit, onChanged: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var version by remember { mutableIntStateOf(0) }
    val spacing = MaterialTheme.spacing
    fun changed() {
        version++
        onChanged()
    }
    BackHandler(onBack = onClose)

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        TextButton(onClick = onClose, modifier = Modifier.padding(start = spacing.s3, top = spacing.s2)) { Text(stringResource(R.string.onb_back)) }
        Text(
            stringResource(R.string.manage_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(horizontal = spacing.s5).padding(bottom = spacing.s2),
        )
        PrimaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
            listOf(R.string.manage_tab_accounts, R.string.manage_tab_categories, R.string.manage_tab_favorites).forEachIndexed { index, label ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(stringResource(label)) })
            }
        }
        when (tab) {
            0 -> AccountsTab(workspace, notifier, version, ::changed)
            1 -> CategoriesTab(workspace, notifier, version, ::changed)
            else -> FavoritesTab(workspace, notifier, version, ::changed)
        }
    }
}

internal fun manageErrorText(context: Context, error: LedgerError): String = context.getString(
    when (error) {
        LedgerError.NAME_TAKEN -> R.string.manage_err_taken
        LedgerError.INVALID_NAME -> R.string.manage_err_name
        LedgerError.AMOUNT_NOT_POSITIVE -> R.string.manage_err_amount
        LedgerError.LAST_ACCOUNT -> R.string.manage_err_last_account
        LedgerError.LAST_CATEGORY -> R.string.manage_err_last_category
        LedgerError.CATEGORY_IS_SYSTEM -> R.string.manage_err_system
        LedgerError.FAVORITE_LIMIT_REACHED -> R.string.manage_err_favorite_limit
        else -> R.string.catat_error_generic
    },
)

private fun kindLabel(kind: AccountKind): Int = when (kind) {
    AccountKind.CASH -> R.string.onb_kind_cash
    AccountKind.BANK -> R.string.onb_kind_bank
    AccountKind.EWALLET -> R.string.onb_kind_ewallet
}

/** Menyaring isian menjadi angka saja; kosong bila tidak ada angka. */
private fun digitsOnly(text: String): String = text.filter(Char::isDigit).take(12)

@Composable
private fun TabBody(content: @Composable ColumnScope.() -> Unit) {
    val spacing = MaterialTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.s4)
            .padding(top = spacing.s3, bottom = spacing.s5),
    ) { content() }
}

@Composable
private fun ManageRow(title: String, subtitle: String?, trailing: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = spacing.s3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s3),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.titleSmall)
        if (enabled) Icon(RizqflowIcons.PanahKanan, contentDescription = null, modifier = Modifier.size(20.dp))
    }
}

// ---------------------------------------------------------------------------------- akun

/** Sheet akun yang sedang terbuka: null tertutup, [AccountSheet.New] tambah, atau [AccountSheet.Edit] ubah. */
private sealed interface AccountSheet {
    data object New : AccountSheet

    data class Edit(val row: AccountRow) : AccountSheet
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountsTab(workspace: AccountWorkspace, notifier: Notifier, version: Int, changed: () -> Unit) {
    var overview by remember { mutableStateOf<AccountOverview?>(null) }
    var sheet by remember { mutableStateOf<AccountSheet?>(null) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val spacing = MaterialTheme.spacing

    LaunchedEffect(version) { overview = workspace.management.accountOverview() }
    val data = overview ?: return Box(Modifier.fillMaxSize())

    TabBody {
        data.active.forEach { row ->
            ManageRow(row.account.name, stringResource(kindLabel(row.account.kind)), formatRupiah(row.balance)) { sheet = AccountSheet.Edit(row) }
            HorizontalDivider()
        }
        Button(
            onClick = { sheet = AccountSheet.New },
            modifier = Modifier.padding(top = spacing.s4).fillMaxWidth().height(52.dp),
        ) {
            Icon(RizqflowIcons.Tambah, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.manage_add_account), modifier = Modifier.padding(start = spacing.s2))
        }

        if (data.archived.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(top = spacing.s5).fillMaxWidth().clickable(role = Role.Button) { showArchived = !showArchived }.padding(vertical = spacing.s2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.manage_archived, data.archived.size), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(if (showArchived) RizqflowIcons.PanahAtas else RizqflowIcons.PanahBawah, contentDescription = null)
            }
            if (showArchived) {
                data.archived.forEach { row ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = spacing.s1)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(row.account.name, style = MaterialTheme.typography.bodyMedium)
                            Text(formatRupiah(row.balance), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedButton(onClick = {
                            scope.launch {
                                when (val result = workspace.management.restoreAccount(row.account.id)) {
                                    is LedgerResult.Success -> changed()
                                    is LedgerResult.Failure -> notifier.show(manageErrorText(context, result.error))
                                }
                            }
                        }) { Text(stringResource(R.string.room_restore)) }
                    }
                }
            }
        }
    }

    sheet?.let { current ->
        ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            AccountForm(
                existing = (current as? AccountSheet.Edit)?.row,
                onCancel = { sheet = null },
                onSave = { name, kind, opening ->
                    val result = when (current) {
                        AccountSheet.New -> workspace.management.addAccount(NewAccount(name, kind, opening))
                        is AccountSheet.Edit -> workspace.management.updateAccount(current.row.account.id, name, kind)
                    }
                    if (result is LedgerResult.Success) {
                        sheet = null
                        changed()
                    }
                    (result as? LedgerResult.Failure)?.error
                },
                onArchive = (current as? AccountSheet.Edit)?.let { edit ->
                    {
                        when (val result = workspace.management.archiveAccount(edit.row.account.id)) {
                            is LedgerResult.Success -> {
                                sheet = null
                                changed()
                                null
                            }

                            is LedgerResult.Failure -> result.error
                        }
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountForm(
    existing: AccountRow?,
    onCancel: () -> Unit,
    onSave: suspend (String, AccountKind, Money) -> LedgerError?,
    onArchive: (suspend () -> LedgerError?)?,
) {
    val spacing = MaterialTheme.spacing
    var name by rememberSaveable { mutableStateOf(existing?.account?.name.orEmpty()) }
    var kind by rememberSaveable { mutableStateOf(existing?.account?.kind ?: AccountKind.CASH) }
    var opening by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<LedgerError?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = spacing.s5).padding(bottom = spacing.s5)) {
        Text(stringResource(if (existing == null) R.string.manage_new_account else R.string.manage_edit_account), style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it.take(Account.NAME_MAX)
                error = null
            },
            label = { Text(stringResource(R.string.manage_name)) },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(manageErrorText(context, it)) } },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth().padding(top = spacing.s3),
        )
        Text(stringResource(R.string.manage_kind), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = spacing.s3, bottom = spacing.s1))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.s2)) {
            AccountKind.entries.forEach { option ->
                FilterChip(selected = kind == option, onClick = { kind = option }, label = { Text(stringResource(kindLabel(option))) }, colors = rizqflowFilterChipColors())
            }
        }
        if (existing == null) {
            OutlinedTextField(
                value = opening,
                onValueChange = { opening = digitsOnly(it) },
                label = { Text(stringResource(R.string.manage_opening)) },
                prefix = { Text("Rp ") },
                supportingText = { Text(stringResource(R.string.manage_opening_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().padding(top = spacing.s3),
            )
        } else {
            Text(
                stringResource(R.string.manage_balance, formatRupiah(existing.balance)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.s3),
            )
        }
        Button(
            onClick = {
                busy = true
                scope.launch {
                    error = onSave(name, kind, Money.rupiah(opening.toLongOrNull() ?: 0L))
                    busy = false
                }
            },
            enabled = name.isNotBlank() && !busy,
            modifier = Modifier.padding(top = spacing.s5).fillMaxWidth().height(52.dp),
        ) { Text(stringResource(R.string.manage_save)) }
        if (onArchive != null) {
            OutlinedButton(
                onClick = {
                    busy = true
                    scope.launch {
                        error = onArchive()
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.padding(top = spacing.s2).fillMaxWidth().height(48.dp),
            ) { Text(stringResource(R.string.manage_archive)) }
        }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.catat_date_cancel)) }
    }
}

// ---------------------------------------------------------------------------------- kategori

private sealed interface CategorySheet {
    data class New(val roomId: com.roziqrizal.rizqflow.domain.model.RoomId, val roomName: String) : CategorySheet

    data class Edit(val category: Category) : CategorySheet
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoriesTab(workspace: AccountWorkspace, notifier: Notifier, version: Int, changed: () -> Unit) {
    var overview by remember { mutableStateOf<List<RoomCategories>?>(null) }
    var sheet by remember { mutableStateOf<CategorySheet?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val spacing = MaterialTheme.spacing

    LaunchedEffect(version) { overview = workspace.management.categoryOverview() }
    val data = overview ?: return Box(Modifier.fillMaxSize())

    TabBody {
        if (data.isEmpty()) {
            Text(stringResource(R.string.manage_no_rooms), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        data.forEach { group ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.s2),
                modifier = Modifier.padding(top = spacing.s4, bottom = spacing.s1),
            ) {
                RoomTile(group.room.iconKey, group.room.colorSlot, size = 32)
                Text(group.room.name, style = MaterialTheme.typography.titleMedium)
            }
            group.categories.forEach { category ->
                if (category.isSystem) {
                    ManageRow(category.name, stringResource(R.string.manage_system), enabled = false) {}
                } else {
                    ManageRow(category.name, null) { sheet = CategorySheet.Edit(category) }
                }
                HorizontalDivider()
            }
            TextButton(onClick = { sheet = CategorySheet.New(group.room.id, group.room.name) }) {
                Icon(RizqflowIcons.Tambah, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.manage_add_category), modifier = Modifier.padding(start = spacing.s1))
            }
            group.archived.forEach { category ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.manage_archived_category, category.name),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        scope.launch {
                            when (val result = workspace.management.restoreCategory(category.id)) {
                                is LedgerResult.Success -> changed()
                                is LedgerResult.Failure -> notifier.show(manageErrorText(context, result.error))
                            }
                        }
                    }) { Text(stringResource(R.string.room_restore)) }
                }
            }
        }
    }

    sheet?.let { current ->
        // Kategori yang sudah aktif di ruang itu tidak ditawarkan lagi sebagai usulan.
        val suggestions = (current as? CategorySheet.New)?.let { new ->
            val existing = data.firstOrNull { it.room.id == new.roomId }?.categories.orEmpty().map { it.name.lowercase() }.toSet()
            LOCAL_CATEGORY_TEMPLATES.filter { it.lowercase() !in existing }
        }.orEmpty()
        ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            CategoryForm(
                title = when (current) {
                    is CategorySheet.New -> stringResource(R.string.manage_new_category, current.roomName)
                    is CategorySheet.Edit -> stringResource(R.string.manage_edit_category)
                },
                initialName = (current as? CategorySheet.Edit)?.category?.name.orEmpty(),
                suggestions = suggestions,
                onCancel = { sheet = null },
                onSave = { name ->
                    val result = when (current) {
                        is CategorySheet.New -> workspace.management.addCategory(current.roomId, name)
                        is CategorySheet.Edit -> workspace.management.renameCategory(current.category.id, name)
                    }
                    if (result is LedgerResult.Success) {
                        sheet = null
                        changed()
                    }
                    (result as? LedgerResult.Failure)?.error
                },
                onArchive = (current as? CategorySheet.Edit)?.let { edit ->
                    {
                        when (val result = workspace.management.archiveCategory(edit.category.id)) {
                            is LedgerResult.Success -> {
                                sheet = null
                                changed()
                                null
                            }

                            is LedgerResult.Failure -> result.error
                        }
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryForm(
    title: String,
    initialName: String,
    suggestions: List<String> = emptyList(),
    onCancel: () -> Unit,
    onSave: suspend (String) -> LedgerError?,
    onArchive: (suspend () -> LedgerError?)?,
) {
    val spacing = MaterialTheme.spacing
    var name by rememberSaveable { mutableStateOf(initialName) }
    var error by remember { mutableStateOf<LedgerError?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.s5).padding(bottom = spacing.s5)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it.take(Category.NAME_MAX)
                error = null
            },
            label = { Text(stringResource(R.string.manage_name)) },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(manageErrorText(context, it)) } },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth().padding(top = spacing.s3),
        )
        if (suggestions.isNotEmpty()) {
            Text(
                stringResource(R.string.manage_category_suggestions),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.s3, bottom = spacing.s1),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.s2)) {
                suggestions.forEach { suggestion ->
                    FilterChip(
                        selected = name == suggestion,
                        onClick = {
                            name = suggestion
                            error = null
                        },
                        label = { Text(suggestion) },
                        colors = rizqflowFilterChipColors(),
                    )
                }
            }
        }
        Button(
            onClick = {
                busy = true
                scope.launch {
                    error = onSave(name)
                    busy = false
                }
            },
            enabled = name.isNotBlank() && !busy,
            modifier = Modifier.padding(top = spacing.s4).fillMaxWidth().height(52.dp),
        ) { Text(stringResource(R.string.manage_save)) }
        if (onArchive != null) {
            OutlinedButton(
                onClick = {
                    busy = true
                    scope.launch {
                        error = onArchive()
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.padding(top = spacing.s2).fillMaxWidth().height(48.dp),
            ) { Text(stringResource(R.string.manage_archive)) }
        }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.catat_date_cancel)) }
    }
}

// ---------------------------------------------------------------------------------- favorit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FavoritesTab(workspace: AccountWorkspace, notifier: Notifier, version: Int, changed: () -> Unit) {
    var rows by remember { mutableStateOf<List<FavoriteRow>?>(null) }
    var editing by remember { mutableStateOf<FavoriteRow?>(null) }
    val spacing = MaterialTheme.spacing

    LaunchedEffect(version) { rows = workspace.favorites.list() }
    val data = rows ?: return Box(Modifier.fillMaxSize())

    TabBody {
        Text(stringResource(R.string.manage_favorites_hint, QuickFavorite.MAX_COUNT), style = MaterialTheme.typography.titleSmall)
        if (data.isEmpty()) {
            Text(
                stringResource(R.string.manage_favorites_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.s3),
            )
        }
        data.forEach { row ->
            ManageRow(
                title = row.favorite.name,
                subtitle = if (row.usable) "${row.roomName}, ${row.categoryName}, ${row.accountName}" else stringResource(R.string.manage_favorite_unusable, row.roomName, row.categoryName, row.accountName),
                trailing = formatRupiah(row.favorite.amount),
            ) { editing = row }
            HorizontalDivider()
        }
        if (data.isNotEmpty()) {
            Text(
                stringResource(R.string.manage_favorites_create_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.s3),
            )
        }
    }

    editing?.let { row ->
        ModalBottomSheet(onDismissRequest = { editing = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            FavoriteForm(
                row = row,
                onCancel = { editing = null },
                onSave = { name, amount ->
                    val result = workspace.favorites.update(row.favorite.id, name, amount)
                    if (result is LedgerResult.Success) {
                        editing = null
                        changed()
                    }
                    (result as? LedgerResult.Failure)?.error
                },
                onDelete = {
                    workspace.favorites.delete(row.favorite.id)
                    editing = null
                    changed()
                },
            )
        }
    }
}

@Composable
private fun FavoriteForm(
    row: FavoriteRow,
    onCancel: () -> Unit,
    onSave: suspend (String, Money) -> LedgerError?,
    onDelete: suspend () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    var name by rememberSaveable { mutableStateOf(row.favorite.name) }
    var amount by rememberSaveable { mutableStateOf(row.favorite.amount.minor.toString()) }
    var error by remember { mutableStateOf<LedgerError?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = spacing.s5).padding(bottom = spacing.s5)) {
        Text(stringResource(R.string.manage_edit_favorite), style = MaterialTheme.typography.titleLarge)
        Text(
            "${row.roomName}, ${row.categoryName}, ${row.accountName}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it.take(QuickFavorite.NAME_MAX)
                error = null
            },
            label = { Text(stringResource(R.string.manage_name)) },
            singleLine = true,
            isError = error == LedgerError.NAME_TAKEN || error == LedgerError.INVALID_NAME,
            supportingText = error?.let { { Text(manageErrorText(context, it)) } },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth().padding(top = spacing.s3),
        )
        OutlinedTextField(
            value = amount,
            onValueChange = {
                amount = digitsOnly(it)
                error = null
            },
            label = { Text(stringResource(R.string.manage_amount)) },
            prefix = { Text("Rp ") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().padding(top = spacing.s2),
        )
        Button(
            onClick = {
                busy = true
                scope.launch {
                    error = onSave(name, Money.rupiah(amount.toLongOrNull() ?: 0L))
                    busy = false
                }
            },
            enabled = name.isNotBlank() && amount.isNotBlank() && !busy,
            modifier = Modifier.padding(top = spacing.s4).fillMaxWidth().height(52.dp),
        ) { Text(stringResource(R.string.manage_save)) }
        OutlinedButton(
            onClick = {
                busy = true
                scope.launch { onDelete() }
            },
            enabled = !busy,
            modifier = Modifier.padding(top = spacing.s2).fillMaxWidth().height(48.dp),
        ) { Text(stringResource(R.string.manage_delete), color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.catat_date_cancel)) }
    }
}
