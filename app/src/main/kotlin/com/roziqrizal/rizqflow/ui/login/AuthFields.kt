package com.roziqrizal.rizqflow.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.auth.AuthMessage
import com.roziqrizal.rizqflow.domain.auth.CredentialIssue
import com.roziqrizal.rizqflow.ui.RizqflowIcons
import com.roziqrizal.rizqflow.ui.theme.rizqflow
import com.roziqrizal.rizqflow.ui.theme.spacing

/**
 * Kolom isian untuk halaman masuk dan daftar. Kolom sandi punya tombol tampilkan/sembunyikan
 * (teks, bukan ikon, supaya jelas bagi pembaca layar) dan memberi petunjuk isi otomatis
 * ([contentType]) ke pengelola sandi milik sistem.
 */
@Composable
internal fun AuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    error: String? = null,
    hint: String? = null,
    isPassword: Boolean = false,
    contentType: ContentType? = null,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        isError = error != null,
        supportingText = (error ?: hint)?.let { { Text(it) } },
        visualTransformation = if (isPassword && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Text,
            // Huruf besar otomatis hanya untuk nama orang; nama pengguna dan sandi harus persis seperti diketik.
            capitalization = if (contentType == ContentType.PersonFirstName) KeyboardCapitalization.Words else KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(onDone = { onImeAction() }),
        trailingIcon = if (isPassword) {
            {
                TextButton(onClick = { visible = !visible }, enabled = enabled) {
                    Text(stringResource(if (visible) R.string.password_hide else R.string.password_show))
                }
            }
        } else {
            null
        },
        modifier = modifier
            .fillMaxWidth()
            .semantics { if (contentType != null) this.contentType = contentType },
    )
}

/** Pesan lembut: tidak merah, tidak memblokir. Dibacakan pembaca layar saat muncul. */
@Composable
internal fun MessageBanner(message: AuthMessage?, modifier: Modifier = Modifier) {
    val spacing = MaterialTheme.spacing
    Box(modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        if (message != null) {
            Row(
                modifier = Modifier
                    .padding(top = spacing.s4)
                    .fillMaxWidth()
                    .background(MaterialTheme.rizqflow.statusWarning.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
                    .padding(spacing.s3),
                horizontalArrangement = Arrangement.spacedBy(spacing.s3),
            ) {
                Icon(RizqflowIcons.Peringatan, contentDescription = null, tint = MaterialTheme.rizqflow.statusWarning)
                Text(stringResource(messageText(message)), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
internal fun Progress() {
    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
}

internal fun messageText(message: AuthMessage): Int = when (message) {
    AuthMessage.NOT_CONFIGURED -> R.string.msg_not_configured
    AuthMessage.FAILED -> R.string.msg_sign_in_failed
    AuthMessage.GMAIL_DENIED -> R.string.msg_gmail_denied
    AuthMessage.GMAIL_UNAVAILABLE -> R.string.msg_gmail_unavailable
    AuthMessage.WRONG_CREDENTIALS -> R.string.msg_wrong_credentials
    AuthMessage.USERNAME_TAKEN -> R.string.msg_username_taken
    AuthMessage.INVALID_INPUT -> R.string.msg_invalid_input
    AuthMessage.RECONNECT_WRONG_ACCOUNT -> R.string.msg_reconnect_wrong_account
}

/** Pesan di bawah kolom untuk satu masalah isian. */
internal fun issueText(issue: CredentialIssue): Int = when (issue) {
    CredentialIssue.USERNAME_TOO_SHORT -> R.string.issue_username_short
    CredentialIssue.USERNAME_TOO_LONG -> R.string.issue_username_long
    CredentialIssue.USERNAME_INVALID_CHARACTERS -> R.string.issue_username_chars
    CredentialIssue.PASSWORD_TOO_SHORT -> R.string.issue_password_short
    CredentialIssue.PASSWORD_TOO_LONG -> R.string.issue_password_long
    CredentialIssue.PASSWORD_SAME_AS_USERNAME -> R.string.issue_password_same
    CredentialIssue.PASSWORD_MISMATCH -> R.string.issue_password_mismatch
}
