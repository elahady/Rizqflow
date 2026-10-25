package com.roziqrizal.rizqflow.ui.recovery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.RizqflowApplication
import com.roziqrizal.rizqflow.domain.sync.EnableRecoveryResult
import com.roziqrizal.rizqflow.domain.sync.RecoveryChallenge
import com.roziqrizal.rizqflow.domain.sync.RecoveryCodeDisplay
import com.roziqrizal.rizqflow.domain.sync.RecoveryCopyState
import com.roziqrizal.rizqflow.domain.sync.RecoveryStatus
import com.roziqrizal.rizqflow.sync.confirmOffMain
import com.roziqrizal.rizqflow.sync.enableRecoveryRetrying
import com.roziqrizal.rizqflow.sync.sessionEndedBy
import com.roziqrizal.rizqflow.ui.RizqflowIcons
import com.roziqrizal.rizqflow.ui.theme.spacing
import com.roziqrizal.rizqflow.workspace.AccountWorkspace
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

private enum class SetupPhase { Intro, Working, Showing, Verify, Done }

/**
 * Atur kode pemulihan 24 kata (Tahap 13): layar tampil-sekali. Alurnya: Intro (status) -> Working (membungkus kunci
 * akun dan UNGGAH ke server) -> Showing (24 kata bernomor, hanya setelah unggahan sukses) -> Verify (ketik ulang
 * tiga kata acak) -> Done. Penanda "sudah diatur" baru ditulis setelah Verify lulus ([RecoveryKeyService.confirm]);
 * menutup layar sebelum itu membiarkan status "belum selesai" dan kode tidak bisa ditampilkan lagi.
 *
 * KODE TIDAK PERNAH DISIMPAN: kata hanya di [RecoveryCodeDisplay] yang dipegang [RizqflowApplication.recoveryCodeHolder]
 * (memori proses, supaya memutar layar tidak membuang kode di tengah mencatat), TIDAK lewat `rememberSaveable`, tidak
 * di SharedPreferences, tidak di log. Ketikan verifikasi juga hanya `remember`.
 *
 * FLAG_SECURE sengaja TIDAK dipasang: dihapus dari seluruh aplikasi atas keputusan pemilik 2026-10-24 (lihat catatan
 * Tahap 6 di docs/roadmap.md). Karena itu layar ini memperingatkan pengguna dengan kalimat terang agar tidak screenshot.
 * Bila keputusan itu ditinjau ulang, layar ini kandidat pertama untuk mengaktifkannya kembali.
 */
