package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.model.RoomId
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Payload QR undangan ruang keluarga Tahap 13: bolak-balik encode/decode, dan checksum menangkap
 * kerusakan. Sejak Tahap 9 (2026-10-27, versi payload 3) juga membuktikan [RoomInvite.memberSecret]
 * dan kompatibilitas mundur membaca undangan versi 1 dan 2 lama (tanpa rahasia itu sama sekali) —
 * lihat [encodeV1]/[encodeV2] yang MENIRU format historis apa adanya, bukan memanggil [RoomInvite.encode]
 * yang sekarang selalu menulis versi 3.
 */
class RoomInviteTest {

    private val invite = RoomInvite(RoomId("ruang-keluarga-1"), "Keluarga", byteArrayOf(1, 2, 3, 4, 5, 9, 9, 9))

    @Test
    fun `decode hasil encode mengembalikan undangan yang sama`() {
        assertEquals(invite, RoomInvite.decode(invite.encode()))
    }

    @Test
    fun `roomId, nama, dan kunci kosong tetap bolak-balik`() {
        val kosong = RoomInvite(RoomId(""), "", ByteArray(0))

        assertEquals(kosong, RoomInvite.decode(kosong.encode()))
    }

    @Test
    fun `satu byte berubah di tengah payload ditolak checksum`() {
        val bytes = invite.encode()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()

        assertFailsWith<RoomInviteCorrupt> { RoomInvite.decode(bytes) }
    }

    @Test
    fun `bytes terpotong ditolak, bukan melempar exception lain`() {
        val truncated = invite.encode().copyOf(3)

        assertFailsWith<RoomInviteCorrupt> { RoomInvite.decode(truncated) }
    }

    @Test
    fun `bytes kosong ditolak`() {
        assertFailsWith<RoomInviteCorrupt> { RoomInvite.decode(ByteArray(0)) }
    }

    @Test
    fun `versi tidak dikenal ditolak`() {
        val bytes = invite.encode()
        bytes[0] = 99

        assertFailsWith<RoomInviteCorrupt> { RoomInvite.decode(bytes) }
    }

    // --- Tahap 9, 2026-10-27: versi payload 3, member_secret (sync-desain.md bagian F, keputusan #9) ---

    @Test
    fun `create menghasilkan member_secret acak sepanjang MEMBER_SECRET_LENGTH`() {
        val created = RoomInvite.create(RoomId("ruang-1"), "Keluarga", byteArrayOf(1, 2, 3))

        assertEquals(MEMBER_SECRET_LENGTH, created.memberSecret!!.size)
    }

    @Test
    fun `create dua kali menghasilkan member_secret berbeda`() {
        val a = RoomInvite.create(RoomId("ruang-1"), "Keluarga", byteArrayOf(1))
        val b = RoomInvite.create(RoomId("ruang-1"), "Keluarga", byteArrayOf(1))

        assertTrue(!a.memberSecret!!.contentEquals(b.memberSecret!!))
    }

    @Test
    fun `encode decode undangan versi 3 dengan member_secret bolak-balik`() {
        val withSecret = RoomInvite.create(RoomId("ruang-keluarga-1"), "Keluarga", byteArrayOf(1, 2, 3, 4))

        assertEquals(withSecret, RoomInvite.decode(withSecret.encode()))
    }

    @Test
    fun `encode decode undangan versi 3 tanpa member_secret (null) bolak-balik`() {
        assertEquals(invite, RoomInvite.decode(invite.encode()))
        assertNull(RoomInvite.decode(invite.encode()).memberSecret)
    }

    @Test
    fun `member_secret ikut membuat undangan berbeda tidak sama walau field lain identik`() {
        val random = SecureRandom()
        val a = RoomInvite.create(RoomId("ruang-1"), "Keluarga", byteArrayOf(1), random)
        val b = RoomInvite(RoomId("ruang-1"), "Keluarga", byteArrayOf(1), null)

        assertTrue(a != b)
    }

    @Test
    fun `undangan versi 1 lama (tanpa roomName dan member_secret) tetap bisa dibaca`() {
        val decoded = RoomInvite.decode(encodeV1(RoomId("ruang-lama"), byteArrayOf(9, 8, 7)))

        assertEquals(RoomId("ruang-lama"), decoded.roomId)
        assertEquals("", decoded.roomName)
        assertTrue(byteArrayOf(9, 8, 7).contentEquals(decoded.exportedRoomKey))
        assertNull(decoded.memberSecret)
    }

    @Test
    fun `undangan versi 2 lama (tanpa member_secret) tetap bisa dibaca`() {
        val decoded = RoomInvite.decode(encodeV2(RoomId("ruang-lama"), "Keluarga Lama", byteArrayOf(9, 8, 7)))

        assertEquals(RoomId("ruang-lama"), decoded.roomId)
        assertEquals("Keluarga Lama", decoded.roomName)
        assertTrue(byteArrayOf(9, 8, 7).contentEquals(decoded.exportedRoomKey))
        assertNull(decoded.memberSecret)
    }

    @Test
    fun `undangan versi 1 yang rusak tetap ditolak checksum`() {
        val bytes = encodeV1(RoomId("ruang-lama"), byteArrayOf(9, 8, 7))
        bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()

        assertFailsWith<RoomInviteCorrupt> { RoomInvite.decode(bytes) }
    }

    /** Menirukan `RoomInvite.encode()` historis versi 1 (commit 5c4f050): tanpa roomName, tanpa member_secret. */
    private fun encodeV1(roomId: RoomId, exportedRoomKey: ByteArray): ByteArray {
        val roomIdBytes = roomId.value.toByteArray(Charsets.UTF_8)
        val payloadSize = 1 + 2 + roomIdBytes.size + 2 + exportedRoomKey.size
        val buffer = ByteBuffer.allocate(payloadSize + 4).order(ByteOrder.BIG_ENDIAN)
        buffer.put(1.toByte())
        buffer.putShort(roomIdBytes.size.toShort())
        buffer.put(roomIdBytes)
        buffer.putShort(exportedRoomKey.size.toShort())
        buffer.put(exportedRoomKey)
        buffer.putInt(CRC32().apply { update(buffer.array(), 0, payloadSize) }.value.toInt())
        return buffer.array()
    }

    /** Menirukan `RoomInvite.encode()` historis versi 2 (commit 1a18a81): roomName, tanpa member_secret. */
    private fun encodeV2(roomId: RoomId, roomName: String, exportedRoomKey: ByteArray): ByteArray {
        val roomIdBytes = roomId.value.toByteArray(Charsets.UTF_8)
        val roomNameBytes = roomName.toByteArray(Charsets.UTF_8)
        val payloadSize = 1 + 2 + roomIdBytes.size + 2 + roomNameBytes.size + 2 + exportedRoomKey.size
        val buffer = ByteBuffer.allocate(payloadSize + 4).order(ByteOrder.BIG_ENDIAN)
        buffer.put(2.toByte())
        buffer.putShort(roomIdBytes.size.toShort())
        buffer.put(roomIdBytes)
        buffer.putShort(roomNameBytes.size.toShort())
        buffer.put(roomNameBytes)
        buffer.putShort(exportedRoomKey.size.toShort())
        buffer.put(exportedRoomKey)
        buffer.putInt(CRC32().apply { update(buffer.array(), 0, payloadSize) }.value.toInt())
        return buffer.array()
    }
}
