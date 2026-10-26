package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.model.RoomId
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Isi bungkusan kode pemulihan (sudah dibuka dari AES-256-GCM) rusak, terpotong, atau bukan hasil [RecoveryBundle.encode]. */
class RecoveryBundleCorrupt : Exception("Isi bungkusan kode pemulihan rusak atau tidak valid")

/**
 * Isi PLAINTEXT bungkusan kode pemulihan format `version` 2 (roadmap.md Tahap 13, "Masukkan kunci
 * ruang keluarga ke bungkusan kode pemulihan sebagai format `version` 2", keputusan pemilik
 * 2026-10-26): kunci akun PLUS kunci semua ruang keluarga yang sedang diikuti perangkat ini saat
 * [RecoveryKeyService.enableRecovery] dipanggil — supaya memulihkan di perangkat baru tidak lagi
 * mengharuskan setiap ruang diundang ulang lewat QR.
 *
 * Bytes [encode] ini adalah yang dibungkus [AccountKeyRecoveryCrypto.wrap] (kriptonya TIDAK berubah,
 * AES-256-GCM dengan kunci PBKDF2 dari kode 24 kata — hanya ISI plaintext-nya yang sekarang
 * berstruktur, bukan lagi bytes kunci akun mentah). Format mengikuti gaya [RoomInvite]: versi lalu
 * field panjang-diawali (length-prefixed) satu per satu, ditutup checksum CRC32 — bukan untuk
 * kerahasiaan (sudah di dalam amplop AES-GCM), hanya supaya bungkusan yang terpotong atau rusak di
 * tengah jalan (bukan kode salah) terdeteksi dengan pesan yang jelas, sama alasannya dengan checksum
 * [RecoveryCode] dan [RoomInvite].
 *
 * Versi `version` 1 LAMA (server-sync.md, sebelum 2026-10-27) tidak memakai kelas ini sama sekali:
 * plaintext-nya adalah bytes [com.roziqrizal.rizqflow.domain.sync.AccountKeyProvider.exportAccountKeyForRecovery]
 * mentah, tanpa struktur apa pun. [RecoveryKeyService] yang membedakan keduanya berdasarkan
 * `version` dari server, bukan kelas ini.
 */
class RecoveryBundle(val accountKey: ByteArray, val roomKeys: List<RoomKeyEntry>) {

    /** Satu kunci ruang terekspor ([RoomKeyProvider.exportRoomKey]) beserta [roomId]-nya. */
    data class RoomKeyEntry(val roomId: RoomId, val exportedRoomKey: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is RoomKeyEntry && roomId == other.roomId && exportedRoomKey.contentEquals(other.exportedRoomKey)

        override fun hashCode(): Int = roomId.hashCode() * 31 + exportedRoomKey.contentHashCode()
    }

    override fun equals(other: Any?): Boolean =
        other is RecoveryBundle && accountKey.contentEquals(other.accountKey) && roomKeys == other.roomKeys

    override fun hashCode(): Int = accountKey.contentHashCode() * 31 + roomKeys.hashCode()

    /** Tidak pernah mencetak isi kunci: dibungkus kode pemulihan, tapi tak ada alasan membiarkannya di log. */
    override fun toString(): String = "RecoveryBundle(accountKey=${accountKey.size} byte, roomKeys=${roomKeys.size} ruang)"

    /** Bytes berstruktur: versi, kunci akun, jumlah ruang, lalu (roomId, kunci ruang) per ruang, ditutup checksum CRC32. */
    fun encode(): ByteArray {
        val roomBytes = roomKeys.map { it.roomId.value.toByteArray(Charsets.UTF_8) to it.exportedRoomKey }
        val roomsSize = roomBytes.sumOf { (roomIdBytes, keyBytes) -> 2 + roomIdBytes.size + 2 + keyBytes.size }
        val payloadSize = 1 + 2 + accountKey.size + 2 + roomsSize
        val buffer = ByteBuffer.allocate(payloadSize + 4).order(ByteOrder.BIG_ENDIAN)
        buffer.put(VERSION)
        buffer.putShort(accountKey.size.toShort())
        buffer.put(accountKey)
        buffer.putShort(roomBytes.size.toShort())
        for ((roomIdBytes, keyBytes) in roomBytes) {
            buffer.putShort(roomIdBytes.size.toShort())
            buffer.put(roomIdBytes)
            buffer.putShort(keyBytes.size.toShort())
            buffer.put(keyBytes)
        }
        buffer.putInt(crc32(buffer.array(), payloadSize))
        return buffer.array()
    }

    companion object {
        /** Satu-satunya versi struktur sejauh ini; terpisah dari `version` 2 di server-sync.md ([RECOVERY_KEY_VERSION]). */
        private const val VERSION: Byte = 1

        /** @throws RecoveryBundleCorrupt bila [bytes] bukan hasil [encode], terpotong, atau checksum tidak cocok. */
        fun decode(bytes: ByteArray): RecoveryBundle {
            try {
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                if (buffer.get() != VERSION) throw RecoveryBundleCorrupt()
                val accountKey = ByteArray(buffer.short.toInt() and 0xFFFF).also(buffer::get)
                val roomCount = buffer.short.toInt() and 0xFFFF
                val roomKeys = (1..roomCount).map {
                    val roomIdBytes = ByteArray(buffer.short.toInt() and 0xFFFF).also(buffer::get)
                    val keyBytes = ByteArray(buffer.short.toInt() and 0xFFFF).also(buffer::get)
                    RoomKeyEntry(RoomId(String(roomIdBytes, Charsets.UTF_8)), keyBytes)
                }
                val payloadSize = bytes.size - 4
                if (buffer.int != crc32(bytes, payloadSize)) throw RecoveryBundleCorrupt()
                return RecoveryBundle(accountKey, roomKeys)
            } catch (e: RecoveryBundleCorrupt) {
                throw e
            } catch (e: Exception) {
                throw RecoveryBundleCorrupt()
            }
        }

        private fun crc32(bytes: ByteArray, length: Int): Int =
            CRC32().apply { update(bytes, 0, length) }.value.toInt()
    }
}
