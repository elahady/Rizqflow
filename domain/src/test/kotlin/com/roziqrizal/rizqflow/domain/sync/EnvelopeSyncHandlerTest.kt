package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.auth.runSuspend
import com.roziqrizal.rizqflow.domain.ledger.EnvelopeEntry
import com.roziqrizal.rizqflow.domain.ledger.EnvelopeRepository
import com.roziqrizal.rizqflow.domain.model.RoomId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeSharedRecordStore : SharedRecordStore {
    private data class Key(val roomId: String, val type: String, val recordId: String)
    private val records = mutableMapOf<Key, SharedRecordMeta>()

    override suspend fun meta(roomId: RoomId, type: SharedRecordType, recordId: String): SharedRecordMeta? =
        records[Key(roomId.value, type.wire, recordId)]

    override suspend fun markDirty(roomId: RoomId, type: SharedRecordType, recordId: String, deleted: Boolean, clock: SyncClock): Long {
        val key = Key(roomId.value, type.wire, recordId)
        val existing = records[key]
        val stamp = clock.stamp(existing?.changedAtMillis)
        records[key] = SharedRecordMeta(recordId, stamp, deleted, dirty = true, author = existing?.author, deviceId = null)
        return stamp
    }

    override suspend fun dirtyRecords(roomId: RoomId, type: SharedRecordType, limit: Int): List<SharedRecordMeta> =
        records.filterKeys { it.roomId == roomId.value && it.type == type.wire }.values
            .filter { it.dirty }
            .sortedBy { it.changedAtMillis }
            .take(limit)

    override suspend fun markPushed(roomId: RoomId, type: SharedRecordType, sent: List<SharedSentVersion>): Int {
        var cleared = 0
        for (version in sent) {
            val key = Key(roomId.value, type.wire, version.recordId)
            val before = records[key] ?: continue
            if (before.dirty && before.changedAtMillis == version.changedAtMillis) {
                records[key] = before.copy(dirty = false)
                cleared++
            }
        }
        return cleared
    }

    override suspend fun applyIncoming(
        roomId: RoomId,
        type: SharedRecordType,
        recordId: String,
        incoming: IncomingVersion,
        localDeviceId: String,
        applyBlock: suspend () -> Unit,
    ): ApplyDecision {
        val key = Key(roomId.value, type.wire, recordId)
        val local = records[key]
        val decision = SyncOrdering.decide(local, incoming, localDeviceId)
        if (decision == IncomingDecision.IGNORE) return ApplyDecision.IGNORED
        applyBlock()
        records[key] = SharedRecordMeta(
            recordId = recordId,
            changedAtMillis = incoming.changedAtMillis,
            deleted = incoming.deleted,
            dirty = false,
            author = incoming.author ?: local?.author,
            deviceId = incoming.deviceId,
        )
        return if (decision == IncomingDecision.APPLY_OVER_DIRTY_LOCAL) ApplyDecision.APPLIED_OVER_LOCAL_EDIT else ApplyDecision.APPLIED
    }
}

private class FakeEnvelopeRepository : EnvelopeRepository {
    private val table = mutableMapOf<Triple<String, String, Int>, EnvelopeEntry>()

    override suspend fun find(roomId: RoomId, memberId: String, fromMonth: Int): EnvelopeEntry? = table[Triple(roomId.value, memberId, fromMonth)]

    override suspend fun entries(roomId: RoomId): List<EnvelopeEntry> = table.filterKeys { it.first == roomId.value }.values.toList()

    override suspend fun upsert(roomId: RoomId, entry: EnvelopeEntry) {
        table[Triple(roomId.value, entry.memberId, entry.fromMonth)] = entry
    }
}

private class FakeSharedMemberRepository : SharedMemberRepository {
    private val table = mutableMapOf<Pair<String, String>, SharedMemberCache>()

    fun put(roomId: RoomId, cache: SharedMemberCache) {
        table[roomId.value to cache.memberId] = cache
    }

    override suspend fun replaceAll(roomId: RoomId, members: List<SharedMemberCache>) {
        members.forEach { put(roomId, it) }
    }

    override suspend fun find(roomId: RoomId, memberId: String): SharedMemberCache? = table[roomId.value to memberId]

    override suspend fun all(roomId: RoomId): List<SharedMemberCache> = table.filterKeys { it.first == roomId.value }.values.toList()
}

/** Tes [EnvelopeSyncHandler] (peran-anggota.md H langkah 8), pola sama dengan tes [SharedExpenseSyncHandler]. */
class EnvelopeSyncHandlerTest {
    private val room = RoomId("room-keluarga")
    private val owner = "owner-abc123"
    private val member = "member-def456"

    private fun handler(store: FakeSharedRecordStore, envelopes: FakeEnvelopeRepository, members: FakeSharedMemberRepository, issues: SyncStore? = null) =
        EnvelopeSyncHandler(store, envelopes, members, issues, nowMillis = { 1_000L })

    private fun decodedOf(memberId: String, fromMonth: Int, amount: Long): DecodedRecord {
        val recordId = EnvelopeRecordId.format(room, memberId, fromMonth)
        return DecodedRecord(SharedRecordType.ENVELOPE.wire, recordId, EnvelopePayload.VERSION, EnvelopePayload.encode(room, memberId, fromMonth, amount))
    }

