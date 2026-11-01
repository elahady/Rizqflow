package com.roziqrizal.rizqflow.domain.sync

import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pemotongan push per batas 200 baris / 800 KB (sync-desain.md bagian C "Batas"). */
class SyncPushBatcherTest {
    @Test
    fun `tepat 200 baris kecil masuk satu batch`() {
        val items = List(200) { it }

        val batches = SyncPushBatcher.split(items) { 100 }

        assertEquals(1, batches.size)
        assertEquals(items, batches.single())
    }

    @Test
    fun `201 baris terpotong menjadi 200 dan 1 dengan urutan terjaga`() {
        val items = List(201) { it }

        val batches = SyncPushBatcher.split(items) { 100 }

        assertEquals(listOf(200, 1), batches.map { it.size })
        assertEquals(items, batches.flatten())
    }

    @Test
    fun `batch tepat di batas byte tidak dipotong, satu byte lebih dipotong`() {
        val limits = SyncPushLimits(maxRows = 1000, maxBytes = SyncPushBatcher.REQUEST_OVERHEAD_BYTES + 300)

        val pas = SyncPushBatcher.split(List(3) { it }, limits) { 100 }
        val lebih = SyncPushBatcher.split(List(3) { it }, limits) { 101 }

        assertEquals(listOf(3), pas.map { it.size })
        assertEquals(listOf(2, 1), lebih.map { it.size })
    }

    @Test
    fun `batas byte bawaan 800 KB memotong payload besar`() {
        // 100 baris x 10 KB = ~1000 KB: harus jadi dua batch, masing-masing <= 800 KB.
        val items = List(100) { it }
        val size = 10 * 1024

        val batches = SyncPushBatcher.split(items) { size }

        assertEquals(2, batches.size)
        batches.forEach { batch ->
            assertTrue(SyncPushBatcher.REQUEST_OVERHEAD_BYTES + batch.size * size <= 800 * 1024)
        }
        assertEquals(items, batches.flatten())
    }

    @Test
    fun `satu baris yang sendirian melebihi batas byte tetap dikirim sebagai batch tunggal`() {
        val limits = SyncPushLimits(maxRows = 10, maxBytes = 500)

        val batches = SyncPushBatcher.split(listOf("kecil", "raksasa", "kecil2"), limits) { if (it == "raksasa") 10_000 else 100 }

        assertEquals(listOf(listOf("kecil"), listOf("raksasa"), listOf("kecil2")), batches)
    }

    @Test
    fun `daftar kosong menghasilkan nol batch`() {
        assertEquals(emptyList(), SyncPushBatcher.split(emptyList<Int>()) { 1 })
    }

    @Test
    fun `perkiraan byte mencakup payload dan tidak lebih kecil dari JSON sebenarnya`() {
        val entry = SyncLogEntryUpload(
            roomId = null,
            recordType = "r",
            recordId = "abcdefghijklmnopqrstuv",
            deviceId = "device-1",
            payload = "A".repeat(1000),
            deleted = false,
            changedAt = "2026-11-01T00:00:00Z",
            changedAtMillis = 1_793_491_200_000,
        )
        val json = """{"room_id":null,"record_type":"r","record_id":"abcdefghijklmnopqrstuv","device_id":"device-1",""" +
            """"payload":"${"A".repeat(1000)}","deleted":false,"changed_at":"2026-11-01T00:00:00Z","changed_at_ms":1793491200000},"""

        assertTrue(SyncPushBatcher.estimateBytes(entry) >= json.length)
    }

    @Test
    fun `batas tidak sah ditolak`() {
        assertFailsWith<IllegalArgumentException> { SyncPushLimits(maxRows = 0) }
        assertFailsWith<IllegalArgumentException> { SyncPushLimits(maxBytes = 0) }
    }
}
