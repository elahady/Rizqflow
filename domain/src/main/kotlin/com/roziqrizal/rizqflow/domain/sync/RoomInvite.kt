package com.roziqrizal.rizqflow.domain.sync

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.zip.CRC32
import com.roziqrizal.rizqflow.domain.model.RoomId

/** Kode QR undangan rusak, terpotong, atau bukan kode undangan Rizqflow. */
class RoomInviteCorrupt : Exception("Kode QR undangan rusak atau tidak valid — coba pindai ulang")

/**
 * Panjang [RoomInvite.memberSecret] dalam byte (sync-desain.md bagian F, keputusan pemilik #9).
 */
const val MEMBER_SECRET_LENGTH = 32

/**
 * Payload QR undangan ruang keluarga (Tahap 13, server-sync.md bagian "Mengundang pasangan"):
 * "bertemu langsung dan memindai QR untuk memverifikasi kunci, tidak bergantung pada server" —
 * QR sendiri sudah jadi saluran yang dipercaya (dipindai langsung dari layar, bukan dikirim lewat
 * jaringan), jadi [encode]/[decode] tidak mengenkripsi apa pun, hanya membungkus [roomId],
 * [roomName], kunci ruang terekspor ([RoomKeyProvider.exportRoomKey]), dan (sejak versi 3)
 * [memberSecret] jadi satu larik byte ringkas untuk QR, dengan checksum supaya pemindaian yang
 * gagal/rusak terdeteksi sebelum kuncinya dipakai — bukan untuk kerahasiaan, sama alasannya dengan
 * checksum [RecoveryCode]. [roomName] ditambahkan di Tahap 9 (2026-10-24) supaya ruang yang dibuat
 * di perangkat anggota baru saat bergabung punya nama yang masuk akal, bukan generik.
 *
 * **[memberSecret] (Tahap 9, 2026-10-27, sync-desain.md bagian F "Rotasi dan keluarkan anggota ke
 * anggota yang tetap", keputusan pemilik #9 — opsi 3 "rahasia pasangan dari QR"):** rahasia acak
 * [MEMBER_SECRET_LENGTH] byte dibuat PEMILIK ruang (lihat [create]) untuk SETIAP undangan yang
 * ditampilkan sebagai QR — bukan dibagi sesama anggota. Dipakai nanti sebagai kunci AEAD `key_grant`
 * saat rotasi, supaya anggota yang tetap bisa menerima kunci ruang baru tanpa bertemu langsung lagi.
 * **Penerbitan/pengambilan `key_grant` lewat sync BELUM diimplementasikan** — lihat KDoc
 * [RoomMemberKeyRedistribution] dan roadmap.md Tahap 9: mesin sync ruang bersama ([SyncCoordinator])
 * belum siap menampung tipe rekaman baru, jadi bagian itu ditangguhkan. [memberSecret] di kelas ini
 * murni dibungkus/dibongkar dari QR; penyimpanannya di perangkat ada di [MemberSecretStore].
 *
 * Null bila [decode] membaca undangan versi 1 atau 2 (lama, tanpa rahasia ini sama sekali) — anggota
 * yang menerima undangan versi lama itu tidak bisa menerima `key_grant` otomatis nanti, tetap harus
 * dipindai ulang manual saat rotasi ("QR ulang cadangan", sudah disetujui sebagai bagian dari opsi 3).
 */
class RoomInvite(val roomId: RoomId, val roomName: String, val exportedRoomKey: ByteArray, val memberSecret: ByteArray? = null) {

    override fun equals(other: Any?): Boolean =
        other is RoomInvite &&
            roomId == other.roomId &&
            roomName == other.roomName &&
            exportedRoomKey.contentEquals(other.exportedRoomKey) &&
            memberSecretEquals(memberSecret, other.memberSecret)

    override fun hashCode(): Int =
        ((roomId.hashCode() * 31 + roomName.hashCode()) * 31 + exportedRoomKey.contentHashCode()) * 31 + (memberSecret?.contentHashCode() ?: 0)

