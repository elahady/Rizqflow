package com.roziqrizal.rizqflow.domain.sync

/**
 * Keanggotaan ruang yang dibagi (Tahap 9, roadmap.md "Ruang keluarga bersama"). Kontrak murni
 * tanpa Retrofit/OkHttp — implementasi sungguhan (`RetrofitRoomMembershipRemote`) ada di `:data`.
 * [join] dipanggil segera setelah memindai undangan QR, sebelum [SyncEngine.syncNow] pertama kali,
 * supaya pull pertama anggota baru itu sudah termasuk baris ruang itu. [members] menampilkan siapa
 * saja anggotanya — server menolak (lihat [RemoteError]) bila pemanggil sendiri belum jadi anggota.
 */
interface RoomMembershipRemote {
    suspend fun join(apiToken: String, roomId: String): RemoteResult<Unit>

    suspend fun members(apiToken: String, roomId: String): RemoteResult<List<RoomMemberInfo>>

    /**
     * Mengeluarkan [userId] dari ruang (`DELETE /rooms/{roomId}/members/{userId}`). Hanya pemilik yang boleh
     * (keputusan pemilik #1, sync-desain.md bagian J); bukan pemilik dijawab [RemoteError.Rejected], bukan paywall.
     * Hanya mencabut keanggotaan di server: kunci ruang baru tetap dibagikan lewat [RoomMemberKeyRedistribution].
     */
    suspend fun remove(apiToken: String, roomId: String, userId: Long): RemoteResult<Unit>

    /**
     * Anggota keluar sendiri (`POST /rooms/{roomId}/leave`, 204; tanpa syarat Sync di server). Pemilik ditolak:
     * [RemoteError.Rejected] berkode [ServerErrorCodes.OWNER_CANNOT_LEAVE]; bukan anggota (juga ulangan setelah berhasil):
     * [RemoteError.Rejected] berkode [ServerErrorCodes.NOT_ROOM_MEMBER]. Hanya mencabut keanggotaan di server; urusan
     * lokal (menandai ruang tidak aktif) ada di [SharedRoomLeaver].
     */
    suspend fun leave(apiToken: String, roomId: String): RemoteResult<Unit>

    /**
     * Memindahkan kepemilikan ruang ke [targetUserId] (`POST /rooms/{roomId}/members/{userId}/make-owner`, 204). Hanya
     * pemilik saat ini yang boleh: bukan pemilik dijawab [RemoteError.Rejected] berkode [ServerErrorCodes.NOT_ROOM_OWNER]
     * (bukan paywall); target bukan anggota: [ServerErrorCodes.MEMBER_NOT_FOUND]; target sudah pemilik:
     * [ServerErrorCodes.ALREADY_OWNER]. Tidak mengeluarkan siapa pun dan tidak butuh rotasi kunci: pemilik lama tetap
     * anggota dan boleh [leave] setelah ini. Tidak ada cache lokal yang perlu diubah di sini (peran lokal dibaca ulang dari
     * daftar anggota oleh pemanggil).
     */
    suspend fun transferOwnership(apiToken: String, roomId: String, targetUserId: Long): RemoteResult<Unit>
}

/**
 * Satu anggota ruang menurut server. [owner] dan [userId] datang dari `GET /rooms/{roomId}/members`; server lama
 * yang belum mengirimnya terbaca sebagai `false`/null (tidak pernah dianggap pemilik, tidak bisa dikeluarkan).
 * [memberId] (sejak 2026-11-02) sama dengan `author` baris sync anggota ini (peran-anggota.md E.1) — dipakai
 * mencocokkan nama anggota ke baris [com.roziqrizal.rizqflow.domain.ledger.MemberSpend]; null dari server lama.
 */
data class RoomMemberInfo(
    val name: String?,
    val email: String,
    val you: Boolean,
    val owner: Boolean = false,
    val userId: Long? = null,
    val memberId: String? = null,
)

/**
 * Peran perangkat ini di ruang menurut server (sumber kebenaran peran, bukan siapa yang membuka layar): null bila
 * daftar tidak memuat diri sendiri (bukan anggota, atau jawaban server lama).
 */
fun List<RoomMemberInfo>.ownRole(): SharedRoomRole? =
    firstOrNull { it.you }?.let { if (it.owner) SharedRoomRole.OWNER else SharedRoomRole.MEMBER }

/**
 * Anggota yang boleh dikeluarkan oleh perangkat ini: kosong bila diri sendiri bukan pemilik (keputusan pemilik #1:
 * hanya pemilik yang mengeluarkan). Pemilik sendiri dan anggota tanpa [RoomMemberInfo.userId] tidak pernah ikut.
 */
fun List<RoomMemberInfo>.removableMembers(): List<RoomMemberInfo> =
    if (!RoomPermissions.canRemoveMembers(ownRole())) emptyList() else filter { !it.you && !it.owner && it.userId != null }
