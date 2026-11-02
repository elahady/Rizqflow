package com.roziqrizal.rizqflow.domain.sync

/**
 * Hak peran di ruang bersama, rilis 1 (peran-anggota.md bagian C): hanya [SharedRoomRole.OWNER] dan
 * [SharedRoomRole.MEMBER]. Peran sesudah rilis (Pengelola, Pemberi jatah, Anak) sengaja belum ada di sini; tambahkan
 * cabangnya bersama rekaman `member_role` (langkah 12 peran-anggota.md bagian H), bukan sebelumnya.
 *
 * Ini jawaban untuk TAMPILAN saja (sembunyikan tombol, pilih label). Server tetap menegakkan ulang setiap aksi
 * (`not_room_owner`, `owner_cannot_leave`, dan sebagainya); aturan yang hanya ditegakkan klien bukan jaminan
 * kriptografis. [role] null berarti perangkat ini bukan anggota ruang itu (lihat [ownRole]); semua hak ditolak.
 */
object RoomPermissions {
    /** Hanya pemilik yang mengundang anggota (membuat QR undangan). */
    fun canInvite(role: SharedRoomRole?): Boolean = role == SharedRoomRole.OWNER

    /** Hanya pemilik yang mengeluarkan anggota (dengan rotasi kunci ruang). */
    fun canRemoveMembers(role: SharedRoomRole?): Boolean = role == SharedRoomRole.OWNER

    /** Hanya pemilik yang memindahkan kepemilikan ke anggota lain. */
    fun canTransferOwnership(role: SharedRoomRole?): Boolean = role == SharedRoomRole.OWNER

    /**
     * Hanya anggota biasa yang boleh keluar sendiri. Pemilik ditolak server dengan `owner_cannot_leave`; pemilik harus
     * memindahkan kepemilikan dulu (lihat [canTransferOwnership]).
     */
    fun canLeave(role: SharedRoomRole?): Boolean = role == SharedRoomRole.MEMBER

    /** Hanya pemilik yang mengatur amplop bulanan (peran-anggota.md D.4, C): server tidak bisa menegakkan ini
     * (`record_type` disamarkan); klien penerima mengabaikan amplop dari penulis yang bukan pemilik — lihat
     * [com.roziqrizal.rizqflow.domain.sync.EnvelopeSyncHandler.apply].
     */
    fun canManageEnvelope(role: SharedRoomRole?): Boolean = role == SharedRoomRole.OWNER
}