    @Test
    fun `apply menulis amplop ketika author Pemilik aktif`() = runSuspend {
        val store = FakeSharedRecordStore()
        val envelopes = FakeEnvelopeRepository()
        val members = FakeSharedMemberRepository().apply {
            put(room, SharedMemberCache(owner, "Ayu", SharedRoomRole.OWNER, SharedRoomStatus.ACTIVE, isMe = false, updatedAtMillis = 1L))
        }
        val handler = handler(store, envelopes, members)

        handler.apply(room, decodedOf(member, 202611, 4_000_000), changedAtMillis = 10L, author = owner)

        assertEquals(EnvelopeEntry(member, 202611, 4_000_000), envelopes.find(room, member, 202611))
    }

    @Test
    fun `apply menolak dan mencatat UNAUTHORIZED_AUTHOR ketika author bukan pemilik`() = runSuspend {
        val store = FakeSharedRecordStore()
        val envelopes = FakeEnvelopeRepository()
        val members = FakeSharedMemberRepository().apply {
            put(room, SharedMemberCache(member, "Dimas", SharedRoomRole.MEMBER, SharedRoomStatus.ACTIVE, isMe = false, updatedAtMillis = 1L))
        }
        val issues = IssueBookSyncStore()
        val handler = handler(store, envelopes, members, issues)

        handler.apply(room, decodedOf(owner, 202611, 4_000_000), changedAtMillis = 10L, author = member)

        assertNull(envelopes.find(room, owner, 202611))
        assertEquals(1, issues.book.size)
        assertEquals(SyncIssueKind.UNAUTHORIZED_AUTHOR, issues.book.single().kind)
    }

    @Test
    fun `apply menolak ketika author belum tercache sama sekali`() = runSuspend {
        val store = FakeSharedRecordStore()
        val envelopes = FakeEnvelopeRepository()
        val members = FakeSharedMemberRepository() // kosong: author tidak dikenal
        val issues = IssueBookSyncStore()
        val handler = handler(store, envelopes, members, issues)

        handler.apply(room, decodedOf(member, 202611, 1_000_000), changedAtMillis = 10L, author = "entah-siapa")

        assertNull(envelopes.find(room, member, 202611))
        assertEquals(SyncIssueKind.UNAUTHORIZED_AUTHOR, issues.book.single().kind)
    }

    @Test
    fun `apply menolak ketika pemilik sudah REMOVED`() = runSuspend {
        val store = FakeSharedRecordStore()
        val envelopes = FakeEnvelopeRepository()
        val members = FakeSharedMemberRepository().apply {
            put(room, SharedMemberCache(owner, "Ayu (keluar)", SharedRoomRole.OWNER, SharedRoomStatus.REMOVED, isMe = false, updatedAtMillis = 1L))
        }
        val handler = handler(store, envelopes, members)

        handler.apply(room, decodedOf(member, 202611, 1_000_000), changedAtMillis = 10L, author = owner)

        assertNull(envelopes.find(room, member, 202611))
    }

    @Test
    fun `apply versi lebih lama diabaikan (LWW)`() = runSuspend {
        val store = FakeSharedRecordStore()
        val envelopes = FakeEnvelopeRepository()
        val members = FakeSharedMemberRepository().apply {
            put(room, SharedMemberCache(owner, "Ayu", SharedRoomRole.OWNER, SharedRoomStatus.ACTIVE, isMe = false, updatedAtMillis = 1L))
        }
        val handler = handler(store, envelopes, members)

        handler.apply(room, decodedOf(member, 202611, 4_000_000), changedAtMillis = 100L, author = owner)
        handler.apply(room, decodedOf(member, 202611, 999), changedAtMillis = 50L, author = owner) // lebih lama, harus diabaikan

        assertEquals(4_000_000, envelopes.find(room, member, 202611)?.amount)
    }

    @Test
    fun `collectDirty membaca rekaman kotor dan membangun payload dari EnvelopeRepository`() = runSuspend {
        val store = FakeSharedRecordStore()
        val envelopes = FakeEnvelopeRepository()
        val members = FakeSharedMemberRepository()
        val handler = handler(store, envelopes, members)
        val clock = SyncClock()

        envelopes.upsert(room, EnvelopeEntry(member, 202611, 2_500_000))
        val recordId = EnvelopeRecordId.format(room, member, 202611)
        store.markDirty(room, SharedRecordType.ENVELOPE, recordId, deleted = false, clock = clock)

        val dirty = handler.collectDirty(room)
        assertEquals(1, dirty.size)
        assertEquals(recordId, dirty.single().recordId)
        assertEquals(EnvelopePayload.encode(room, member, 202611, 2_500_000), dirty.single().data)
    }

    @Test
    fun `markPushed membersihkan status kotor hanya yang masih sama`() = runSuspend {
        val store = FakeSharedRecordStore()
        val envelopes = FakeEnvelopeRepository()
        val members = FakeSharedMemberRepository()
        val handler = handler(store, envelopes, members)
        val clock = SyncClock()

        envelopes.upsert(room, EnvelopeEntry(member, 202611, 1_000_000))
        val recordId = EnvelopeRecordId.format(room, member, 202611)
        store.markDirty(room, SharedRecordType.ENVELOPE, recordId, deleted = false, clock = clock)
        val dirty = handler.collectDirty(room)

        handler.markPushed(room, dirty)

        assertTrue(store.dirtyRecords(room, SharedRecordType.ENVELOPE).isEmpty())
    }
}
