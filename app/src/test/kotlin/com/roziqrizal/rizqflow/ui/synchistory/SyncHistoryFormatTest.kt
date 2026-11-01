package com.roziqrizal.rizqflow.ui.synchistory

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/** Format tampilan Riwayat sinkron: ukuran data, waktu, dan id perangkat pendek. Locale dan zona waktu selalu disuntikkan. */
class SyncHistoryFormatTest {
    private val en = Locale.US
    private val id = Locale.forLanguageTag("id-ID")

    @Test
    fun `ukuran di bawah satu kilobyte tampil dalam byte`() {
        assertEquals("0 B", formatSyncBytes(0, en))
        assertEquals("1023 B", formatSyncBytes(1023, en))
    }

    @Test
    fun `ukuran kilobyte dan megabyte satu desimal dan dipotong ke bawah`() {
        assertEquals("1.0 KB", formatSyncBytes(1024, en))
        assertEquals("1.5 KB", formatSyncBytes(1536, en))
        assertEquals("1.0 KB", formatSyncBytes(1024 + 101, en), "1,099 KB tidak boleh dibulatkan ke 1,1")
        assertEquals("1.0 MB", formatSyncBytes(1024L * 1024, en))
        assertEquals("150.0 MB", formatSyncBytes(157_286_400, en))
    }

    @Test
    fun `batas kuota 150 MB dan pemakaian hampir penuh tidak tampil lebih penuh dari kenyataan`() {
        assertEquals("149.9 MB", formatSyncBytes(157_286_400 - 1, en))
    }

    @Test
    fun `desimal mengikuti bahasa perangkat dan nilai negatif dianggap nol`() {
        assertEquals("1,5 KB", formatSyncBytes(1536, id))
        assertEquals("0 B", formatSyncBytes(-5, en))
    }

    @Test
    fun `waktu diformat di zona waktu yang diberikan`() {
        val at = Instant.parse("2026-11-01T17:30:00Z")

        assertEquals("Nov 2, 2026, 12:30 AM", formatSyncTime(at, ZoneId.of("Asia/Jakarta"), en).replace(' ', ' '))
        assertEquals("Nov 1, 2026", formatSyncDate(at, ZoneId.of("UTC"), en))
    }

    @Test
    fun `id perangkat panjang dipendekkan delapan karakter`() {
        assertEquals("a1b2c3d4", shortDeviceId("a1b2c3d4-0000-4000-8000-000000000000"))
        assertEquals("hp", shortDeviceId("hp"))
    }
}
