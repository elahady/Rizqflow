package com.roziqrizal.rizqflow.ui.security

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.auth.AppLockService
import com.roziqrizal.rizqflow.domain.auth.UnlockResult
import com.roziqrizal.rizqflow.security.BiometricUnlock
import com.roziqrizal.rizqflow.ui.theme.spacing
import kotlinx.coroutines.delay

/**
 * Gerbang kunci aplikasi (S19): PIN 6 angka, dengan sidik jari ditawarkan otomatis bila aktif.
 * Lima kali salah menahan sementara; hitung mundurnya ditampilkan, bukan cuma pesan diam.
 */
@Composable
fun AppLockScreen(service: AppLockService, onUnlocked: () -> Unit) {
    val spacing = MaterialTheme.spacing
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    // Teks lewat resources yang ikut konfigurasi (LocalResources), bukan context.getString, supaya tidak basi saat konfigurasi berubah.
    val resources = LocalResources.current

    var digits by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var lockedUntil by remember { mutableStateOf<Long?>(null) }
    var biometricEnabled by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(service) { biometricEnabled = service.isBiometricEnabled() }

    fun tryBiometric() {
        if (activity != null && biometricEnabled && BiometricUnlock.isAvailable(activity)) {
            BiometricUnlock.prompt(
                activity,
                title = resources.getString(R.string.lock_biometric_title),
                subtitle = resources.getString(R.string.lock_biometric_subtitle),
                negativeText = resources.getString(R.string.lock_biometric_use_pin),
                onSuccess = onUnlocked,
            )
        }
    }

    LaunchedEffect(biometricEnabled) { tryBiometric() }

    LaunchedEffect(digits) {
        if (digits.length == AppLockService.PIN_LENGTH) {
            when (val result = service.verifyPin(digits)) {
                UnlockResult.Success -> onUnlocked()

                is UnlockResult.WrongPin -> {
                    error = resources.getString(R.string.lock_wrong_pin, result.attemptsLeft)
                    digits = ""
                }

                is UnlockResult.LockedOut -> {
                    error = null
                    now = System.currentTimeMillis()
                    lockedUntil = result.untilMillis
                    digits = ""
                }
            }
        }
    }

    // Hitung mundur terkunci sementara; lepas begitu waktunya habis.
    LaunchedEffect(lockedUntil) {
        val until = lockedUntil ?: return@LaunchedEffect
        while (System.currentTimeMillis() < until) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
        lockedUntil = null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(spacing.s5),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.headlineMedium)

        val until = lockedUntil
        if (until != null) {
            val remainingSeconds = ((until - now) / 1_000).coerceAtLeast(0) + 1
            Text(
                stringResource(R.string.lock_locked_out, remainingSeconds),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.s3),
            )
        } else {
            PinDots(length = AppLockService.PIN_LENGTH, filled = digits.length, modifier = Modifier.padding(top = spacing.s4))
            Text(
                error.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = spacing.s2),
            )
            Spacer(Modifier.weight(1f))
            PinKeypad(
                enabled = true,
                onKey = { key -> if (digits.length < AppLockService.PIN_LENGTH) { error = null; digits += key } },
                onBackspace = { digits = digits.dropLast(1) },
            )
            if (biometricEnabled) {
                TextButton(onClick = ::tryBiometric, modifier = Modifier.fillMaxWidth().padding(top = spacing.s3)) {
                    Text(stringResource(R.string.lock_use_biometric))
                }
            }
        }
        Spacer(Modifier.weight(1f))
    }
}