@Composable
fun RecoverySetupScreen(
    workspace: AccountWorkspace,
    accountId: String,
    onOpenRestore: () -> Unit,
    onClose: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val holder = (context.applicationContext as RizqflowApplication).recoveryCodeHolder
    val service = workspace.recovery

    // Dilanjutkan bila kode masih ditahan (layar diputar); selain itu mulai dari Intro.
    var display by remember { mutableStateOf(holder.peek(accountId)) }
    var phase by remember { mutableStateOf(if (display != null) SetupPhase.Showing else SetupPhase.Intro) }
    var status by remember { mutableStateOf(service.status()) }
    var challenge by remember { mutableStateOf<RecoveryChallenge?>(null) }
    val answers = remember { mutableStateMapOf<Int, String>() }
    var verifyWrong by remember { mutableStateOf(false) }
    var errorRes by remember { mutableStateOf<Int?>(null) }
    var confirmRecreate by remember { mutableStateOf(false) }
    var existingCopyDialog by remember { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf(false) }
    var copyState by remember { mutableStateOf(RecoveryCopyState.UNKNOWN) }
    var confirmExit by remember { mutableStateOf(false) }

    // Kata tidak boleh bertahan setelah layar ini hilang (ditutup, atau komposisi dibuang saat keluar akun).
    // Memutar layar juga membuang komposisi ini, tetapi holder dikosongkan HANYA lewat jalur eksplisit [leave] dan
    // verifikasi lulus, bukan di sini, supaya rotasi tidak membuang kode.
    fun leave() {
        holder.clear()
        display = null
        answers.clear()
        onClose()
    }

    fun startVerify() {
        val current = display ?: return
        challenge = current.newChallenge()
        answers.clear()
        verifyWrong = false
        phase = SetupPhase.Verify
    }

    // Penanda yang sudah terkonfigurasi dibandingkan dengan salinan di server tiap layar dibuka (dan tiap status berubah):
    // `updated_at` berbeda berarti salinan diganti dari perangkat lain dan kode di kertas mungkin tidak berlaku lagi.
    // Gagal diam (UNKNOWN): tidak ada kabar, tidak ada kepanikan.
    LaunchedEffect(status) {
        copyState = RecoveryCopyState.UNKNOWN
        if (status is RecoveryStatus.Configured) copyState = service.compareWithServer()
    }

    fun createCode(replaceExisting: Boolean) {
        // Dua klik cepat tidak boleh meluncurkan dua unggahan.
        if (phase == SetupPhase.Working) return
        errorRes = null
        phase = SetupPhase.Working
        scope.launch {
            val result = service.enableRecoveryRetrying(workspace.serverSession, replaceExistingCopy = replaceExisting)
            status = service.status()
            when (result) {
                is EnableRecoveryResult.Ready -> {
                    holder.hold(accountId, result.display)
                    display = result.display
                    phase = SetupPhase.Showing
                }

                EnableRecoveryResult.ExistingCopyOnServer -> {
                    phase = SetupPhase.Intro
                    existingCopyDialog = true
                }

                else -> {
                    phase = SetupPhase.Intro
                    val ended = (result as? EnableRecoveryResult.Failed)?.let { workspace.serverSession.sessionEndedBy(it.error) } ?: false
                    errorRes = enableFailureMessage(result, sessionEnded = ended)
                }
            }
        }
    }

    // Menutup layar lewat Kembali: saat kode sudah tampil tapi belum terkonfirmasi, tanya dulu (kode tidak akan tampil lagi).
    val midFlow = phase == SetupPhase.Showing || phase == SetupPhase.Verify
    // Selalu aktif: Back selama Working DIABAIKAN (bukan menutup layar); bila dinonaktifkan, Back menutup Activity di
    // tengah unggahan dan hasilnya hilang. Pencatatan hasil tetap aman (NonCancellable di service), tetapi layarnya jangan ditutup.
    BackHandler {
        when {
            phase == SetupPhase.Working -> Unit
            midFlow -> confirmExit = true
            else -> leave()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        TextButton(
            onClick = { if (midFlow) confirmExit = true else leave() },
            enabled = phase != SetupPhase.Working,
            modifier = Modifier.padding(start = spacing.s3, top = spacing.s2),
        ) { Text(stringResource(R.string.onb_back)) }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.s5)
                .padding(bottom = spacing.s5),
        ) {
            when (phase) {
                SetupPhase.Intro, SetupPhase.Working -> IntroContent(
                    status = status,
                    working = phase == SetupPhase.Working,
                    errorRes = errorRes,
                    copyStateRes = copyStateMessage(copyState),
                    onCreate = { createCode(replaceExisting = false) },
                    onRecreate = { confirmRecreate = true },
                    onRetryPending = { createCode(replaceExisting = false) },
                )

                SetupPhase.Showing -> ShowingContent(display = display, onDone = ::startVerify)

                SetupPhase.Verify -> VerifyContent(
                    positions = challenge?.positions.orEmpty(),
                    answers = answers,
                    wrong = verifyWrong,
                    onBack = { phase = SetupPhase.Showing },
                    onSubmit = {
                        val current = challenge
                        val typed = answers.toMap()
                        // Menulis penanda memakai commit() sinkron: di luar thread utama.
                        scope.launch {
                            if (current != null && service.confirmOffMain(current, typed)) {
                                holder.clear()
                                display = null
                                answers.clear()
                                status = service.status()
                                phase = SetupPhase.Done
                            } else {
                                // Undian baru tiap gagal: nomor tidak bisa ditebak dengan mengintip berulang.
                                verifyWrong = true
                                display?.let { challenge = it.newChallenge() }
                                answers.clear()
                            }
                        }
                    },
                )

                SetupPhase.Done -> DoneContent(onClose = ::leave)
            }
        }
    }

    if (confirmRecreate) {
        AlertDialog(
            onDismissRequest = { confirmRecreate = false },
            title = { Text(stringResource(R.string.recovery_recreate_confirm_title)) },
            text = { Text(stringResource(R.string.recovery_recreate_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRecreate = false
                    createCode(replaceExisting = true)
                }) { Text(stringResource(R.string.recovery_action_recreate)) }
            },
            dismissButton = { TextButton(onClick = { confirmRecreate = false }) { Text(stringResource(R.string.catat_date_cancel)) } },
        )
    }
    if (existingCopyDialog) {
        AlertDialog(
            onDismissRequest = { existingCopyDialog = false },
            title = { Text(stringResource(R.string.recovery_existing_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.recovery_existing_body))
                    // Pilihan yang menimpa salinan sengaja BUKAN tombol sejajar dengan yang aman: tautan kecil,
                    // dan masih butuh konfirmasi tegas kedua.
                    TextButton(
                        onClick = {
                            existingCopyDialog = false
                            confirmReplace = true
                        },
                        modifier = Modifier.padding(top = spacing.s2),
                    ) { Text(stringResource(R.string.recovery_existing_replace), style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    existingCopyDialog = false
                    onOpenRestore()
                }) { Text(stringResource(R.string.recovery_existing_restore)) }
            },
            dismissButton = { TextButton(onClick = { existingCopyDialog = false }) { Text(stringResource(R.string.recovery_existing_cancel)) } },
        )
    }
    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text(stringResource(R.string.recovery_replace_confirm_title)) },
            text = { Text(stringResource(R.string.recovery_replace_confirm_body)) },
            // Tombol aman (Kembali) yang menonjol; tombol yang menimpa hanya teks.
            confirmButton = { Button(onClick = { confirmReplace = false }) { Text(stringResource(R.string.recovery_replace_confirm_back)) } },
            dismissButton = {
                TextButton(onClick = {
                    confirmReplace = false
                    createCode(replaceExisting = true)
                }) { Text(stringResource(R.string.recovery_replace_confirm_action)) }
            },
        )
    }
    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text(stringResource(R.string.recovery_exit_title)) },
            text = { Text(stringResource(R.string.recovery_exit_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmExit = false
                    leave()
                }) { Text(stringResource(R.string.recovery_exit_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text(stringResource(R.string.recovery_exit_stay)) } },
        )
    }
}

