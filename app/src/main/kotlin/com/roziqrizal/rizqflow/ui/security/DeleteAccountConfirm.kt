package com.roziqrizal.rizqflow.ui.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.auth.AppLockService
import com.roziqrizal.rizqflow.domain.auth.DeleteAccountConfirmation
import com.roziqrizal.rizqflow.domain.auth.DeleteAccountPinCheck
import com.roziqrizal.rizqflow.domain.auth.DeleteAccountRequirement
import com.roziqrizal.rizqflow.ui.theme.spacing
import kotlinx.coroutines.delay

/**
 * Langkah konfirmasi di bottom sheet Hapus akun server (Tahap 9, keputusan pemilik 2026-10-24), dipasang
 * setelah judul dan penjelasan. Bila kunci aplikasi aktif, pengguna memasukkan PIN lewat papan PIN yang
 * sama dengan layar kunci, dicek lewat [DeleteAccountConfirmation] (penghitung salah dan penguncian sama
 * dengan layar kunci). Bila tidak, pengguna mengetik HAPUS. Tombol hapus hanya aktif bila syarat terpenuhi
 * dan [busy] false; aturannya ada di :domain dan diuji di sana.
 */
@Composable
fun DeleteAccountConfirmContent(
    confirmation: DeleteAccountConfirmation,
    busy: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val context = LocalContext.current

    var requirement by remember { mutableStateOf<DeleteAccountRequirement?>(null) }
    LaunchedEffect(confirmation) { requirement = confirmation.requirement() }

    var typed by rememberSaveable { mutableStateOf("") }
    var digits by remember { mutableStateOf("") }
    var pinVerified by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var lockedUntil by remember { mutableStateOf<Long?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(digits) {
        if (requirement != DeleteAccountRequirement.PIN || pinVerified) return@LaunchedEffect
        if (digits.length != AppLockService.PIN_LENGTH) return@LaunchedEffect
        when (val check = confirmation.verifyPin(digits)) {
            DeleteAccountPinCheck.Verified -> {
                pinVerified = true
                error = null
            }

            is DeleteAccountPinCheck.Wrong -> {
                error = context.getString(R.string.lock_wrong_pin, check.attemptsLeft)
                digits = ""
            }

            is DeleteAccountPinCheck.LockedOut -> {
                error = null
                now = System.currentTimeMillis()
                lockedUntil = check.untilMillis
                digits = ""
            }
        }
    }

    // Hitung mundur terkunci sementara; lepas begitu waktunya habis (sama dengan layar kunci).
    LaunchedEffect(lockedUntil) {
        val until = lockedUntil ?: return@LaunchedEffect
        while (System.currentTimeMillis() < until) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
        lockedUntil = null
    }

    val current = requirement
    val confirmable = current != null && DeleteAccountConfirmation.canConfirm(current, typed, pinVerified)

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
        when (current) {
            null -> Unit

            DeleteAccountRequirement.PIN -> {
                val until = lockedUntil
                when {
                    pinVerified -> Text(stringResource(R.string.account_delete_pin_verified), style = MaterialTheme.typography.bodyMedium)

                    until != null -> Text(
                        stringResource(R.string.lock_locked_out, ((until - now) / 1_000).coerceAtLeast(0) + 1),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> {
                        Text(stringResource(R.string.account_delete_pin_prompt), style = MaterialTheme.typography.bodyMedium)
                        PinDots(length = AppLockService.PIN_LENGTH, filled = digits.length, modifier = Modifier.padding(top = spacing.s2))
                        Text(error.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        PinKeypad(
                            enabled = !busy,
                            onKey = { key -> if (digits.length < AppLockService.PIN_LENGTH) { error = null; digits += key } },
                            onBackspace = { digits = digits.dropLast(1) },
                        )
                    }
                }
            }

            DeleteAccountRequirement.TYPED_WORD -> {
                Text(stringResource(R.string.account_delete_word_prompt), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    placeholder = { Text(DeleteAccountConfirmation.CONFIRM_WORD) },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Button(
            onClick = onConfirm,
            enabled = confirmable && !busy,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.padding(top = spacing.s2).fillMaxWidth().height(52.dp),
        ) { Text(stringResource(R.string.account_delete_confirm_action)) }
        TextButton(onClick = onCancel, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.catat_date_cancel))
        }
    }
}
