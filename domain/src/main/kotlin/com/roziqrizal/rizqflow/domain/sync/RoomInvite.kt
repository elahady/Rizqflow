package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.model.RoomId
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Kode QR undangan rusak, terpotong, atau bukan kode undangan Rizqflow. */
class RoomInviteCorrupt : Exception("Kode QR undangan rusak atau tidak valid — coba pindai ulang")

/**
 * Payload QR undangan ruang keluarga (Tahap 13, server-sync.md bagian "Mengundang pasangan"):
 * "bertemu langsung dan memindai QR untuk memverifikasi kunci, tidak bergantung pada server" —
 * QR sendiri sudah jadi saluran yang dipercaya (dipindai langsung dari layar, bukan dikirim lewat
 * jaringan), jadi [encode]/[decode] tidak mengenkripsi apa pun, hanya membungkus [roomId],
 * [roomName], dan kunci ruang terekspor ([RoomKeyProvider.exportRoomKey]) jadi satu larik byte
 * ringkas untuk QR, dengan checksum supaya pemindaian yang gagal/rusak terdeteksi sebelum kuncinya
 * dipakai — bukan untuk kerahasiaan, sama alasannya dengan checksum [RecoveryCode]. [roomName]
 * ditambahkan di Tahap 9 (2026-10-24) supaya ruang yang dibuat di perangkat anggota baru saat
 * bergabung ([com.roziqrizal.rizqflow.domain.sync.SyncEngine] belum menyinkronkan metadata ruang,
 * hanya transaksi) punya nama yang masuk akal, bukan generik.
 */
class RoomInvite(val roomId: RoomId, val roomName: String, val exportedRoomKey: ByteArray) {

    override fun equals(other: Any?): Boolean =
        other is RoomInvite && roomId == other.roomId && roomName == other.roomName && exportedRoomKey.contentEquals(other.exportedRoomKey)

    override fun hashCode(): Int = (roomId.hashCode() * 31 + roomName.hashCode()) * 31 + exportedRoomKey.contentHashCode()

    /** Bytes ringkas untuk dirender jadi QR: versi, roomId, nama ruang, kunci ruang terekspor, lalu checksum CRC32. */
    fun encode(): ByteArray {
        val roomIdBytes = roomId.value.toByteArray(Charsets.UTF_8)
        val roomNameBytes = roomName.toByteArray(Charsets.UTF_8)
        val payloadSize = 1 + 2 + roomIdBytes.size + 2 + roomNameBytes.size + 2 + exportedRoomKey.size
        val buffer = ByteBuffer.allocate(payloadSize + 4).order(ByteOrder.BIG_ENDIAN)
        buffer.put(VERSION)
        buffer.putShort(roomIdBytes.size.toShort())
        buffer.put(roomIdBytes)
        buffer.putShort(roomNameBytes.size.toShort())
        buffer.put(roomNameBytes)
        buffer.putShort(exportedRoomKey.size.toShort())
        buffer.put(exportedRoomKey)
        buffer.putInt(crc32(buffer.array(), payloadSize))
        return buffer.array()
    }

    companion object {
        /** Versi 2 (2026-10-24): tambah [roomName] — belum pernah dipakai UI sungguhan di versi 1, aman diganti. */
        private const val VERSION: Byte = 2

        /** @throws RoomInviteCorrupt bila bytes bukan hasil [encode], terpotong, atau checksum tidak cocok. */
        fun decode(bytes: ByteArray): RoomInvite {
            try {
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                if (buffer.get() != VERSION) throw RoomInviteCorrupt()
                val roomIdBytes = ByteArray(buffer.short.toInt() and 0xFFFF).also(buffer::get)
                val roomNameBytes = ByteArray(buffer.short.toInt() and 0xFFFF).also(buffer::get)
                val exportedRoomKey = ByteArray(buffer.short.toInt() and 0xFFFF).also(buffer::get)
                val payloadSize = bytes.size - 4
                if (buffer.int != crc32(bytes, payloadSize)) throw RoomInviteCorrupt()
                return RoomInvite(RoomId(String(roomIdBytes, Charsets.UTF_8)), String(roomNameBytes, Charsets.UTF_8), exportedRoomKey)
            } catch (e: RoomInviteCorrupt) {
                throw e
            } catch (e: Exception) {
                throw RoomInviteCorrupt()
            }
        }

        private fun crc32(bytes: ByteArray, length: Int): Int =
            CRC32().apply { update(bytes, 0, length) }.value.toInt()
    }
}