@Composable
private fun IntroContent(
    status: RecoveryStatus,
    working: Boolean,
    errorRes: Int?,
    copyStateRes: Int?,
    onCreate: () -> Unit,
    onRecreate: () -> Unit,
    onRetryPending: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    Text(stringResource(R.string.recovery_title), style = MaterialTheme.typography.headlineMedium)
    Text(
        stringResource(R.string.recovery_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = spacing.s3),
    )
    // Status: ikon plus teks, tidak hanya warna.
    Row(modifier = Modifier.padding(top = spacing.s4), verticalAlignment = Alignment.CenterVertically) {
        val done = status is RecoveryStatus.Configured
        Icon(if (done) RizqflowIcons.Centang else RizqflowIcons.Lingkaran, contentDescription = null, modifier = Modifier.size(20.dp))
        Text(
            text = when (status) {
                RecoveryStatus.NotConfigured -> stringResource(R.string.recovery_status_none)
                is RecoveryStatus.PendingConfirmation -> stringResource(R.string.recovery_status_pending)
                is RecoveryStatus.Configured -> stringResource(R.string.recovery_status_configured, formatConfiguredAt(status.at))
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = spacing.s2),
        )
    }
    if (errorRes != null) {
        Text(
            stringResource(errorRes),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = spacing.s3).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    if (copyStateRes != null) {
        // Kabar tenang, bukan alarm: ikon plus teks, diumumkan sopan oleh pembaca layar.
        Row(
            modifier = Modifier.padding(top = spacing.s3).semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(RizqflowIcons.Peringatan, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stringResource(copyStateRes), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = spacing.s2))
        }
    }
    if (status is RecoveryStatus.Configured) {
        Text(
            stringResource(R.string.recovery_recreate_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.s3),
        )
    }
    if (working) {
        Row(modifier = Modifier.padding(top = spacing.s5), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.recovery_working), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = spacing.s3))
        }
    } else {
        when (status) {
            RecoveryStatus.NotConfigured -> Button(
                onClick = onCreate,
                modifier = Modifier.padding(top = spacing.s5).fillMaxWidth().height(52.dp),
            ) { Text(stringResource(R.string.recovery_action_create)) }

            is RecoveryStatus.PendingConfirmation -> Button(
                onClick = onRetryPending,
                modifier = Modifier.padding(top = spacing.s5).fillMaxWidth().height(52.dp),
            ) { Text(stringResource(R.string.recovery_action_recreate)) }

            is RecoveryStatus.Configured -> if (copyStateRes != null) {
                // Salinan diganti atau hilang: membuat kode baru menjadi tindakan utama.
                Button(
                    onClick = onRecreate,
                    modifier = Modifier.padding(top = spacing.s5).fillMaxWidth().height(52.dp),
                ) { Text(stringResource(R.string.recovery_action_recreate)) }
            } else {
                OutlinedButton(
                    onClick = onRecreate,
                    modifier = Modifier.padding(top = spacing.s5).fillMaxWidth().height(52.dp),
                ) { Text(stringResource(R.string.recovery_action_recreate)) }
            }
        }
    }
}