    /**
     * Bytes ringkas untuk dirender jadi QR, selalu format versi [VERSION] (3) sekarang: versi,
     * roomId, nama ruang, kunci ruang terekspor, [memberSecret] (kosong bila null — dipertahankan
     * hanya untuk kasus uji; [create] selalu mengisinya untuk undangan sungguhan), lalu checksum CRC32.
     */
    fun encode(): ByteArray {
        val roomIdBytes = roomId.value.toByteArray(Charsets.UTF_8)
        val roomNameBytes = roomName.toByteArray(Charsets.UTF_8)
        val secretBytes = memberSecret ?: ByteArray(0)
        val payloadSize = 1 + 2 + roomIdBytes.size + 2 + roomNameBytes.size + 2 + exportedRoomKey.size + 2 + secretBytes.size
        val buffer = ByteBuffer.allocate(payloadSize + 4).order(ByteOrder.BIG_ENDIAN)
        buffer.put(VERSION)
        buffer.putShort(roomIdBytes.size.toShort())
        buffer.put(roomIdBytes)
        buffer.putShort(roomNameBytes.size.toShort())
        buffer.put(roomNameBytes)
        buffer.putShort(exportedRoomKey.size.toShort())
        buffer.put(exportedRoomKey)
        buffer.putShort(secretBytes.size.toShort())
        buffer.put(secretBytes)
        buffer.putInt(crc32(buffer.array(), payloadSize))
        return buffer.array()
    }

    companion object {
        /**
         * Versi 3 (2026-10-27): tambah [memberSecret]. Versi 2 (2026-10-24): tambah [roomName].
         * [encode] selalu menulis versi ini; [decode] tetap membaca versi 1 dan 2 lama (lihat bawah)
         * supaya QR yang sudah pernah ditampilkan sebelum pembaruan ini tidak mendadak ditolak.
         */
        private const val VERSION: Byte = 3
        private const val VERSION_2: Byte = 2
        private const val VERSION_1: Byte = 1

        /** Buat undangan baru dengan [memberSecret] acak segar — satu per undangan, dibuat PEMILIK ruang. */
        fun create(roomId: RoomId, roomName: String, exportedRoomKey: ByteArray, random: SecureRandom = SecureRandom()): RoomInvite {
            val secret = ByteArray(MEMBER_SECRET_LENGTH)
            random.nextBytes(secret)
            return RoomInvite(roomId, roomName, exportedRoomKey, secret)
        }

        /**
         * @throws RoomInviteCorrupt bila bytes bukan hasil [encode], terpotong, checksum tidak cocok,
         *   atau versinya tidak dikenal (bukan 1, 2, maupun 3).
         */
        fun decode(bytes: ByteArray): RoomInvite {
            try {
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                val version = buffer.get()
                if (version != VERSION && version != VERSION_2 && version != VERSION_1) throw RoomInviteCorrupt()

                val roomIdBytes = ByteArray(buffer.short.toInt() and 0xFFFF).also(buffer::get)
                // Versi 1 (2026-09, sebelum rilis UI sungguhan): tanpa roomName sama sekali.
                val roomNameBytes = if (version != VERSION_1) ByteArray(buffer.short.toInt() and 0xFFFF).also(buffer::get) else ByteArray(0)
                val exportedRoomKey = ByteArray(buffer.short.toInt() and 0xFFFF).also(buffer::get)
                // Versi 1 dan 2: tanpa member_secret sama sekali (ditambahkan versi 3).
                val memberSecret = if (version == VERSION) ByteArray(buffer.short.toInt() and 0xFFFF).also(buffer::get) else null

                val payloadSize = bytes.size - 4
                if (buffer.int != crc32(bytes, payloadSize)) throw RoomInviteCorrupt()

                return RoomInvite(
                    RoomId(String(roomIdBytes, Charsets.UTF_8)),
                    String(roomNameBytes, Charsets.UTF_8),
                    exportedRoomKey,
                    memberSecret?.takeIf { it.isNotEmpty() },
                )
            } catch (e: RoomInviteCorrupt) {
                throw e
            } catch (e: Exception) {
                throw RoomInviteCorrupt()
            }
        }

        private fun crc32(bytes: ByteArray, length: Int): Int =
            CRC32().apply { update(bytes, 0, length) }.value.toInt()

        private fun memberSecretEquals(a: ByteArray?, b: ByteArray?): Boolean =
            if (a == null || b == null) a == null && b == null else a.contentEquals(b)
    }
}
