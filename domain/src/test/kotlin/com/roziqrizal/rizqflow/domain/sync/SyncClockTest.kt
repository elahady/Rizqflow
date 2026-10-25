package com.roziqrizal.rizqflow.domain.sync

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncClockTest {
    private var now = 1_000_000L
    private val clock = SyncClock { now }

    @Test
    fun `tanpa rekaman dan tanpa offset cap sama dengan jam perangkat`() {
        assertEquals(1_000_000L, clock.stamp())
        assertEquals(0L, clock.serverOffsetMillis)
    }

    @Test
    fun `offset server ditambahkan ke jam perangkat`() {
        clock.setServerOffset(5_000)
        assertEquals(1_005_000L, clock.stamp())
        assertEquals(5_000L, clock.serverOffsetMillis)
    }

    @Test
    fun `lantai rekaman lokal plus satu mengalahkan jam yang tertinggal`() {
        // Versi lokal bercap 2_000_000 (mis. hasil pull dari perangkat berjam maju); jam kita 1_000_000.
        assertEquals(2_000_001L, clock.stamp(recordChangedAtMillis = 2_000_000L))
    }

    @Test
    fun `lantai tidak dipakai bila jam sudah lebih besar`() {
        assertEquals(1_000_000L, clock.stamp(recordChangedAtMillis = 10L))
    }

    @Test
    fun `lantai persis sama dengan jam menghasilkan satu lebih besar`() {
        assertEquals(1_000_001L, clock.stamp(recordChangedAtMillis = 1_000_000L))
    }

    @Test
    fun `jam mundur tidak menurunkan cap`() {
        val first = clock.stamp()
        now -= 600_000 // pengguna memundurkan jam 10 menit
        val second = clock.stamp()
        assertEquals(first, second)
        now += 700_000
        assertTrue(clock.stamp() > first)
    }

    @Test
    fun `offset yang diturunkan tidak menurunkan cap`() {
        clock.setServerOffset(60_000)
        val high = clock.stamp()
        clock.setServerOffset(-60_000)
        assertEquals(high, clock.stamp())
    }

    @Test
    fun `jam jauh ke depan mengunci cap tinggi tetapi edit rekaman tetap naik satu per satu`() {
        now = 4_000_000_000_000L // tahun ~2096
        val future = clock.stamp()
        now = 1_000_000L // jam dikoreksi kembali
        assertEquals(future, clock.stamp())
        // Edit berturut-turut pada rekaman yang sama selalu strictly naik walau jam pulih.
        val a = clock.stamp(recordChangedAtMillis = future)
        val b = clock.stamp(recordChangedAtMillis = a)
        assertEquals(future + 1, a)
        assertEquals(future + 2, b)
    }

    @Test
    fun `luapan Long tidak membalik tanda`() {
        now = Long.MAX_VALUE - 1
        clock.setServerOffset(10)
        assertEquals(Long.MAX_VALUE, clock.stamp())
        assertEquals(Long.MAX_VALUE, clock.stamp(recordChangedAtMillis = Long.MAX_VALUE))

        val negative = SyncClock { Long.MIN_VALUE + 1 }
        negative.setServerOffset(-10)
        assertEquals(Long.MIN_VALUE, negative.stamp())
    }

    @Test
    fun `urutan acak jam dan lantai tidak pernah menghasilkan cap yang turun`() {
        val seed = 20261025L
        val random = Random(seed)
        var previous = Long.MIN_VALUE
        repeat(2_000) {
            now += random.nextLong(-5_000, 5_000)
            if (random.nextInt(10) == 0) clock.setServerOffset(random.nextLong(-100_000, 100_000))
            val floor = if (random.nextBoolean()) null else random.nextLong(0, 2_000_000)
            val stamp = clock.stamp(floor)
            assertTrue(stamp >= previous, "seed=$seed cap turun: $stamp < $previous")
            if (floor != null) assertTrue(stamp > floor, "seed=$seed cap $stamp tidak melewati lantai $floor")
            previous = stamp
        }
    }
}