@Composable
private fun ShowingContent(display: RecoveryCodeDisplay?, onDone: () -> Unit) {
    val spacing = MaterialTheme.spacing
    Text(stringResource(R.string.recovery_show_title), style = MaterialTheme.typography.headlineMedium)
    // Peringatan jelas, bukan hanya warna: ikon peringatan plus kalimat terang. Tanpa FLAG_SECURE (keputusan pemilik 2026-10-24).
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.padding(top = spacing.s4).fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(spacing.s4)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(RizqflowIcons.Peringatan, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(
                    stringResource(R.string.recovery_show_warn_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = spacing.s2),
                )
            }
            for (line in listOf(
                R.string.recovery_show_warn_paper,
                R.string.recovery_show_warn_no_copy,
                R.string.recovery_show_warn_no_help,
                R.string.recovery_show_warn_once,
            )) {
                Text(stringResource(line), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = spacing.s2))
            }
        }
    }
    val words = display?.words.orEmpty()
    // Dua kolom, nomor 1-12 di kiri dan 13-24 di kanan, supaya mudah dibaca dan disalin ke kertas berurutan.
    Row(modifier = Modifier.padding(top = spacing.s5).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing.s4)) {
        for (column in 0..1) {
            Column(modifier = Modifier.weight(1f)) {
                for (row in 0 until 12) {
                    val index = column * 12 + row
                    if (index < words.size) {
                        Text(
                            "${index + 1}. ${words[index]}",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(vertical = spacing.s1),
                        )
                    }
                }
            }
        }
    }
    Button(
        onClick = onDone,
        enabled = words.isNotEmpty(),
        modifier = Modifier.padding(top = spacing.s5).fillMaxWidth().height(52.dp),
    ) { Text(stringResource(R.string.recovery_show_done)) }
}

@Composable
private fun VerifyContent(
    positions: List<Int>,
    answers: MutableMap<Int, String>,
    wrong: Boolean,
    onBack: () -> Unit,
    onSubmit: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    Text(stringResource(R.string.recovery_verify_title), style = MaterialTheme.typography.headlineMedium)
    Text(
        stringResource(R.string.recovery_verify_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = spacing.s3),
    )
    if (wrong) {
        Row(
            modifier = Modifier.padding(top = spacing.s3).semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(RizqflowIcons.Peringatan, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.recovery_verify_wrong), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = spacing.s2))
        }
    }
    positions.forEachIndexed { i, position ->
        OutlinedTextField(
            value = answers[position].orEmpty(),
            onValueChange = { answers[position] = it },
            label = { Text(stringResource(R.string.recovery_verify_field, position)) },
            singleLine = true,
            // Jenis papan ketik "sandi" meminta papan ketik tidak menyarankan atau mempelajari isiannya; tetap terlihat.
            // Belum diverifikasi di perangkat: perilaku tiap papan ketik berbeda.
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password,
                imeAction = if (i == positions.lastIndex) ImeAction.Done else ImeAction.Next,
            ),
            modifier = Modifier.padding(top = spacing.s3).fillMaxWidth(),
        )
    }
    Button(
        onClick = onSubmit,
        enabled = positions.isNotEmpty() && positions.all { !answers[it].isNullOrBlank() },
        modifier = Modifier.padding(top = spacing.s5).fillMaxWidth().height(52.dp),
    ) { Text(stringResource(R.string.recovery_verify_action)) }
    TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.recovery_verify_back)) }
}

@Composable
private fun DoneContent(onClose: () -> Unit) {
    val spacing = MaterialTheme.spacing
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(RizqflowIcons.Centang, contentDescription = null, modifier = Modifier.size(28.dp))
        Text(
            stringResource(R.string.recovery_done_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = spacing.s3),
        )
    }
    Text(
        stringResource(R.string.recovery_done_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = spacing.s3),
    )
    Button(onClick = onClose, modifier = Modifier.padding(top = spacing.s5).fillMaxWidth().height(52.dp)) {
        Text(stringResource(R.string.recovery_done_action))
    }
}

/** Tanggal pengaturan kode dalam format medium sesuai bahasa perangkat (zona waktu perangkat). */
internal fun formatConfiguredAt(at: Instant): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(at)
