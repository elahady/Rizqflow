package com.roziqrizal.rizqflow.ui.recovery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.sync.RecoverKeyResult
import com.roziqrizal.rizqflow.domain.sync.RecoveryCode
import com.roziqrizal.rizqflow.domain.sync.RecoveryWordsInput
import com.roziqrizal.rizqflow.sync.recoverKeyRetrying
import com.roziqrizal.rizqflow.sync.sessionEndedBy
import com.roziqrizal.rizqflow.ui.Notifier
import com.roziqrizal.rizqflow.ui.RizqflowIcons
import com.roziqrizal.rizqflow.ui.theme.spacing
import com.roziqrizal.rizqflow.workspace.AccountWorkspace
import kotlinx.coroutines.launch

/**
 * Pulihkan kunci akun di perangkat baru dengan kode pemulihan 24 kata (Tahap 13). Satu kolom tempel yang dipecah
 * ([RecoveryWordsInput.split]) dengan validasi per kata terhadap daftar baku, dan dialog konfirmasi SEBELUM
 * menimpa kunci akun lokal. Pulihkan MENIMPA kunci lokal: saat ini aman karena belum ada data lokal yang dienkripsi
 * dengan kunci akun (sync data akun belum dibangun; yang ada baru kunci ruang, yang tidak disentuh); begitu sync
 * entitas akun ada, pulihkan wajib menjaga data lama (kewajiban butir sync berikutnya).
 *
 * Kata yang diketik hanya di `remember` (tidak `rememberSaveable`), jadi tidak ikut tersimpan di Bundle dan hilang
 * bila layar diputar; itu disengaja demi tidak menyimpan kode di mana pun. Kode tidak dicatat di log.
 * Berhasil: penanda "terkonfigurasi" dituliskan oleh [RecoveryKeyService.recoverKey] dan kode TIDAK ditampilkan.
 */
@Composable
fun RecoveryRestoreScreen(
    workspace: AccountWorkspace,
    notifier: Notifier,
    onRecovered: () -> Unit,
    onClose: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current

    var text by remember { mutableStateOf("") }
    // Perubahan terakhir berupa tempel (lebih dari satu karakter sekaligus): kata terakhirnya sudah utuh, bukan sedang diketik.
    var lastChangeWasBulk by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorRes by remember { mutableStateOf<Int?>(null) }
    var confirm by remember { mutableStateOf(false) }

    val words = RecoveryWordsInput.split(text)
    // Sah-tidaknya kode memeriksa SEMUA kata; peringatan di layar menunda token terakhir selama masih diketik.
    val unknown = RecoveryWordsInput.unknownWords(words)
    val unknownToWarn = RecoveryWordsInput.unknownWordsToWarn(text, pasted = lastChangeWasBulk)
    val canRestore = words.size == RecoveryCode.WORD_COUNT && unknown.isEmpty() && !busy

    // Selalu aktif: Back selagi memulihkan diabaikan, bukan menutup Activity di tengah pemulihan.
    BackHandler { if (!busy) onClose() }

    fun restore() {
        busy = true
        errorRes = null
        scope.launch {
            val result = workspace.recovery.recoverKeyRetrying(workspace.serverSession, words)
            busy = false
            if (result == RecoverKeyResult.Recovered) {
                text = ""
                notifier.show(resources.getString(R.string.restore_success))
                onRecovered()
            } else {
                val ended = (result as? RecoverKeyResult.Failed)?.let { workspace.serverSession.sessionEndedBy(it.error) } ?: false
                errorRes = recoverFailureMessage(result, sessionEnded = ended)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        TextButton(onClick = onClose, enabled = !busy, modifier = Modifier.padding(start = spacing.s3, top = spacing.s2)) {
            Text(stringResource(R.string.onb_back))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.s5)
                .padding(bottom = spacing.s5),
        ) {
            Text(stringResource(R.string.restore_title), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.restore_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.s3),
            )
            OutlinedTextField(
                value = text,
                onValueChange = { changed ->
                    lastChangeWasBulk = RecoveryWordsInput.isBulkChange(text, changed)
                    text = changed
                },
                enabled = !busy,
                label = { Text(stringResource(R.string.restore_field_label)) },
                minLines = 5,
                // Papan ketik tanpa koreksi otomatis dan tanpa huruf kapital otomatis. Belum diverifikasi di perangkat:
                // papan ketik pihak ketiga bisa tetap mempelajari isiannya.
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Text),
                modifier = Modifier.padding(top = spacing.s4).fillMaxWidth(),
            )
            Text(
                stringResource(R.string.restore_counter, words.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.s1),
            )
            // Salah ketik per kata: nomor dan kata yang tidak ada di daftar baku, kata yang pertama saja supaya tidak ramai.
            unknownToWarn.firstOrNull()?.let { (position, word) ->
                Row(modifier = Modifier.padding(top = spacing.s2).semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(RizqflowIcons.Peringatan, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(
                        stringResource(R.string.restore_unknown_word, position, word),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = spacing.s2),
                    )
                }
            }
            errorRes?.let { res ->
                Row(modifier = Modifier.padding(top = spacing.s3).semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(RizqflowIcons.Peringatan, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(stringResource(res), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = spacing.s2))
                }
            }
            if (busy) {
                Row(modifier = Modifier.padding(top = spacing.s4), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.restore_working), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = spacing.s3))
                }
            }
            Button(
                onClick = { confirm = true },
                enabled = canRestore,
                modifier = Modifier.padding(top = spacing.s5).fillMaxWidth().height(52.dp),
            ) { Text(stringResource(R.string.restore_action)) }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.restore_confirm_title)) },
            text = { Text(stringResource(R.string.restore_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    restore()
                }) { Text(stringResource(R.string.restore_confirm_action)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.catat_date_cancel)) } },
        )
    }
}
