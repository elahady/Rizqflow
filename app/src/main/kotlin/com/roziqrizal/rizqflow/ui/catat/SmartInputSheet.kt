package com.roziqrizal.rizqflow.ui.catat

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.entitlement.Feature
import com.roziqrizal.rizqflow.domain.ledger.CatatContext
import com.roziqrizal.rizqflow.domain.ledger.CatatDraft
import com.roziqrizal.rizqflow.domain.model.TransactionKind
import com.roziqrizal.rizqflow.domain.smartinput.ParsedEntry
import com.roziqrizal.rizqflow.domain.smartinput.SmartField
import com.roziqrizal.rizqflow.domain.smartinput.SmartInputContext
import com.roziqrizal.rizqflow.domain.smartinput.SmartInputParser
import com.roziqrizal.rizqflow.ui.RizqflowIcons
import com.roziqrizal.rizqflow.ui.formatDate
import com.roziqrizal.rizqflow.ui.formatRupiah
import com.roziqrizal.rizqflow.ui.theme.rizqflowFilterChipColors
import com.roziqrizal.rizqflow.ui.theme.spacing
import com.roziqrizal.rizqflow.workspace.AccountWorkspace
import java.time.LocalDate

/**
 * Input cerdas (Tahap 11, Gratis): satu baris bebas seperti "gojek 23rb dari gopay" diuraikan lewat
 * `SmartInputParser`. Hasilnya TIDAK PERNAH tersimpan dari sini — [onUse] hanya mengisi ulang
 * [CatatDraft] layar Catat yang memanggilnya, tempat setiap kolom masih bisa diperiksa dan diubah
 * sebelum Simpan ditekan. Riwayat pengeluaran (tebakan kategori) dimuat sekali saat sheet dibuka,
 * lalu penguraian tiap ketikan berjalan di tempat tanpa akses database lagi.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SmartInputSheet(
    workspace: AccountWorkspace,
    catatContext: CatatContext,
    today: LocalDate,
    onUse: (CatatDraft) -> Unit,
    onDismiss: () -> Unit,
    /** Dipanggil saat ikon mikrofon ditekan tanpa Pro; pemanggil menutup sheet ini dan membuka paywall. */
    onVoiceLocked: () -> Unit = {},
) {
    var smartContext by remember { mutableStateOf<SmartInputContext?>(null) }
    LaunchedEffect(Unit) { smartContext = workspace.smartInput.contextFor(catatContext, today) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        val spacing = MaterialTheme.spacing
        var text by rememberSaveable { mutableStateOf("") }
        val loaded = smartContext
        val entry = loaded?.let { SmartInputParser.parse(text, it) }
        val focusRequester = remember { FocusRequester() }
        val focusManager = LocalFocusManager.current
        LaunchedEffect(Unit) { focusRequester.requestFocus() }

        // Input suara: hanya Pro (Feature.VOICE_INPUT). Hasil pengenal masuk ke kolom teks yang sama, lalu
        // diurai dan ditampilkan seperti ketikan; tidak pernah tersimpan sendiri.
        val voiceUnlocked = workspace.entitlements.isEnabled(Feature.VOICE_INPUT)
        var voiceUnavailable by remember { mutableStateOf(false) }
        val speech = rememberLauncherForActivityResult(SpeechRecognitionContract()) { spoken ->
            if (spoken != null) {
                text = spoken
                voiceUnavailable = false
            }
        }

        fun use() {
            val current = entry ?: return
            onUse(SmartInputParser.toDraft(current, catatContext, today))
        }

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.s5).padding(bottom = spacing.s5)) {
            Text(stringResource(R.string.catat_smart_input_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.catat_smart_input_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.s1, bottom = spacing.s3),
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.catat_smart_input_placeholder)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (entry != null) {
                        use()
                    } else {
                        focusManager.clearFocus()
                    }
                }),
                trailingIcon = {
                    IconButton(onClick = {
                        if (!voiceUnlocked) {
                            onVoiceLocked()
                            return@IconButton
                        }
                        try {
                            speech.launch(Unit)
                        } catch (e: ActivityNotFoundException) {
                            voiceUnavailable = true
                        }
                    }) {
                        Icon(
                            RizqflowIcons.Mikrofon,
                            contentDescription = stringResource(if (voiceUnlocked) R.string.catat_voice_start else R.string.catat_voice_locked),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
            if (!voiceUnlocked) {
                Text(
                    stringResource(R.string.catat_voice_locked_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.s2),
                )
            }
            if (voiceUnavailable) {
                Text(
                    stringResource(R.string.catat_voice_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = spacing.s2),
                )
            }

            if (text.isNotBlank()) {
                if (entry != null) {
                    SmartInputPreview(entry, catatContext, today)
                } else {
                    Text(
                        stringResource(R.string.catat_smart_input_unreadable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = spacing.s3),
                    )
                }
            }

            Button(
                onClick = ::use,
                enabled = entry != null,
                modifier = Modifier.padding(top = spacing.s4).fillMaxWidth(),
            ) { Text(stringResource(R.string.catat_smart_input_use)) }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.catat_date_cancel)) }
        }
    }
}

