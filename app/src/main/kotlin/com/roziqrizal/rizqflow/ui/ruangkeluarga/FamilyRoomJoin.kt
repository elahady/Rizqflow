package com.roziqrizal.rizqflow.ui.ruangkeluarga

import com.roziqrizal.rizqflow.domain.ledger.Category
import com.roziqrizal.rizqflow.domain.ledger.Room
import com.roziqrizal.rizqflow.domain.ledger.RoomTemplates
import com.roziqrizal.rizqflow.domain.model.CategoryId
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.model.RoomKind
import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import com.roziqrizal.rizqflow.domain.sync.RoomInvite
import com.roziqrizal.rizqflow.sync.isUnauthorized
import com.roziqrizal.rizqflow.sync.retryOnceIfRotated
import com.roziqrizal.rizqflow.workspace.AccountWorkspace
import java.util.UUID

sealed interface FamilyJoinResult {
    data class Success(val roomId: RoomId) : FamilyJoinResult

    /** Belum masuk dengan Google atau belum punya langganan Sync — join butuh token API. */
    data object NotSignedIn : FamilyJoinResult

    data class Failed(val error: RemoteError) : FamilyJoinResult
}

/**
 * Bergabung ke ruang keluarga dari hasil pindai QR (Tahap 9, roadmap.md "Ruang keluarga bersama").
 * Kalau ruang dengan [RoomInvite.roomId] belum ada di perangkat ini (kasus umum: anggota baru),
 * dibuat dengan id dan nama yang sama persis (bukan `RuleService.addRoom`, yang selalu membuat id
 * acak dan menolak nama ganda/batas ruang — bergabung ke ruang yang sudah ada bukan "menambah ruang
 * baru"). Kategori awal selalu Lain-lain dan Tak terlacak; kategori asli ruang itu tidak ikut
 * tersinkron (Tahap 9 hanya menyinkronkan transaksi), jadi kategori yang belum ada di sini jatuh ke
 * Lain-lain saat transaksi pertama di-pull (lihat `SyncEngine`). Aman dipanggil berulang (idempoten)
 * untuk ruang yang sama — dipakai juga oleh tombol "Sinkronkan sekarang" untuk memastikan
 * keanggotaan tetap terdaftar bila pemanggilan pertama sempat gagal karena offline.
 */
suspend fun joinSharedRoom(workspace: AccountWorkspace, invite: RoomInvite): FamilyJoinResult {
    val session = workspace.serverSession
    if (session.token() == null) return FamilyJoinResult.NotSignedIn

    if (workspace.repositories.rooms.find(invite.roomId) == null) {
        val active = workspace.repositories.rooms.activeRooms()
        val room = Room(
            id = invite.roomId,
            name = invite.roomName.ifBlank { "Ruang Keluarga" },
            kind = RoomKind.MENCUKUPI,
            iconKey = "home",
            colorSlot = FAMILY_ROOM_COLOR_SLOT,
            sortOrder = (active.maxOfOrNull { it.sortOrder } ?: -1) + 1,
        )
        val other = Category(CategoryId(UUID.randomUUID().toString()), room.id, "Lain-lain", sortOrder = 0)
        val untracked = Category(CategoryId(UUID.randomUUID().toString()), room.id, RoomTemplates.UNTRACKED, isSystem = true, sortOrder = 1)
        workspace.repositories.rooms.addRoom(room, listOf(other, untracked))
    }

    workspace.roomKeyProvider.importRoomKey(invite.roomId, invite.exportedRoomKey)

    // Token dibaca ulang di dalam panggilan: 401 karena token baru saja diputar (permintaan membawa token lama)
    // diulang sekali dengan token baru, bukan dilaporkan sebagai kegagalan (lihat retryOnceIfRotated).
    val result = session.retryOnceIfRotated(
        isUnauthorized = { it?.isUnauthorized() == true },
        call = {
            val token = session.token()
            if (token == null) null else workspace.roomMembershipRemote.join(token, invite.roomId.value)
        },
    )
    return when (result) {
        null -> FamilyJoinResult.NotSignedIn
        is RemoteResult.Success -> FamilyJoinResult.Success(invite.roomId)
        is RemoteResult.Failure -> FamilyJoinResult.Failed(result.error)
    }
}

/** Slot warna netral (lihat `Color.kt`, slot 1-3 dipakai pola Tiga hak) — tidak perlu mencari slot bebas untuk satu ruang. */
private const val FAMILY_ROOM_COLOR_SLOT = 4
