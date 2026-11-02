package com.roziqrizal.rizqflow.ui.ruangkeluarga

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.sync.MemberSecretKdf
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import com.roziqrizal.rizqflow.domain.sync.RoomInvite
import com.roziqrizal.rizqflow.sync.isUnauthorized
import com.roziqrizal.rizqflow.sync.retryOnceIfRotated
import com.roziqrizal.rizqflow.ui.UiMessage
import com.roziqrizal.rizqflow.ui.familyRoomMessage
import com.roziqrizal.rizqflow.workspace.AccountWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import qrcode.QRCode

sealed interface InviteQrResult {
    /** Undangan sudah terdaftar di server dan [bitmap] siap ditampilkan. */
    data class Ready(val bitmap: ImageBitmap) : InviteQrResult

    /** Belum masuk dengan Google: undangan terdaftar butuh token API. */
    data object NotSignedIn : InviteQrResult

    /** Server menolak atau jaringan gagal; [message] sudah dipetakan lewat kode galat, tidak pernah teks mentah server. */
    data class Failed(val message: UiMessage) : InviteQrResult
}

/**
 * Membuat QR undangan ruang keluarga (key_grant langkah 2): satu [RoomInvite] baru dengan `member_secret` segar per
 * tampilan, rahasianya dicatat di [AccountWorkspace.memberSecretStore] (supaya nanti bisa diikat ke anggota yang
 * memakainya), dan hash-nya ([MemberSecretKdf.inviteHash]) didaftarkan ke server SEBELUM QR dirender. Hanya hash yang
 * meninggalkan perangkat; rahasia dan kunci ruang hanya ada di QR. Sekali pakai dan berlaku 7 hari (aturan server).
 *
 * Rahasia dicatat lokal lebih dulu daripada pendaftaran ke server: bila pendaftaran gagal yang tertinggal hanya
 * rahasia yatim yang tak pernah dipakai siapa pun (QR-nya tidak pernah tampil), bukan undangan terdaftar tanpa rahasia.
 */
suspend fun buildInviteQr(workspace: AccountWorkspace, roomId: RoomId, roomName: String): InviteQrResult {
    val session = workspace.serverSession
    if (session.token() == null) return InviteQrResult.NotSignedIn

    val invite = RoomInvite.create(roomId, roomName, workspace.roomKeyProvider.exportRoomKey(roomId))
    val secret = requireNotNull(invite.memberSecret) { "RoomInvite.create selalu mengisi member_secret" }
    val inviteHash = MemberSecretKdf.inviteHash(secret)
    workspace.memberSecretStore.recordIssuedSecret(roomId, secret)

    val result = session.retryOnceIfRotated(
        isUnauthorized = { it?.isUnauthorized() == true },
        call = {
            val token = session.token()
            if (token == null) null else workspace.roomMembershipRemote.registerInvite(token, roomId.value, inviteHash)
        },
    )
    return when (result) {
        null -> InviteQrResult.NotSignedIn
        is RemoteResult.Failure -> InviteQrResult.Failed(result.error.familyRoomMessage(R.string.family_invite_prepare_failed))
        is RemoteResult.Success -> withContext(Dispatchers.Default) {
            val payload = Base64.encodeToString(invite.encode(), Base64.NO_WRAP)
            val pngBytes = QRCode.ofSquares().build(payload).render().getBytes()
            InviteQrResult.Ready(BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size).asImageBitmap())
        }
    }
}
