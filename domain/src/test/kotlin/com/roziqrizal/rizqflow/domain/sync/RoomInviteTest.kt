package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.model.RoomId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Payload QR undangan ruang keluarga Tahap 13: bolak-balik encode/decode, dan checksum menangkap kerusakan. */
class RoomInviteTest {

    private val invite = RoomInvite(RoomId("ruang-keluarga-1"), byteArrayOf(1, 2, 3, 4, 5, 9, 9, 9))

    @Test
    fun `decode hasil encode mengembalikan undangan yang sama`() {
        assertEquals(invite, RoomInvite.decode(invite.encode()))
    }

    @Test
    fun `roomId kosong dan kunci kosong tetap bolak-balik`() {
        val kosong = RoomInvite(RoomId(""), ByteArray(0))

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
}
