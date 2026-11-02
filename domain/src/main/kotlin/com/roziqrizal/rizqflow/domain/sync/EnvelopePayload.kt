package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.model.RoomId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Id rekaman sinkron amplop (peran-anggota.md E.2): DETERMINISTIK (bukan UUID acak seperti transaksi), supaya dua
 * tulisan untuk anggota dan bulan yang sama konvergen lewat LWW ([SyncOrdering]) alih-alih menumpuk dua rekaman.
 * Bentuknya `envelope|<roomId>|<memberId>|<fromMonth>`; `|` tidak boleh muncul di [RoomId]/`memberId` (keduanya id
 * buram tanpa pemisah ini di praktiknya — [RoomId] UUID, `memberId` heksadesimal).
 */
object EnvelopeRecordId {
    private const val PREFIX = "envelope"

    fun format(roomId: RoomId, memberId: String, fromMonth: Int): String = "$PREFIX|${roomId.value}|$memberId|$fromMonth"

    data class Ref(val roomId: RoomId, val memberId: String, val fromMonth: Int)

    /** Null bila [id] bukan hasil [format] yang sah (prefiks salah, jumlah bagian salah, atau bulan bukan angka). */
    fun parse(id: String): Ref? {
        val parts = id.split('|')
        if (parts.size != 4 || parts[0] != PREFIX || parts[1].isEmpty() || parts[2].isEmpty()) return null
        val fromMonth = parts[3].toIntOrNull() ?: return null
        return Ref(RoomId(parts[1]), parts[2], fromMonth)
    }
}

/** Isi amplop sinkron sudah terbuka dan terbaca ([EnvelopePayload.decode]); [roomId] dibandingkan lagi terhadap ruang yang sedang disinkron oleh pemanggil ([EnvelopeSyncHandler.apply]) — amplop yang isinya menyebut ruang lain ditolak sebagai korup. */
data class DecodedEnvelope(val roomId: RoomId, val memberId: String, val fromMonth: Int, val amount: Long)

/**
 * Isi `d` amplop [SyncEnvelope] untuk [SharedRecordType.ENVELOPE] (peran-anggota.md E.2, versi [VERSION]).
 * Bentuk JSON: `{"room":"<roomId>","member":"<memberId>","from_month":202611,"amount":1500000}` — `room`/`member`
 * dibawa lagi di dalam `d` (redundan dengan [EnvelopeRecordId] di kolom `id` amplop) karena itu persis format kabel
 * yang didokumentasikan peran-anggota.md E.2, dan supaya [decode] tidak bergantung pada [EnvelopeRecordId.parse]
 * untuk mendapatkan nilainya sendiri.
 */
object EnvelopePayload {
    const val VERSION = 1

    fun encode(roomId: RoomId, memberId: String, fromMonth: Int, amount: Long): JsonObject = buildJsonObject {
        put("room", roomId.value)
        put("member", memberId)
        put("from_month", fromMonth)
        put("amount", amount)
    }

    /** @throws TransactionSyncPayloadCorrupt bila bidang wajib hilang atau salah jenis (dipakai ulang dari [SharedExpensePayload]: sudah jadi sinyal "payload rusak" generik yang ditangkap [SyncCoordinator]). */
    fun decode(data: JsonObject): DecodedEnvelope {
        val room = data.text("room")
        val member = data.text("member")
        val fromMonth = data.number("from_month")?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        val amount = data.number("amount")
        if (room.isNullOrEmpty() || member.isNullOrEmpty() || fromMonth == null || amount == null) {
            throw TransactionSyncPayloadCorrupt()
        }
        return DecodedEnvelope(RoomId(room), member, fromMonth, amount)
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
}
