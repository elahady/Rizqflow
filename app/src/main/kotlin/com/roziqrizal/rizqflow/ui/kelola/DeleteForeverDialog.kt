package com.roziqrizal.rizqflow.ui.kelola

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.ledger.LedgerError
import com.roziqrizal.rizqflow.ui.theme.spacing
import kotlinx.coroutines.launch

/**
 * Konfirmasi hapus permanen akun, kategori, atau ruang yang sudah diarsipkan (Tahap 9). Penghapusan tidak bisa dibatalkan dan
 * ikut tersinkron ke perangkat lain, jadi selalu lewat dialog ini. [onConfirm] mengembalikan galat domain bila hapus ditolak
 * (masih dirujuk riwayat, dibagi, dan sebagainya): dialog tetap terbuka dan menampilkan alasannya lewat [errorText]. Pada
 * keberhasilan (null) pemanggil menutup dialog dan memuat ulang daftar.
 */
@Composable
internal fun DeleteForeverDialog(
    title: String,
    body: String,
    errorText: @Composable (LedgerError) -> String,
    onDismiss: () -> Unit,
    onConfirm: suspend () -> LedgerError?,
) {
    var error by remember { mutableStateOf<LedgerError?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val spacing = MaterialTheme.spacing

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column {
                Text(body, style = MaterialTheme.typography.bodyMedium)
                error?.let {
                    Text(
                        errorText(it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = spacing.s3),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    busy = true
                    scope.launch {
                        error = onConfirm()
                        busy = false
                    }
                },
                enabled = !busy,
            ) { Text(stringResource(R.string.manage_delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.catat_date_cancel)) }
        },
    )
}
