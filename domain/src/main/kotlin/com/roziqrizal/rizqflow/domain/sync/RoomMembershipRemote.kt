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
}

data class RoomMemberInfo(val name: String?, val email: String, val you: Boolean)
