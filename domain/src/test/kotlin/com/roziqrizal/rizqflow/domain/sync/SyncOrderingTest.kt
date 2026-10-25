package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.model.RoomId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncOrderingTest {
    private fun local(changedAt: Long, deleted: Boolean = false, dirty: Boolean = false, deviceId: String? = null) = SyncRecordMeta(
        scope = SyncScope.Personal,
        type = SyncRecordType.TRANSACTION,
        recordId = "t1",
        changedAtMillis = changedAt,
        deleted = deleted,
        dirty = dirty,
        pushedChangedAtMillis = null,
        author = null,
        deviceId = deviceId,
    )

    private fun incoming(changedAt: Long, deleted: Boolean = false, deviceId: String = "dev-b") =
        IncomingVersion(changedAtMillis = changedAt, deleted = deleted, deviceId = deviceId)

    private fun decide(local: SyncRecordMeta?, incoming: IncomingVersion, me: String = "dev-a") =
        SyncOrdering.decide(local, incoming, me)

    // ---- aturan urutan total

    @Test
    fun `tanpa versi lokal versi masuk diterapkan`() {
        assertEquals(IncomingDecision.APPLY, decide(null, incoming(1)))
    }

    @Test
    fun `cap lebih baru menang dan cap lebih lama kalah`() {
        assertEquals(IncomingDecision.APPLY, decide(local(100), incoming(101)))
        assertEquals(IncomingDecision.IGNORE, decide(local(101), incoming(100)))
    }

    @Test
    fun `tombstone menang pada cap yang sama`() {
        assertEquals(IncomingDecision.APPLY, decide(local(100, deleted = false), incoming(100, deleted = true)))
        assertEquals(IncomingDecision.IGNORE, decide(local(100, deleted = true), incoming(100, deleted = false)))
    }

    @Test
    fun `pada cap dan status hapus sama deviceId leksikografis terbesar menang`() {
        assertEquals(IncomingDecision.APPLY, decide(local(100, deviceId = "dev-a"), incoming(100, deviceId = "dev-b")))
        assertEquals(IncomingDecision.IGNORE, decide(local(100, deviceId = "dev-b"), incoming(100, deviceId = "dev-a")))
        // Versi lokal tanpa deviceId dianggap milik perangkat ini.
        assertEquals(IncomingDecision.APPLY, decide(local(100, deviceId = null), incoming(100, deviceId = "dev-z"), me = "dev-a"))
        assertEquals(IncomingDecision.IGNORE, decide(local(100, deviceId = null), incoming(100, deviceId = "dev-0"), me = "dev-a"))
    }

    @Test
    fun `echo versi sendiri dan duplikat persis diabaikan`() {
        assertEquals(IncomingDecision.IGNORE, decide(local(100, deviceId = null), incoming(100, deviceId = "dev-a"), me = "dev-a"))
        assertEquals(IncomingDecision.IGNORE, decide(local(100, deviceId = "dev-b"), incoming(100, deviceId = "dev-b")))
        assertEquals(IncomingDecision.IGNORE, decide(local(100, deleted = true, deviceId = "dev-b"), incoming(100, deleted = true, deviceId = "dev-b")))
    }

    @Test
    fun `versi masuk yang menang atas edit lokal kotor ditandai sebagai menimpa edit lokal`() {
        assertEquals(IncomingDecision.APPLY_OVER_DIRTY_LOCAL, decide(local(100, dirty = true), incoming(200)))
        assertEquals(IncomingDecision.APPLY, decide(local(100, dirty = false), incoming(200)))
    }

    @Test
    fun `edit lokal kotor yang lebih baru tidak ditimpa dan tetap menunggu dikirim`() {
        assertEquals(IncomingDecision.IGNORE, decide(local(300, dirty = true), incoming(200)))
    }

    @Test
    fun `urutan acak versi menghasilkan pemenang yang sama untuk semua urutan kedatangan`() {
        val seed = 7L
        val random = Random(seed)
        repeat(300) { round ->
            val versions = List(random.nextInt(2, 7)) {
                IncomingVersion(
                    changedAtMillis = random.nextLong(0, 4),
                    deleted = random.nextBoolean(),
                    deviceId = "dev-${random.nextInt(3)}",
                )
            }
            fun winner(order: List<IncomingVersion>): IncomingVersion? {
                var current: SyncRecordMeta? = null
                var currentVersion: IncomingVersion? = null
                for (v in order) {
                    if (SyncOrdering.decide(current, v, "local") != IncomingDecision.IGNORE) {
                        current = SyncRecordMeta(SyncScope.Personal, SyncRecordType.TRANSACTION, "t", v.changedAtMillis, v.deleted, false, null, null, v.deviceId)
                        currentVersion = v
                    }
                }
                return currentVersion
            }
            val expected = winner(versions)
            repeat(5) {
                val shuffled = versions.shuffled(random)
                val actual = winner(shuffled)
                assertNotNull(actual)
                assertEquals(
                    Triple(expected!!.changedAtMillis, expected.deleted, expected.deviceId),
                    Triple(actual.changedAtMillis, actual.deleted, actual.deviceId),
                    "seed=$seed putaran=$round",
                )
            }
        }
    }

    @Test
    fun `compare membandingkan cap lalu tombstone lalu deviceId`() {
        assertTrue(SyncOrdering.compare(2, false, "a", 1, true, "z") > 0)
        assertTrue(SyncOrdering.compare(1, true, "a", 1, false, "z") > 0)
        assertTrue(SyncOrdering.compare(1, false, "b", 1, false, "a") > 0)
        assertEquals(0, SyncOrdering.compare(1, false, "a", 1, false, "a"))
    }

    // ---- urutan penerapan satu halaman pull

    @Test
    fun `urutan penerapan memakai peringkat tipe induk sebelum anak`() {
        val ranks = SyncRecordType.entries.map { it.applyRank }
        assertEquals(ranks.sorted(), ranks, "enum harus terurut menurut peringkat")
        assertEquals(ranks.size, ranks.toSet().size, "peringkat unik")
        fun rank(t: SyncRecordType) = t.applyRank
        // Urutan bagian B: room, account, category, allocation_config, transaction, debt, debt_payment, bill, recurring.
        val expected = listOf(
            SyncRecordType.ROOM, SyncRecordType.ACCOUNT, SyncRecordType.CATEGORY, SyncRecordType.ALLOCATION_CONFIG,
            SyncRecordType.TRANSACTION, SyncRecordType.DEBT, SyncRecordType.DEBT_PAYMENT, SyncRecordType.BILL,
            SyncRecordType.RECURRING_RULE,
        )
        assertEquals(expected, expected.shuffled(Random(1)).sortedBy(::rank))
        // zakat_* sebelum trader/dca, dan favorit/day_check terakhir.
        assertTrue(rank(SyncRecordType.ZAKAT_PROFILE) < rank(SyncRecordType.WEALTH_ITEM))
        assertTrue(rank(SyncRecordType.ZAKAT_PAYMENT) < rank(SyncRecordType.TRADER_PROFILE))
        assertTrue(rank(SyncRecordType.DCA_PLAN) < rank(SyncRecordType.QUICK_FAVORITE))
        assertTrue(rank(SyncRecordType.QUICK_FAVORITE) < rank(SyncRecordType.DAY_CHECK))
        assertTrue(rank(SyncRecordType.ZAKAT_PAYMENT) > rank(SyncRecordType.TRANSACTION), "zakat_payment merujuk transaksi")
    }

    @Test
    fun `halaman pull diurutkan peringkat lalu live sebelum tombstone lalu cap lalu id server`() {
        val page = listOf(
            PulledRef(SyncRecordType.TRANSACTION, 5, deleted = false, serverId = 9),
            PulledRef(SyncRecordType.ROOM, 50, deleted = true, serverId = 1),
            PulledRef(SyncRecordType.ROOM, 60, deleted = false, serverId = 2),
            PulledRef(SyncRecordType.TRANSACTION, 5, deleted = false, serverId = 3),
            PulledRef(SyncRecordType.TRANSACTION, 1, deleted = false, serverId = 7),
            PulledRef(SyncRecordType.ACCOUNT, 1, deleted = false, serverId = 4),
        )
        val sorted = page.sortedWith(SyncApplyOrder)
        assertEquals(
            listOf(2L, 1L, 4L, 7L, 3L, 9L),
            sorted.map { it.serverId },
        )
    }

    // ---- model

    @Test
    fun `cakupan di-parse dari bentuk teksnya`() {
        assertEquals("a", SyncScope.Personal.wire)
        assertEquals("r:room-1", SyncScope.SharedRoom(RoomId("room-1")).wire)
        assertEquals(SyncScope.Personal, SyncScope.parse("a"))
        assertEquals(SyncScope.SharedRoom(RoomId("room-1")), SyncScope.parse("r:room-1"))
        assertNull(SyncScope.parse("r:"))
        assertNull(SyncScope.parse("x"))
        assertNull(SyncScope.parse("r:a|b"))
    }

    @Test
    fun `tipe rekaman punya nama kabel unik dan bisa dicari balik`() {
        val wires = SyncRecordType.entries.map { it.wire }
        assertEquals(wires.size, wires.toSet().size)
        for (type in SyncRecordType.entries) assertEquals(type, SyncRecordType.fromWire(type.wire))
        assertNull(SyncRecordType.fromWire("tidak-ada"))
    }
}
