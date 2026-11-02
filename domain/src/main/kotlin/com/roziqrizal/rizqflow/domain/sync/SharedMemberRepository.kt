package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.model.RoomId

/**
 * Satu anggota ruang bersama di cache lokal (tabel `shared_member`, peran-anggota.md E.2/H langkah 8): proyeksi
 * dari [RoomMemberInfo] terakhir yang berhasil dibalas [RoomMembershipRemote.members] — dipakai [EnvelopeSyncHandler]
 * menegakkan D.4 ("hanya Pemilik menulis amplop") dan UI (sheet "Atur amplop", bagian Amplop di `RuangKeluargaScreen`)
 * untuk label nama tanpa menunggu jaringan lagi.
 *
 * [status] REMOVED dipakai untuk anggota yang pernah tercache tapi sudah tidak muncul di jawaban `members()`
 * terakhir (dikeluarkan ATAU keluar sendiri — API `members()` tidak membedakan keduanya, jadi cache ini pun tidak;
 * usulan Claude, belum dikonfirmasi pemilik). Baris tidak pernah dihapus fisik supaya label/peran historis anggota
 * yang sudah pergi tetap bisa dibaca untuk amplop/terpakai bulan-bulan sebelumnya.
 */
data class SharedMemberCache(
    val memberId: String,
    val label: String,
    val role: SharedRoomRole,
    val status: SharedRoomStatus,
    val isMe: Boolean,
    val updatedAtMillis: Long,
)

/** Penyimpanan cache [SharedMemberCache] (tabel `shared_member`). Implementasi: `LocalFamilyRepository` di `:data`. */
interface SharedMemberRepository {
    /**
     * Mengganti cache anggota [roomId] dengan [members] (atomik): setiap anggota di [members] di-upsert
     * `status = ACTIVE`; baris lama di cache yang TIDAK ada di [members] ditandai `status = REMOVED` (tidak
     * dihapus). Dipanggil setiap [RoomMembershipRemote.members] sukses — lihat `RuangKeluargaScreen.loadMembers`.
     */
    suspend fun replaceAll(roomId: RoomId, members: List<SharedMemberCache>)

    suspend fun find(roomId: RoomId, memberId: String): SharedMemberCache?

    suspend fun all(roomId: RoomId): List<SharedMemberCache>
}
