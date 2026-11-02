package com.roziqrizal.rizqflow.data.sync

import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.data.db.SyncRecordEntity
import com.roziqrizal.rizqflow.data.db.withTransaction
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.sync.ApplyDecision
import com.roziqrizal.rizqflow.domain.sync.IncomingDecision
import com.roziqrizal.rizqflow.domain.sync.IncomingVersion
import com.roziqrizal.rizqflow.domain.sync.SharedRecordMeta
import com.roziqrizal.rizqflow.domain.sync.SharedRecordStore
import com.roziqrizal.rizqflow.domain.sync.SharedRecordType
import com.roziqrizal.rizqflow.domain.sync.SharedSentVersion
import com.roziqrizal.rizqflow.domain.sync.SyncClock
import com.roziqrizal.rizqflow.domain.sync.SyncOrdering
import com.roziqrizal.rizqflow.domain.sync.SyncScope

/**
 * Implementasi [SharedRecordStore] (peran-anggota.md E.2/H langkah 8): memakai `SyncDao`/`sync_record` yang SAMA
 * dengan [RoomSyncStore] cakupan pribadi — lihat KDoc [SharedRecordStore] soal kenapa dua antarmuka Kotlin terpisah
 * untuk satu tabel yang sama. [markDirtyWire] (sudah ada sejak `SharedRoomRecorder`/`shared_expense`) dipakai lagi
 * di sini, bukan ditulis ulang.
 */
class RoomSharedRecordStore(private val db: RizqflowDatabase) : SharedRecordStore {
    private val dao get() = db.sync()
    private fun scopeOf(roomId: RoomId) = SyncScope.SharedRoom(roomId).wire

    override suspend fun meta(roomId: RoomId, type: SharedRecordType, recordId: String): SharedRecordMeta? =
        dao.find(scopeOf(roomId), type.wire, recordId)?.toSharedMeta()

    override suspend fun markDirty(roomId: RoomId, type: SharedRecordType, recordId: String, deleted: Boolean, clock: SyncClock): Long =
        db.withTransaction { dao.markDirtyWire(scopeOf(roomId), type.wire, recordId, deleted, clock) }

    override suspend fun dirtyRecords(roomId: RoomId, type: SharedRecordType, limit: Int): List<SharedRecordMeta> =
        dao.dirtyOfType(scopeOf(roomId), type.wire, limit).map { it.toSharedMeta() }

    override suspend fun markPushed(roomId: RoomId, type: SharedRecordType, sent: List<SharedSentVersion>): Int = db.withTransaction {
        val scope = scopeOf(roomId)
        var cleared = 0
        for (version in sent) {
            val before = dao.find(scope, type.wire, version.recordId)
            dao.markPushed(scope, type.wire, version.recordId, version.changedAtMillis)
            if (before != null && before.dirty && before.changedAt == version.changedAtMillis) cleared++
        }
        cleared
    }

    override suspend fun applyIncoming(
        roomId: RoomId,
        type: SharedRecordType,
        recordId: String,
        incoming: IncomingVersion,
        localDeviceId: String,
        applyBlock: suspend () -> Unit,
    ): ApplyDecision = db.withTransaction {
        val scope = scopeOf(roomId)
        val localEntity = dao.find(scope, type.wire, recordId)
        val local = localEntity?.toSharedMeta()
        val decision = SyncOrdering.decide(local, incoming, localDeviceId)
        if (decision == IncomingDecision.IGNORE) return@withTransaction ApplyDecision.IGNORED

        applyBlock()

        dao.upsert(
            SyncRecordEntity(
                scope = scope,
                recordType = type.wire,
                recordId = recordId,
                changedAt = incoming.changedAtMillis,
                deleted = incoming.deleted,
                dirty = false,
                pushedChangedAt = incoming.changedAtMillis,
                author = incoming.author ?: localEntity?.author,
                deviceId = incoming.deviceId,
            ),
        )
        if (decision == IncomingDecision.APPLY_OVER_DIRTY_LOCAL) ApplyDecision.APPLIED_OVER_LOCAL_EDIT else ApplyDecision.APPLIED
    }
}

private fun SyncRecordEntity.toSharedMeta() = SharedRecordMeta(recordId, changedAt, deleted, dirty, author, deviceId)
