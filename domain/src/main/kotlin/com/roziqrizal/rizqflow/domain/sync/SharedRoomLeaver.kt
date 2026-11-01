package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.model.RoomId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Hasil [SharedRoomLeaver.leave]. */
sealed interface LeaveRoomOutcome {
    /** Server mencabut keanggotaan dan ruang sudah ditandai tidak aktif di perangkat ini. */
    data object Left : LeaveRoomOutcome

    /**
     * Server menjawab "bukan anggota" (`not_room_member`): ulangan setelah berhasil keluar yang jawabannya hilang di
     * jaringan, atau sudah dikeluarkan pemilik. Hasil akhirnya sama dengan [Left] bagi perangkat ini, jadi ruang tetap
     * ditandai tidak aktif.
     */
    data object NoLongerMember : LeaveRoomOutcome

    /** Pemilik tidak bisa keluar (server menolak `owner_cannot_leave`; belum ada pindah kepemilikan). Tidak ada yang berubah. */
    data object OwnerCannotLeave : LeaveRoomOutcome

    /** Gagal karena sebab lain (jaringan, sesi, batas laju, galat server). Tidak ada yang berubah di perangkat ini. */
    data class Failed(val error: RemoteError) : LeaveRoomOutcome
}

/**
 * Keluar dari ruang bersama sebagai anggota biasa (Tahap 9). Memanggil [RoomMembershipRemote.leave], lalu BILA server
 * menyatakan keanggotaan sudah tidak ada ([LeaveRoomOutcome.Left] atau [LeaveRoomOutcome.NoLongerMember]) memanggil
 * [markLeft] untuk menandai ruang tidak aktif di perangkat ini (status `LEFT` di `shared_room`, sehingga sinkron
 * otomatis dan manual untuk ruang itu berhenti).
 *
 * TIDAK menghapus apa pun: transaksi, kategori, dan kunci ruang tetap ada di perangkat ini. Bergabung lagi lewat
 * undangan baru memulihkan status `ACTIVE`. Seluruh urutan berjalan [NonCancellable]: setelah server berhasil mencabut
 * keanggotaan, penandaan lokal tidak boleh hilang hanya karena layar ditutup di tengah jalan.
 *
 * Tidak ada pengulangan otomatis di sini; pengguna yang mengulang lewat tombolnya (idempoten: ulangan yang dijawab
 * "bukan anggota" menyelesaikan penandaan lokal).
 */
class SharedRoomLeaver(
    private val remote: RoomMembershipRemote,
    private val markLeft: suspend (RoomId) -> Unit,
) {
    suspend fun leave(apiToken: String, roomId: RoomId): LeaveRoomOutcome = withContext(NonCancellable) {
        when (val result = remote.leave(apiToken, roomId.value)) {
            is RemoteResult.Success -> {
                markLeft(roomId)
                LeaveRoomOutcome.Left
            }

            is RemoteResult.Failure -> when (result.error.rejectionCode()) {
                ServerErrorCodes.NOT_ROOM_MEMBER -> {
                    markLeft(roomId)
                    LeaveRoomOutcome.NoLongerMember
                }

                ServerErrorCodes.OWNER_CANNOT_LEAVE -> LeaveRoomOutcome.OwnerCannotLeave
                else -> LeaveRoomOutcome.Failed(result.error)
            }
        }
    }
}
