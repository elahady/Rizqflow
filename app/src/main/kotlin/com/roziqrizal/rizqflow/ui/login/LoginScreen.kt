package com.roziqrizal.rizqflow.ui.login

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.auth.AuthBusy
import com.roziqrizal.rizqflow.auth.AuthMessage
import com.roziqrizal.rizqflow.ui.RizqflowIcons
import com.roziqrizal.rizqflow.ui.theme.rizqflow
import com.roziqrizal.rizqflow.ui.theme.rizqflowTonalButtonColors
import com.roziqrizal.rizqflow.ui.theme.spacing

/**
 * S30 Masuk (docs/ui-flow.md). Tampil setelah splash bila belum ada riwayat masuk. Masuk dengan
 * nama pengguna dan sandi (akun lokal), atau dengan Google; Gmail adalah izin tambahan yang
 * sengaja terpisah dan tidak wajib. Belum punya akun: ke halaman Daftar (S31).
 */
@Composable
fun LoginScreen(
    busy: AuthBusy?,
    message: AuthMessage?,
    showDebugLogin: Boolean,
    onGoogle: () -> Unit,
    onGoogleWithGmail: () -> Unit,
    onPasswordSignIn: (username: String, password: String) -> Unit,
    onOpenRegister: () -> Unit,
    onDebug: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val idle = busy == null
    var username by rememberSaveable { mutableStateOf("") }
    // Sandi sengaja tidak masuk penyimpanan state instance (rememberSaveable), supaya tidak ikut tersimpan.
    var password by remember { mutableStateOf("") }
    var attempted by rememberSaveable { mutableStateOf(false) }

    fun submit() {
        attempted = true
        if (username.isNotBlank() && password.isNotEmpty()) onPasswordSignIn(username, password)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.s5, vertical = spacing.s6),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Logo aplikasi (wordmark); versi tinta gelap di latar terang, versi putih di latar gelap.
        Image(
            painter = painterResource(
                if (MaterialTheme.colorScheme.background.luminance() < 0.5f) R.drawable.ic_rizqflow_wordmark
                else R.drawable.ic_rizqflow_wordmark_ink,
            ),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier.width(260.dp),
            contentScale = ContentScale.FillWidth,
        )
        Text(
            text = stringResource(R.string.login_tagline),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = spacing.s2),
        )
        Spacer(Modifier.height(spacing.s5))

        AuthTextField(
            value = username,
            onValueChange = { username = it },
            label = stringResource(R.string.field_username),
            enabled = idle,
            error = if (attempted && username.isBlank()) stringResource(R.string.login_fill_username) else null,
            contentType = ContentType.Username,
        )
        Spacer(Modifier.height(spacing.s2))
        AuthTextField(
            value = password,
            onValueChange = { password = it },
            label = stringResource(R.string.field_password),
            enabled = idle,
            isPassword = true,
            error = if (attempted && password.isEmpty()) stringResource(R.string.login_fill_password) else null,
            contentType = ContentType.Password,
            imeAction = ImeAction.Done,
            onImeAction = ::submit,
        )
        Button(
            onClick = ::submit,
            enabled = idle,
            modifier = Modifier.padding(top = spacing.s3).fillMaxWidth().height(52.dp),
        ) {
            if (busy == AuthBusy.PASSWORD) Progress() else Text(stringResource(R.string.login_submit))
        }
        MessageBanner(message)

        Row(
            modifier = Modifier.padding(vertical = spacing.s4).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s3),
        ) {
            HorizontalDivider(modifier = Modifier.weight(1f))
            Text(stringResource(R.string.login_or), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(modifier = Modifier.weight(1f))
        }

        // Cukup simbol G (varian ikon resmi Google); namanya dibacakan pembaca layar.
        GoogleIconButton(onClick = onGoogle, enabled = idle, busy = busy == AuthBusy.GOOGLE)
        Spacer(Modifier.height(spacing.s4))
        FilledTonalButton(
            onClick = onGoogleWithGmail,
            enabled = idle,
            colors = rizqflowTonalButtonColors(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (busy == AuthBusy.GMAIL) Progress() else Text(stringResource(R.string.login_gmail))
        }
        Text(
            text = stringResource(R.string.login_gmail_note),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = spacing.s2),
        )

        Row(
            modifier = Modifier.padding(top = spacing.s4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.login_register_prompt), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onOpenRegister, enabled = idle) { Text(stringResource(R.string.login_register_action)) }
        }

        Spacer(Modifier.height(spacing.s3))
        Column(verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
            listOf(R.string.login_point_local, R.string.login_point_offline, R.string.login_point_backup, R.string.login_point_no_ads).forEach {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.s2)) {
                    Icon(RizqflowIcons.Centang, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Text(stringResource(it), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (showDebugLogin) {
            TextButton(onClick = onDebug, enabled = idle, modifier = Modifier.padding(top = spacing.s3)) {
                Text(stringResource(R.string.login_debug))
            }
        }
    }
}

@Composable
private fun GoogleIconButton(onClick: () -> Unit, enabled: Boolean, busy: Boolean) {
    // Pedoman Google: latar putih (gelap #131314 di mode gelap) dengan garis tepi tipis.
    val dark = isSystemInDarkTheme()
    val name = stringResource(R.string.login_google)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = if (dark) Color(0xFF131314) else Color.White,
        border = BorderStroke(1.dp, if (dark) Color(0xFF8E918F) else Color(0xFF747775)),
        modifier = Modifier
            .size(64.dp)
            .semantics {
                contentDescription = name
                role = Role.Button
            },
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            if (busy) Progress() else Image(GoogleGLogo, contentDescription = null, modifier = Modifier.size(28.dp))
        }
    }
}