/**
 * Pratinjau ringkas: satu baris ringkasan, lalu chip per kolom yang benar-benar dikenali
 * ([ParsedEntry.understood]) supaya jelas apa yang akan diisi otomatis di layar Catat berikutnya.
 * Kolom yang tidak dikenali sengaja tidak ditampilkan di sini; layar Catat sendiri yang menawarkannya.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SmartInputPreview(entry: ParsedEntry, context: CatatContext, today: LocalDate) {
    val spacing = MaterialTheme.spacing
    val kindLabel = stringResource(
        when (entry.kind) {
            TransactionKind.INCOME -> R.string.catat_tab_income
            TransactionKind.TRANSFER -> R.string.catat_tab_transfer
            else -> R.string.catat_tab_expense
        },
    )
    Text(
        if (entry.note.isNotBlank()) "$kindLabel · ${formatRupiah(entry.amount)} · ${entry.note}" else "$kindLabel · ${formatRupiah(entry.amount)}",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = spacing.s3),
    )
    // Hanya kolom yang benar-benar dikenali ditampilkan sebagai chip; sisanya diisi bawaan Catat dan diperiksa di sana.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.s2), modifier = Modifier.padding(top = spacing.s2)) {
        if (SmartField.DATE in entry.understood) SmartChip(formatDate(entry.date, today))
        entry.source?.takeIf { SmartField.SOURCE in entry.understood }?.let { SmartChip(it) }
        entry.accountId?.takeIf { SmartField.ACCOUNT in entry.understood }
            ?.let { id -> context.accounts.firstOrNull { it.id == id }?.name }
            ?.let { SmartChip(it) }
        entry.toAccountId?.takeIf { SmartField.TO_ACCOUNT in entry.understood }
            ?.let { id -> context.accounts.firstOrNull { it.id == id }?.name }
            ?.let { SmartChip(stringResource(R.string.recurring_into, it)) }
        entry.categoryId?.takeIf { SmartField.CATEGORY in entry.understood }
            ?.let { id -> entry.roomId?.let { context.categoriesOf(it) }?.firstOrNull { it.id == id }?.name }
            ?.let { SmartChip(it) }
        entry.roomId?.takeIf { SmartField.ROOM in entry.understood && SmartField.CATEGORY !in entry.understood }
            ?.let { id -> context.rooms.firstOrNull { it.id == id }?.name }
            ?.let { SmartChip(it) }
    }
}

@Composable
private fun SmartChip(label: String) {
    FilterChip(
        selected = true,
        onClick = {},
        label = { Text(label) },
        leadingIcon = { Icon(RizqflowIcons.Centang, contentDescription = null) },
        colors = rizqflowFilterChipColors(),
    )
}
