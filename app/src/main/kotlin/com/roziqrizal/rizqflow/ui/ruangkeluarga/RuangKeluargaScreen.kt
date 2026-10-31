package com.roziqrizal.rizqflow.ui.ruangkeluarga

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.entitlement.Feature
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import com.roziqrizal.rizqflow.domain.sync.RoomInvite
import com.roziqrizal.rizqflow.domain.sync.RoomMemberInfo
import com.roziqrizal.rizqflow.domain.sync.SharedRoomRole
import com.roziqrizal.rizqflow.domain.sync.SyncResult
import com.roziqrizal.rizqflow.domain.sync.ownRole
import com.roziqrizal.rizqflow.domain.sync.removableMembers
import com.roziqrizal.rizqflow.sync.DeviceId
import com.roziqrizal.rizqflow.sync.isUnauthorized
import com.roziqrizal.rizqflow.sync.retryOnceIfRotated
import com.roziqrizal.rizqflow.sync.sessionEndedBy
import com.roziqrizal.rizqflow.ui.Notifier
import com.roziqrizal.rizqflow.ui.paywall.opensSyncPaywall
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
 *
 * Layar ini butuh langganan Sync (Tahap 9, "Penguncian Sync di klien"): tanpa Sync, atau bila server menjawab 403,
 * [onOpenSyncPaywall] dibuka dan layar ditutup lewat [onClose]; tidak ada request yang dijalankan.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuangKeluargaScreen(
    workspace: AccountWorkspace,
    roomId: RoomId,
    notifier: Notifier,
    onClose: () -> Unit,
    onOpenSyncPaywall: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onClose)

    // Paket Sync tidak dimiliki (atau server menolak 403): paywall Sync dibuka, layar ditutup, bukan dibiarkan gagal diam-diam.
    fun leaveForSyncPaywall() {
        onOpenSyncPaywall()
        onClose()
    }

    var roomName by remember { mutableStateOf<String?>(null) }
    var members by remember { mutableStateOf<List<RoomMemberInfo>?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var signedIn by remember { mutableStateOf(true) }
    var sessionEnded by remember { mutableStateOf(false) }
    var inviteSheet by rememberSaveable { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }
    // Keluarkan anggota (Tahap 9, keputusan pemilik #1: hanya pemilik): pilih anggota, cabut keanggotaannya di server,
    // lalu rotasi kunci dan QR ulang untuk anggota yang tetap — lihat RoomMemberKeyRedistribution.
    var removeSheet by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<RoomMemberInfo?>(null) }
    var removing by remember { mutableStateOf(false) }
    var rotationEpoch by remember { mutableIntStateOf(0) }
    var justRotated by remember { mutableStateOf(false) }
    // Peran perangkat ini MENURUT SERVER (bendera owner di daftar anggota), bukan siapa yang membuka layar.
    val isOwner = members?.ownRole() == SharedRoomRole.OWNER
    val removable = members?.removableMembers().orEmpty()

    suspend fun loadMembers() {
        val session = workspace.serverSession
        // 401 padahal token sementara itu diputar (permintaan membawa token lama): diulang sekali dengan token baru,
        // bukan dinyatakan "sesi berakhir" (lihat retryOnceIfRotated). null = tidak ada token (belum masuk).
        val result = session.retryOnceIfRotated(
            isUnauthorized = { it?.isUnauthorized() == true },
            call = {
                val token = session.token()
                if (token == null) {
                    null
                } else {
                    // Idempoten: memastikan diri sendiri terdaftar sebelum menampilkan daftar, supaya anggota
                    // yang baru bergabung lewat QR langsung terlihat tanpa harus menekan Sinkronkan dulu.
                    workspace.roomMembershipRemote.join(token, roomId.value)
                    workspace.roomMembershipRemote.members(token, roomId.value)
                }
            },
        )
        if (result == null) {
            signedIn = false
            return
        }
        signedIn = true
        when (result) {
            is RemoteResult.Success -> {
                members = result.value
                loadFailed = false
                // Penandaan ACTIVE menyalakan sinkron otomatis untuk ruang ini. Perannya diambil dari bendera owner
                // server (pembuat ruang = pemilik, keputusan pemilik #1) dan menimpa peran lokal yang berbeda — dulu
                // siapa pun yang membuka layar ini tercatat OWNER tanpa syarat.
                result.value.ownRole()?.let { workspace.sharedRoomActivation.applyServerRole(roomId, it) }
            }

            // "Sesi berakhir" hanya dinyatakan bila ServerSession benar-benar menyatakannya (token sudah dibersihkan dan
            // "perlu sambungkan ulang" naik); 401 lain yang sesinya masih sah diperlakukan sebagai gagal muat biasa.
            is RemoteResult.Failure -> when {
                result.error.opensSyncPaywall() -> leaveForSyncPaywall()
                session.sessionEndedBy(result) -> {
                    signedIn = false
                    sessionEnded = true
                }

                else -> loadFailed = true
            }
        }
    }

    LaunchedEffect(roomId, reloadKey) {
        // Dicek sebelum request apa pun (join, anggota): tanpa Sync, tidak ada yang dikirim ke server.
        if (!workspace.entitlements.isEnabled(Feature.SHARED_FAMILY_ROOM)) {
            leaveForSyncPaywall()
            return@LaunchedEffect
        }
        roomName = workspace.repositories.rooms.find(roomId)?.name
        workspace.roomKeyProvider.getOrCreateRoomKey(roomId) // kunci wajib ada sebelum QR bisa dirender
        loadMembers()
    }

    fun syncNow() {
        // Dicek sebelum request: paket yang sudah turun di tengah sesi tidak boleh menjalankan sinkron.
        if (!workspace.entitlements.isEnabled(Feature.ENCRYPTED_SYNC)) {
            leaveForSyncPaywall()
            return
        }
        syncing = true
        scope.launch {
            val session = workspace.serverSession
            val deviceId = DeviceId.read(context)
            // Sama seperti loadMembers: 401 karena token baru saja diputar diulang sekali, bukan "sesi berakhir".
            val result = session.retryOnceIfRotated(
                isUnauthorized = { it is SyncResult.Failed && it.error == RemoteError.Unauthorized },
                call = {
                    val token = session.token()
                    if (token == null) {
                        SyncResult.NotSignedIn
                    } else {
                        workspace.roomMembershipRemote.join(token, roomId.value)
                        // Kunci ruang yang sama dengan sinkron otomatis: tidak pernah berjalan bersamaan untuk ruang ini.
                        workspace.syncSharedRoomNow(roomId, deviceId)
                    }
                },
            )
            when (result) {
                is SyncResult.Success -> {
                    notifier.show(resources.getString(R.string.family_sync_result, result.pushed, result.applied))
                    reloadKey++
                }

                is SyncResult.Failed -> when {
                    session.sessionEndedBy(result.error) -> {
                        signedIn = false
                        sessionEnded = true
                    }

                    result.error.opensSyncPaywall() -> leaveForSyncPaywall()
                    else -> notifier.show(resources.getString(R.string.family_sync_failed))
                }

                SyncResult.NotSignedIn -> signedIn = false
            }
            syncing = false
        }
    }

    fun removeMember(member: RoomMemberInfo) {
        val userId = member.userId ?: return
        if (!workspace.entitlements.isEnabled(Feature.SHARED_FAMILY_ROOM)) {
            leaveForSyncPaywall()
            return
        }
        removing = true
        scope.launch {
            val session = workspace.serverSession
            val result = session.retryOnceIfRotated(
                isUnauthorized = { it?.isUnauthorized() == true },
                call = { session.token()?.let { token -> workspace.roomMembershipRemote.remove(token, roomId.value, userId) } },
            )
            when (result) {
                null -> signedIn = false
                is RemoteResult.Success -> {
                    // Keanggotaan sudah dicabut di server; kunci ruang dirotasi dan QR baru disiapkan untuk anggota yang tetap.
                    workspace.memberRemoval.rotateAndPrepareInvite(roomId, roomName.orEmpty())
                    rotationEpoch++ // memaksa QR di bawah dibangun ulang dari kunci yang baru dirotasi
                    justRotated = true
                    inviteSheet = true
                    notifier.show(resources.getString(R.string.family_remove_done, member.name ?: member.email))
                    reloadKey++
                }

                is RemoteResult.Failure -> when {
                    result.error.opensSyncPaywall() -> leaveForSyncPaywall()
                    session.sessionEndedBy(result) -> {
                        signedIn = false
                        sessionEnded = true
                    }

                    else -> {
                        notifier.show(resources.getString(R.string.family_remove_failed))
                        reloadKey++ // daftar mungkin basi (anggota sudah keluar, atau peran berubah)
                    }
                }
            }
            removing = false
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
                    stringResource(if (sessionEnded) R.string.family_session_ended else R.string.family_not_signed_in),
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
                            if (member.owner) {
                                Text(
                                    stringResource(R.string.family_members_owner),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(end = spacing.s2),
                                )
                            }
                            if (member.you) {
                                Text(stringResource(R.string.family_members_you), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider()
                    }
                }

                // Keputusan pemilik #1 (sync-desain.md bagian J): hanya pemilik ruang yang mengundang dan mengeluarkan.
                if (isOwner) {
                    Button(
                        onClick = { inviteSheet = true },
                        modifier = Modifier.padding(top = spacing.s5).fillMaxWidth().height(52.dp),
                    ) { Text(stringResource(R.string.family_invite_action)) }
                }
                OutlinedButton(
                    onClick = ::syncNow,
                    enabled = !syncing,
                    modifier = Modifier.padding(top = spacing.s2).fillMaxWidth().height(52.dp),
                ) { Text(stringResource(R.string.family_sync_action)) }
                // Keluarkan anggota (Tahap 9, server-sync.md "Mengeluarkan anggota"): pilih anggota, cabut
                // keanggotaannya di server (DELETE /rooms/{roomId}/members/{userId}), lalu kunci baru dan QR ulang.
                if (removable.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { removeSheet = true },
                        enabled = !removing,
                        modifier = Modifier.padding(top = spacing.s2).fillMaxWidth().height(52.dp),
                    ) { Text(stringResource(R.string.family_remove_action)) }
                }
            }
        }
    }

    if (removeSheet) {
        ModalBottomSheet(onDismissRequest = { removeSheet = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Text(stringResource(R.string.family_remove_pick_title), style = MaterialTheme.typography.titleLarge)
                removable.forEach { member ->
                    Text(
                        member.name?.let { "$it (${member.email})" } ?: member.email,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                removeSheet = false
                                removeTarget = member
                            }
                            .padding(vertical = 14.dp),
                    )
                    HorizontalDivider()
                }
                TextButton(onClick = { removeSheet = false }, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.catat_date_cancel))
                }
            }
        }
    }

    removeTarget?.let { target ->
        val label = target.name ?: target.email
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(stringResource(R.string.family_remove_confirm_title, label)) },
            text = { Text(stringResource(R.string.family_remove_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    removeTarget = null
                    removeMember(target)
                }) { Text(stringResource(R.string.family_remove_confirm_action)) }
            },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text(stringResource(R.string.catat_date_cancel)) } },
        )
    }

    if (inviteSheet) {
        ModalBottomSheet(onDismissRequest = { inviteSheet = false; justRotated = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.family_invite_sheet_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(if (justRotated) R.string.family_rotate_key_sheet_hint else R.string.family_invite_sheet_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                // rotationEpoch di kunci remember: QR ini membaca kunci ruang TERKINI (exportRoomKey), jadi
                // wajib dibangun ulang setelah rotasi walau roomId/roomName tidak berubah.
                val qrBitmap = remember(roomId, roomName, rotationEpoch) {
                    val invite = RoomInvite(roomId, roomName.orEmpty(), workspace.roomKeyProvider.exportRoomKey(roomId))
                    val payload = Base64.encodeToString(invite.encode(), Base64.NO_WRAP)
                    val pngBytes = QRCode.ofSquares().build(payload).render().getBytes()
                    BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size).asImageBitmap()
                }
                Image(bitmap = qrBitmap, contentDescription = null, modifier = Modifier.padding(top = 16.dp).size(240.dp))
                TextButton(onClick = { inviteSheet = false; justRotated = false }, modifier = Modifier.padding(top = 16.dp)) {
                    Text(stringResource(R.string.catat_date_cancel))
                }
            }
        }
    }
}
