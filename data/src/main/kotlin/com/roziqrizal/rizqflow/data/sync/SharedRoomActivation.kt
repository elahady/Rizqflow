package com.roziqrizal.rizqflow.data.sync

import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.sync.SharedRoomInfo
import com.roziqrizal.rizqflow.domain.sync.SharedRoomRole
import com.roziqrizal.rizqflow.domain.sync.SharedRoomStatus
import com.roziqrizal.rizqflow.domain.sync.SyncStore

/**
 * Menandai ruang sebagai ruang bersama ACTIVE (Tahap 9, roadmap "Sync berjalan otomatis"). Dipanggil setelah
 * keanggotaan di server berhasil didaftarkan, di dua tempat: penerima undangan (`joinSharedRoom`, peran MEMBER) dan
 * pemilik yang membuka layar Ruang Keluarga (peran OWNER, bila belum ada baris `shared_room` untuk ruang itu).
 *
 * Tanpa penandaan ini, [SharedRoomAutoSync] tidak pernah menganggap ruang sebagai ruang bersama dan kedua pemicu
 * otomatis tidak berjalan di aplikasi nyata.
 *
 * Aman dipanggil berulang (idempoten): peran dan waktu bergabung yang sudah tersimpan dipertahankan, hanya status
 * yang diset ke ACTIVE. Ini juga memulihkan ruang yang sebelumnya berstatus REMOVED/LEFT ketika server kembali
 * menerima pendaftaran anggota.
 */
class SharedRoomActivation(private val syncStore: SyncStore) {

    suspend fun markActive(roomId: RoomId, role: SharedRoomRole, nowMillis: Long = System.currentTimeMillis()) {
        val existing = syncStore.sharedRoom(roomId)
        syncStore.saveSharedRoom(
            SharedRoomInfo(
                roomId = roomId,
                role = existing?.role ?: role,
                status = SharedRoomStatus.ACTIVE,
                joinedAtMillis = existing?.joinedAtMillis ?: nowMillis,
            ),
        )
    }
}
