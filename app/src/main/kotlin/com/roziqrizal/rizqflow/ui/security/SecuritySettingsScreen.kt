package com.roziqrizal.rizqflow.ui.security

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.auth.AppLockService
import com.roziqrizal.rizqflow.domain.auth.AutoLockMode
import com.roziqrizal.rizqflow.domain.auth.SetBiometricResult
import com.roziqrizal.rizqflow.domain.auth.UnlockResult
import com.roziqrizal.rizqflow.security.BiometricUnlock
import com.roziqrizal.rizqflow.ui.theme.rizqflowFilterChipColors
import com.roziqrizal.rizqflow.ui.theme.spacing
import kotlinx.coroutines.launch

private enum class Purpose { SETUP, CHANGE, DISABLE }
private enum class Stage { CURRENT, NEW, CONFIRM }
private data class PinWizard(val purpose: Purpose, val stage: Stage, val newPin: String? = null, val error: String? = null)

/** S19 Keamanan: PIN, biometrik, dan kunci otomatis. Berlaku untuk aplikasi, bukan satu akun. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SecuritySettingsScreen(service: AppLockService, onClose: () -> Unit) {
    val spacing = MaterialTheme.spacing
    val context = LocalContext.current
    val resources = LocalResources.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onClose)

    var pinSet by remember { mutableStateOf(false) }
    var biometricEnabled by remember { mutableStateOf(false) }
    var autoLock by remember { mutableStateOf(AutoLockMode.IMMEDIATELY) }
    var wizard by rememberSaveable(stateSaver = wizardSaver) { mutableStateOf<PinWizard?>(null) }
    var digits by rememberSaveable { mutableStateOf("") }

    suspend fun reload() {
        pinSet = service.isPinSet()
        biometricEnabled = service.isBiometricEnabled()
        autoLock = service.autoLockMode()
    }
    LaunchedEffect(service) { reload() }

    fun start(purpose: Purpose) {
        digits = ""
        wizard = PinWizard(purpose, if (purpose == Purpose.SETUP) Stage.NEW else Stage.CURRENT)
    }

    fun submit(pin: String) {
        val w = wizard ?: return
        scope.launch {
            when (w.stage) {
                Stage.CURRENT -> when (val result = service.verifyPin(pin)) {
                    UnlockResult.Success -> {
                        digits = ""
                        wizard = if (w.purpose == Purpose.DISABLE) {
                            service.clearPin()
                            reload()
                            null
                        } else {
                            w.copy(stage = Stage.NEW, error = null)
                        }
                    }

                    is UnlockResult.WrongPin -> {
                        digits = ""
                        wizard = w.copy(error = resources.getString(R.string.lock_wrong_pin, result.attemptsLeft))
                    }

                    is UnlockResult.LockedOut -> {
                        digits = ""
                        wizard = w.copy(error = resources.getString(R.string.security_locked_out))
                    }
                }

                Stage.NEW -> {
                    digits = ""
                    wizard = w.copy(stage = Stage.CONFIRM, newPin = pin, error = null)
                }

                Stage.CONFIRM -> {
                    digits = ""
                    if (pin == w.newPin) {
                        service.setPin(pin)
                        reload()
                        wizard = null
                    } else {
                        wizard = w.copy(stage = Stage.NEW, newPin = null, error = resources.getString(R.string.security_pin_mismatch))
                    }
                }
            }
        }
    }

    val activeWizard = wizard
    if (activeWizard != null) {
        PinWizardScreen(
            wizard = activeWizard,
            digits = digits,
            onKey = { key -> if (digits.length < AppLockService.PIN_LENGTH) digits += key },
            onBackspace = { digits = digits.dropLast(1) },
            onSubmit = ::submit,
            onCancel = { wizard = null; digits = "" },
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        TextButton(onClick = onClose, modifier = Modifier.padding(start = spacing.s3, top = spacing.s2)) { Text(stringResource(R.string.onb_back)) }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.s5)
                .padding(bottom = spacing.s5),
        ) {
            Text(stringResource(R.string.security_title), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.security_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.s2),
            )

            if (!pinSet) {
                Button(
                    onClick = { start(Purpose.SETUP) },
                    modifier = Modifier.padding(top = spacing.s5).fillMaxWidth().height(52.dp),
                ) { Text(stringResource(R.string.security_enable_pin)) }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = spacing.s5),
                    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
                ) {
                    Button(onClick = { start(Purpose.CHANGE) }, modifier = Modifier.weight(1f).height(52.dp)) {
                        Text(stringResource(R.string.security_change_pin))
                    }
                    TextButton(onClick = { start(Purpose.DISABLE) }, modifier = Modifier.weight(1f).height(52.dp)) {
                        Text(stringResource(R.string.security_disable_pin))
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = spacing.s5),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.security_biometric), style = MaterialTheme.typography.titleMedium)
                        if (activity != null && !BiometricUnlock.isAvailable(activity)) {
                            Text(
                                stringResource(R.string.security_biometric_unavailable),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Switch(
                        checked = biometricEnabled,
                        enabled = activity != null && BiometricUnlock.isAvailable(activity),
                        onCheckedChange = { enabled ->
                            scope.launch {
                                val result = service.setBiometricEnabled(enabled)
                                if (result == SetBiometricResult.Success) biometricEnabled = enabled
                            }
                        },
                    )
                }

                Text(stringResource(R.string.security_auto_lock), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = spacing.s5))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.s2), modifier = Modifier.padding(top = spacing.s2)) {
                    AutoLockMode.entries.forEach { mode ->
                        FilterChip(
                            selected = autoLock == mode,
                            onClick = { scope.launch { service.setAutoLockMode(mode); autoLock = mode } },
                            label = { Text(autoLockLabel(mode)) },
                            colors = rizqflowFilterChipColors(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun autoLockLabel(mode: AutoLockMode): String = stringResource(
    when (mode) {
        AutoLockMode.IMMEDIATELY -> R.string.security_auto_lock_immediately
        AutoLockMode.AFTER_1_MINUTE -> R.string.security_auto_lock_1min
        AutoLockMode.AFTER_5_MINUTES -> R.string.security_auto_lock_5min
        AutoLockMode.AFTER_15_MINUTES -> R.string.security_auto_lock_15min
    },
)

@Composable
private fun PinWizardScreen(
    wizard: PinWizard,
    digits: String,
    onKey: (String) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    BackHandler(onBack = onCancel)
    LaunchedEffect(digits) {
        if (digits.length == AppLockService.PIN_LENGTH) onSubmit(digits)
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        TextButton(onClick = onCancel, modifier = Modifier.padding(start = spacing.s3, top = spacing.s2)) { Text(stringResource(R.string.catat_date_cancel)) }
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = spacing.s5).padding(bottom = spacing.s5),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Text(wizardTitle(wizard), style = MaterialTheme.typography.headlineSmall)
            PinDots(length = AppLockService.PIN_LENGTH, filled = digits.length, modifier = Modifier.padding(top = spacing.s4))
            Text(
                wizard.error.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = spacing.s2),
            )
            Spacer(Modifier.weight(1f))
            PinKeypad(enabled = true, onKey = onKey, onBackspace = onBackspace)
        }
    }
}

@Composable
private fun wizardTitle(wizard: PinWizard): String = when (wizard.stage) {
    Stage.CURRENT -> stringResource(R.string.security_enter_current_pin)
    Stage.NEW -> stringResource(R.string.security_enter_new_pin)
    Stage.CONFIRM -> stringResource(R.string.security_confirm_new_pin)
}

private val wizardSaver = androidx.compose.runtime.saveable.Saver<PinWizard?, List<String?>>(
    save = { it?.let { w -> listOf(w.purpose.name, w.stage.name, w.newPin, w.error) } ?: emptyList() },
    restore = { list ->
        if (list.isEmpty()) null
        else PinWizard(Purpose.valueOf(list[0]!!), Stage.valueOf(list[1]!!), list.getOrNull(2), list.getOrNull(3))
    },
)
