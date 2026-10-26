package com.roziqrizal.rizqflow.ui.catat

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.ledger.MoneyTransaction
import com.roziqrizal.rizqflow.ui.formatRupiah

/**
 * Simpan yang ditahan karena ada kemungkinan transaksi ganda ([match]). [proceed] menyimpan tanpa
 * memeriksa ulang; dipanggil bila pengguna memilih "Simpan tetap".
 */
class DuplicateHold(val match: MoneyTransaction, val proceed: () -> Unit)

/**
 * Peringatan "Mungkin sudah dicatat?" untuk simpan transaksi baru secara manual. Pilihannya dua:
 * simpan tetap, atau batalkan karena sudah tercatat. Menutup dialog tanpa memilih tidak menyimpan.
 */
@Composable
fun DuplicateWarningDialog(
    match: MoneyTransaction,
    onKeep: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.catat_duplicate_title)) },
        text = { Text(stringResource(R.string.catat_duplicate_body, formatRupiah(match.amount))) },
        confirmButton = { TextButton(onClick = onKeep) { Text(stringResource(R.string.catat_duplicate_keep)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.catat_duplicate_cancel)) } },
    )
}
