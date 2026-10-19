package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.model.RoomId

/** Payload notifikasi push server rusak, bukan buatan Rizqflow, atau `kind`-nya tidak dikenal. */
class SyncNotificationPayloadCorrupt : Exception("Payload notifikasi sync tidak valid")

/** Jenis kabar generik yang boleh dikirim lewat push — tidak satu pun membawa nominal atau isi catatan. */
enum class SyncNotificationKind {
    NEW_RECORD,
    MEMBER_REMOVED,
    ROOM_KEY_ROTATED,
}

/**
 * Payload notifikasi push dari server Rizqflow (Tahap 13, server-sync.md bagian "Notifikasi"):
 * "Push dari server hanya berisi kabar umum ('ada catatan baru'); aplikasi mengambil dan
 * mendekripsi sendiri, jadi nominal tidak bocor". [parse] sengaja hanya membaca dua kunci
 * ([KEY_ROOM_ID], [KEY_KIND]) dari data push mentah dan [SyncNotificationPayload] sendiri cuma
 * punya dua field ([roomId], [kind]) — jadi walau server suatu saat salah kirim field tambahan
 * (nominal, nama pihak, dsb.) di data push, tidak ada jalur kode di sisi aplikasi yang
 * membacanya. Isi catatan sungguhan (termasuk nominal) hanya didapat lewat pengambilan dan
 * dekripsi terpisah setelah notifikasi ini diterima (belum ada — menunggu server Tahap 9), bukan
 * dari payload push itu sendiri.
 */
class SyncNotificationPayload(val roomId: RoomId, val kind: SyncNotificationKind) {

    override fun equals(other: Any?): Boolean =
        other is SyncNotificationPayload && roomId == other.roomId && kind == other.kind

    override fun hashCode(): Int = roomId.hashCode() * 31 + kind.hashCode()

    override fun toString(): String = "SyncNotificationPayload(roomId=$roomId, kind=$kind)"

    companion object {
        private const val KEY_ROOM_ID = "roomId"
        private const val KEY_KIND = "kind"

        /** @throws SyncNotificationPayloadCorrupt bila [data] tidak punya `roomId` atau `kind` yang dikenal. */
        fun parse(data: Map<String, String>): SyncNotificationPayload {
            val roomId = data[KEY_ROOM_ID]?.takeIf { it.isNotBlank() } ?: throw SyncNotificationPayloadCorrupt()
            val kindRaw = data[KEY_KIND] ?: throw SyncNotificationPayloadCorrupt()
            val kind = SyncNotificationKind.entries.find { it.name == kindRaw } ?: throw SyncNotificationPayloadCorrupt()
            return SyncNotificationPayload(RoomId(roomId), kind)
        }
    }
}
