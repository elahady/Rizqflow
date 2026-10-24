package com.roziqrizal.rizqflow.ui.ruangkeluarga

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import com.roziqrizal.rizqflow.domain.sync.RoomInvite
import com.roziqrizal.rizqflow.domain.sync.RoomMemberInfo
import com.roziqrizal.rizqflow.domain.sync.SyncResult
import com.roziqrizal.rizqflow.sync.DeviceId
import com.roziqrizal.rizqflow.ui.Notifier
import com.roziqrizal.rizqflow.ui.theme.spacing
import com.roziqrizal.rizqflow.workspace.AccountWorkspace
import kotlinx.coroutines.launch
import qrcode.QRCode

/**
 * Kelola ruang keluarga (Tahap 9, roadmap.md "Ruang keluarga bersama"): daftar anggota, undang
 * lewat QR, dan tombol sinkron manual. Membuka layar ini sekaligus memastikan kunci ruang ada dan
 * keanggotaan sendiri terdaftar (idempoten) — tidak ada langkah "mulai berbagi" terpisah, karena
 * membuat kunci/mendaftar diri sendiri tidak membocorkan apa pun (pemilik sudah punya semua
 * datanya). Trigger sync di putaran ini hanya manual, bukan latar belakang (konsisten "tenang,
 * bukan panik").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuangKeluargaScreen(workspace: AccountWorkspace, roomId: RoomId, notifier: Notifier, onClose: () -> Unit) {
    val spacing = MaterialTheme.spacing
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onClose)

    var roomName by remember { mutableStateOf<String?>(null) }
    var members by remember { mutableStateOf<List<RoomMemberInfo>?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var signedIn by remember { mutableStateOf(true) }
    var inviteSheet by rememberSaveable { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }

    suspend fun loadMembers() {
        val token = workspace.apiTokenStore.read()
        if (token == null) {
            signedIn = false
            return
        }
        signedIn = true
        // Idempoten: memastikan diri sendiri terdaftar sebelum menampilkan daftar, supaya anggota
        // yang baru bergabung lewat QR langsung terlihat tanpa harus menekan Sinkronkan dulu.
        workspace.roomMembershipRemote.join(token, roomId.value)
        when (val result = workspace.roomMembershipRemote.members(token, roomId.value)) {
            is RemoteResult.Success -> {
                members = result.value
                loadFailed = false
            }

            is RemoteResult.Failure -> loadFailed = true
        }
    }

    LaunchedEffect(roomId, reloadKey) {
        roomName = workspace.repositories.rooms.find(roomId)?.name
        workspace.roomKeyProvider.getOrCreateRoomKey(roomId) // kunci wajib ada sebelum QR bisa dirender
        loadMembers()
    }

    fun syncNow() {
        syncing = true
        scope.launch {
            val token = workspace.apiTokenStore.read()
            if (token == null) {
                signedIn = false
                syncing = false
                return@launch
            }
            workspace.roomMembershipRemote.join(token, roomId.value)
            when (val result = workspace.sync.syncNow(roomId, DeviceId.read(context))) {
                is SyncResult.Success -> {
                    notifier.show(context.getString(R.string.family_sync_result, result.pushed, result.applied))
                    reloadKey++
                }

                is SyncResult.Failed -> notifier.show(context.getString(R.string.family_sync_failed))
                SyncResult.NotSignedIn -> signedIn = false
            }
            syncing = false
        }
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
            Text(stringResource(R.string.family_title), style = MaterialTheme.typography.headlineMedium)
            roomName?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = spacing.s1))
            }

            if (!signedIn) {
                Text(
                    stringResource(R.string.family_not_signed_in),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.s4),
                )
            } else {
                Text(stringResource(R.string.family_members_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = spacing.s5, bottom = spacing.s2))
                when {
                    loadFailed -> {
                        Text(stringResource(R.string.family_members_error), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { scope.launch { loadMembers() } }, modifier = Modifier.padding(top = spacing.s1)) {
                            Text(stringResource(R.string.family_retry))
                        }
                    }

                    members == null -> CircularProgressIndicator(modifier = Modifier.padding(top = spacing.s2))

                    else -> members!!.forEach { member ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = spacing.s2)) {
                            Text(
                                member.name?.let { "$it (${member.email})" } ?: member.email,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (member.you) {
                                Text(stringResource(R.string.family_members_you), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider()
                    }
                }

                Button(
                    onClick = { inviteSheet = true },
                    modifier = Modifier.padding(top = spacing.s5).fillMaxWidth().height(52.dp),
                ) { Text(stringResource(R.string.family_invite_action)) }
                OutlinedButton(
                    onClick = ::syncNow,
                    enabled = !syncing,
                    modifier = Modifier.padding(top = spacing.s2).fillMaxWidth().height(52.dp),
                ) { Text(stringResource(R.string.family_sync_action)) }
            }
        }
    }

    if (inviteSheet) {
        ModalBottomSheet(onDismissRequest = { inviteSheet = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.family_invite_sheet_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.family_invite_sheet_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                val qrBitmap = remember(roomId, roomName) {
                    val invite = RoomInvite(roomId, roomName.orEmpty(), workspace.roomKeyProvider.exportRoomKey(roomId))
                    val payload = Base64.encodeToString(invite.encode(), Base64.NO_WRAP)
                    val pngBytes = QRCode.ofSquares().build(payload).render().getBytes()
                    BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size).asImageBitmap()
                }
                Image(bitmap = qrBitmap, contentDescription = null, modifier = Modifier.padding(top = 16.dp).size(240.dp))
                TextButton(onClick = { inviteSheet = false }, modifier = Modifier.padding(top = 16.dp)) {
                    Text(stringResource(R.string.catat_date_cancel))
                }
            }
        }
    }
}
