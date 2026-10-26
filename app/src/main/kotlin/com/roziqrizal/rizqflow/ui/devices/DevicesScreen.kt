package com.roziqrizal.rizqflow.ui.devices

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.sync.ServerDevice
import com.roziqrizal.rizqflow.ui.recovery.formatConfiguredAt
import com.roziqrizal.rizqflow.ui.theme.spacing
import java.time.Instant
import kotlinx.coroutines.launch

/**
 * Layar "Perangkat yang masuk" (Tahap 14, menu Lainnya): daftar perangkat yang masuk ke akun server ini dan tombol
 * "Keluar dari semua perangkat". Logikanya ada di [DevicesViewModel]; layar ini hanya menampilkan keadaannya.
 * [onSignedOut] dipanggil sekali setelah server mencabut semua token, supaya pemanggil mengeluarkan akun di ponsel ini.
 */
@Composable
fun DevicesScreen(
    viewModel: DevicesViewModel,
    onClose: () -> Unit,
    onSignedOut: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val spacing = MaterialTheme.spacing
    var confirmSignOut by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(state.signedOutEverywhere) {
        if (state.signedOutEverywhere) onSignedOut()
    }
    BackHandler { onClose() }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        TextButton(
            onClick = onClose,
            modifier = Modifier.padding(start = spacing.s3, top = spacing.s2),
        ) { Text(stringResource(R.string.onb_back)) }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.s5)
                .padding(bottom = spacing.s5),
        ) {
            Text(stringResource(R.string.devices_title), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.devices_intro),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = spacing.s3),
            )

            if (state.loading) {
                Row(modifier = Modifier.padding(top = spacing.s4), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = spacing.s3))
                    Text(stringResource(R.string.devices_loading), style = MaterialTheme.typography.bodyMedium)
                }
            }

            state.loadFailure?.let { failure ->
                Text(
                    stringResource(loadFailureText(failure)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = spacing.s4),
                )
                OutlinedButton(
                    onClick = { scope.launch { viewModel.load() } },
                    modifier = Modifier.padding(top = spacing.s3),
                ) { Text(stringResource(R.string.devices_retry)) }
            }

            if (!state.loading && state.loadFailure == null && state.devices.isEmpty()) {
                Text(
                    stringResource(R.string.devices_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = spacing.s4),
                )
            }

            val now = remember(state.devices) { Instant.now() }
            state.devices.forEach { device -> DeviceCard(device, now) }

            Text(
                stringResource(R.string.devices_sign_out_all_sub),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = spacing.s5),
            )
            Button(
                onClick = { confirmSignOut = true },
                enabled = !state.revoking && !state.signedOutEverywhere,
                modifier = Modifier.fillMaxWidth().padding(top = spacing.s3),
            ) {
                if (state.revoking) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = spacing.s3))
                    Text(stringResource(R.string.devices_revoking))
                } else {
                    Text(stringResource(R.string.devices_sign_out_all))
                }
            }
            state.revokeFailure?.let { failure ->
                Text(
                    stringResource(revokeFailureText(failure)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = spacing.s3),
                )
            }
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.devices_sign_out_confirm_title)) },
            text = { Text(stringResource(R.string.devices_sign_out_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    scope.launch { viewModel.signOutEverywhere() }
                }) { Text(stringResource(R.string.devices_sign_out_confirm_action)) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.devices_sign_out_cancel)) } },
        )
    }
}

/** Satu perangkat: nama yang dikenali (atau label bawaan), waktu terakhir dipakai, dan masa berlaku. */
@Composable
private fun DeviceCard(device: ServerDevice, now: Instant) {
    val spacing = MaterialTheme.spacing
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = spacing.s3),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(spacing.s4), verticalArrangement = Arrangement.spacedBy(spacing.s1)) {
            Text(
                device.deviceName ?: device.label,
                style = MaterialTheme.typography.titleMedium,
            )
            if (device.current) {
                Text(stringResource(R.string.devices_this_device), style = MaterialTheme.typography.labelLarge)
            }
            val lastUsed = device.lastUsedAt
            Text(
                if (lastUsed != null) {
                    stringResource(R.string.devices_last_used, formatConfiguredAt(lastUsed))
                } else {
                    stringResource(R.string.devices_never_used)
                },
                style = MaterialTheme.typography.bodySmall,
            )
            device.createdAt?.let { created ->
                Text(stringResource(R.string.devices_signed_in_at, formatConfiguredAt(created)), style = MaterialTheme.typography.bodySmall)
            }
            device.expiresAt?.let { expires ->
                val text = if (device.isExpiredAt(now)) {
                    stringResource(R.string.devices_expired_at, formatConfiguredAt(expires))
                } else {
                    stringResource(R.string.devices_expires_at, formatConfiguredAt(expires))
                }
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun loadFailureText(failure: DevicesFailure): Int = when (failure) {
    DevicesFailure.SessionEnded -> R.string.devices_failure_session
    DevicesFailure.Offline -> R.string.devices_failure_offline
    DevicesFailure.Other -> R.string.devices_failure_other
}

private fun revokeFailureText(failure: DevicesFailure): Int = when (failure) {
    DevicesFailure.SessionEnded -> R.string.devices_revoke_failed_session
    DevicesFailure.Offline -> R.string.devices_revoke_failed_offline
    DevicesFailure.Other -> R.string.devices_revoke_failed_other
}
