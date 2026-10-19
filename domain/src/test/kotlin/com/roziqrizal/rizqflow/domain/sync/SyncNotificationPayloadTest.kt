package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.model.RoomId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Payload notifikasi push generik Tahap 13: hanya roomId+kind yang terbaca, field lain diabaikan. */
class SyncNotificationPayloadTest {

    @Test
    fun `data push minimal berhasil diparse`() {
        val payload = SyncNotificationPayload.parse(mapOf("roomId" to "ruang-keluarga-1", "kind" to "NEW_RECORD"))

        assertEquals(SyncNotificationPayload(RoomId("ruang-keluarga-1"), SyncNotificationKind.NEW_RECORD), payload)
    }

    @Test
    fun `field tambahan selain roomId dan kind tidak pernah ikut terbaca`() {
        val payload = SyncNotificationPayload.parse(
            mapOf(
                "roomId" to "ruang-keluarga-1",
                "kind" to "NEW_RECORD",
                "nominal" to "150000",
                "nama" to "Kopi pagi",
            ),
        )

        assertEquals(SyncNotificationPayload(RoomId("ruang-keluarga-1"), SyncNotificationKind.NEW_RECORD), payload)
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
