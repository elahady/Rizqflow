package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.model.RoomId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Payload notifikasi push generik Tahap 13: hanya roomId+kind yang diterima; kunci lain apa pun menolak seluruh pesan. */
class SyncNotificationPayloadTest {

    @Test
    fun `data push minimal berhasil diparse`() {
        val payload = SyncNotificationPayload.parse(mapOf("roomId" to "ruang-keluarga-1", "kind" to "NEW_RECORD"))

        assertEquals(SyncNotificationPayload(RoomId("ruang-keluarga-1"), SyncNotificationKind.NEW_RECORD), payload)
    }

    @Test
    fun `data push berisi nominal ditolak utuh`() {
        assertFailsWith<SyncNotificationPayloadCorrupt> {
            SyncNotificationPayload.parse(mapOf("roomId" to "ruang-keluarga-1", "kind" to "NEW_RECORD", "nominal" to "150000"))
        }
    }

    @Test
    fun `data push berisi nama ditolak utuh`() {
        assertFailsWith<SyncNotificationPayloadCorrupt> {
            SyncNotificationPayload.parse(mapOf("roomId" to "ruang-keluarga-1", "kind" to "NEW_RECORD", "nama" to "Kopi pagi"))
        }
    }

    @Test
    fun `data push berisi catatan ditolak utuh`() {
        assertFailsWith<SyncNotificationPayloadCorrupt> {
            SyncNotificationPayload.parse(mapOf("roomId" to "ruang-keluarga-1", "kind" to "NEW_RECORD", "catatan" to "bayar listrik"))
        }
    }

    @Test
    fun `kunci apa pun selain roomId dan kind ditolak, termasuk yang tampak tak berbahaya`() {
        for (extra in listOf("amount", "title", "body", "note", "account", "x")) {
            assertFailsWith<SyncNotificationPayloadCorrupt>(extra) {
                SyncNotificationPayload.parse(mapOf("roomId" to "r1", "kind" to "NEW_RECORD", extra to ""))
            }
        }
    }

    @Test
    fun `roomId terlalu panjang ditolak`() {
        assertFailsWith<SyncNotificationPayloadCorrupt> {
            SyncNotificationPayload.parse(mapOf("roomId" to "a".repeat(SyncNotificationPayload.MAX_ROOM_ID_LENGTH + 1), "kind" to "NEW_RECORD"))
        }
        val atLimit = "a".repeat(SyncNotificationPayload.MAX_ROOM_ID_LENGTH)
        assertEquals(RoomId(atLimit), SyncNotificationPayload.parse(mapOf("roomId" to atLimit, "kind" to "NEW_RECORD")).roomId)
    }

    @Test
    fun `roomId kosong ditolak`() {
        assertFailsWith<SyncNotificationPayloadCorrupt> {
            SyncNotificationPayload.parse(mapOf("roomId" to "", "kind" to "NEW_RECORD"))
        }
    }

    @Test
    fun `roomId tidak ada ditolak`() {
        assertFailsWith<SyncNotificationPayloadCorrupt> {
            SyncNotificationPayload.parse(mapOf("kind" to "NEW_RECORD"))
        }
    }

    @Test
    fun `kind tidak dikenal ditolak`() {
        assertFailsWith<SyncNotificationPayloadCorrupt> {
            SyncNotificationPayload.parse(mapOf("roomId" to "ruang-keluarga-1", "kind" to "DEPOSIT_RECEIVED"))
        }
    }

    @Test
    fun `kind tidak ada ditolak`() {
        assertFailsWith<SyncNotificationPayloadCorrupt> {
            SyncNotificationPayload.parse(mapOf("roomId" to "ruang-keluarga-1"))
        }
    }

    @Test
    fun `data push kosong ditolak`() {
        assertFailsWith<SyncNotificationPayloadCorrupt> { SyncNotificationPayload.parse(emptyMap()) }
    }

    @Test
    fun `member removed dan room key rotated terparse`() {
        assertEquals(SyncNotificationKind.MEMBER_REMOVED, SyncNotificationPayload.parse(mapOf("roomId" to "r1", "kind" to "MEMBER_REMOVED")).kind)
        assertEquals(SyncNotificationKind.ROOM_KEY_ROTATED, SyncNotificationPayload.parse(mapOf("roomId" to "r1", "kind" to "ROOM_KEY_ROTATED")).kind)
    }
}
