package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.model.RoomId

/** Payload notifikasi push server rusak, bukan buatan Rizqflow, `kind`-nya tidak dikenal, atau membawa kunci lain. */
class SyncNotificationPayloadCorrupt : Exception("Payload notifikasi sync tidak valid")

/** Jenis kabar generik yang boleh dikirim lewat push — tidak satu pun membawa nominal atau isi catatan. */
enum class SyncNotificationKind {
    NEW_RECORD,
    MEMBER_REMOVED,
    ROOM_KEY_ROTATED,
}

/**
 * Payload notifikasi push dari server Rizqflow (Tahap 13, server-sync.md bagian "Notifikasi" dan "Push
 * notifikasi"): "Push dari server hanya berisi kabar umum ('ada catatan baru'); aplikasi mengambil dan
 * mendekripsi sendiri, jadi nominal tidak bocor". [SyncNotificationPayload] cuma punya dua field
 * ([roomId], [kind]), dan sejak wiring FCM (2026-11-01) [parse] juga **menolak** data push yang membawa
 * kunci apa pun selain [KEY_ROOM_ID] dan [KEY_KIND]: data berlebih (nominal, nama pihak, catatan) berarti
 * server melanggar kontrak, dan pesan itu dibuang utuh alih-alih ditampilkan sebagian. Isi catatan
 * sungguhan (termasuk nominal) hanya didapat lewat pengambilan dan dekripsi terpisah, bukan dari push.
 */
class SyncNotificationPayload(val roomId: RoomId, val kind: SyncNotificationKind) {

    override fun equals(other: Any?): Boolean =
        other is SyncNotificationPayload && roomId == other.roomId && kind == other.kind

    override fun hashCode(): Int = roomId.hashCode() * 31 + kind.hashCode()

    override fun toString(): String = "SyncNotificationPayload(roomId=$roomId, kind=$kind)"

    companion object {
        const val KEY_ROOM_ID = "roomId"
        const val KEY_KIND = "kind"

        /** Satu-satunya kunci yang boleh ada di data push; kunci lain apa pun membuat seluruh pesan ditolak. */
        val ALLOWED_KEYS: Set<String> = setOf(KEY_ROOM_ID, KEY_KIND)

        /** Batas panjang `roomId` (pengenal buram); jauh di atas UUID, cukup untuk menolak data yang jelas bukan pengenal. */
        const val MAX_ROOM_ID_LENGTH = 128

        /**
         * @throws SyncNotificationPayloadCorrupt bila [data] tidak punya `roomId` atau `kind` yang dikenal,
         *   `roomId` lebih panjang dari [MAX_ROOM_ID_LENGTH], atau [data] membawa kunci selain [ALLOWED_KEYS].
         */
        fun parse(data: Map<String, String>): SyncNotificationPayload {
            if (!ALLOWED_KEYS.containsAll(data.keys)) throw SyncNotificationPayloadCorrupt()
            val roomId = data[KEY_ROOM_ID]?.takeIf { it.isNotBlank() && it.length <= MAX_ROOM_ID_LENGTH }
                ?: throw SyncNotificationPayloadCorrupt()
            val kindRaw = data[KEY_KIND] ?: throw SyncNotificationPayloadCorrupt()
            val kind = SyncNotificationKind.entries.find { it.name == kindRaw } ?: throw SyncNotificationPayloadCorrupt()
            return SyncNotificationPayload(RoomId(roomId), kind)
        }
    }
}
