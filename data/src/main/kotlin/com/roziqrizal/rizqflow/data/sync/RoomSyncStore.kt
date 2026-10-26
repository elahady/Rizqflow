package com.roziqrizal.rizqflow.data.sync

import androidx.room.withTransaction
import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.data.db.SharedRoomEntity
import com.roziqrizal.rizqflow.data.db.SyncCursorEntity
import com.roziqrizal.rizqflow.data.db.SyncIssueEntity
import com.roziqrizal.rizqflow.data.db.SyncRecordEntity
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.sync.ApplyDecision
import com.roziqrizal.rizqflow.domain.sync.IncomingDecision
import com.roziqrizal.rizqflow.domain.sync.IncomingVersion
import com.roziqrizal.rizqflow.domain.sync.SentVersion
import com.roziqrizal.rizqflow.domain.sync.SyncApplyAbortedException
import com.roziqrizal.rizqflow.domain.sync.SharedRoomInfo
import com.roziqrizal.rizqflow.domain.sync.SharedRoomRole
import com.roziqrizal.rizqflow.domain.sync.SharedRoomStatus
import com.roziqrizal.rizqflow.domain.sync.SyncClock
import com.roziqrizal.rizqflow.domain.sync.SyncIssue
import com.roziqrizal.rizqflow.domain.sync.SyncIssueKind
import com.roziqrizal.rizqflow.domain.sync.SyncOrdering
import com.roziqrizal.rizqflow.domain.sync.SyncRecordMeta
import com.roziqrizal.rizqflow.domain.sync.SyncRecordType
import com.roziqrizal.rizqflow.domain.sync.SyncScope
import com.roziqrizal.rizqflow.domain.sync.SyncStore
import com.roziqrizal.rizqflow.domain.sync.isSyncApplying
import com.roziqrizal.rizqflow.domain.sync.withSyncApplying

/**
 * Implementasi Room dari [SyncStore] (Tahap 1 sync-desain.md). Semua operasi majemuk berjalan dalam
 * satu transaksi; [applyIncoming] menjalankan penerapan data di transaksi yang sama dengan
 * pembaruan meta, jadi gagal di tengah tidak meninggalkan setengah.
 */
class RoomSyncStore(
    private val db: RizqflowDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : SyncStore {
    private val dao get() = db.sync()

    override suspend fun meta(scope: SyncScope, type: SyncRecordType, recordId: String): SyncRecordMeta? =
        dao.find(scope.wire, type.wire, recordId)?.toMeta()

    override suspend fun markDirty(scope: SyncScope, type: SyncRecordType, recordId: String, deleted: Boolean, clock: SyncClock): Long? {
        if (isSyncApplying()) return null
        return db.withTransaction { dao.markDirty(scope, type, recordId, deleted, clock) }
    }

    override suspend fun dirtyRecords(scope: SyncScope, limit: Int): List<SyncRecordMeta> =
        dao.dirty(scope.wire, limit).mapNotNull { it.toMeta() }

    override suspend fun dirtyRecords(scope: SyncScope, type: SyncRecordType, limit: Int): List<SyncRecordMeta> =
        dao.dirtyOfType(scope.wire, type.wire, limit).mapNotNull { it.toMeta() }

    override suspend fun dirtyCount(scope: SyncScope): Int = dao.dirtyCount(scope.wire)

    override suspend fun markPushed(scope: SyncScope, sent: List<SentVersion>): Int = db.withTransaction {
        var cleared = 0
        for (version in sent) {
            val before = dao.find(scope.wire, version.type.wire, version.recordId)
            dao.markPushed(scope.wire, version.type.wire, version.recordId, version.changedAtMillis)
            if (before != null && before.dirty && before.changedAt == version.changedAtMillis) cleared++
        }
        cleared
    }

    override suspend fun applyIncoming(
        scope: SyncScope,
        type: SyncRecordType,
        recordId: String,
        incoming: IncomingVersion,
        localDeviceId: String,
        applyBlock: suspend () -> Unit,
    ): ApplyDecision {
        val decision = applyInTransaction(scope, type, recordId, incoming, localDeviceId, applyBlock)
        if (decision != ApplyDecision.IGNORED) verifyCommitted(scope, type, recordId, incoming, localDeviceId)
        return decision
    }

    /**
     * Transaksi bersarang yang gagal (dan galatnya ditelan di [applyBlock]) membuat transaksi luar dibatalkan diam-diam
     * sementara kode di dalamnya terus berjalan dan mengembalikan APPLIED. Setelah komit, meta rekaman harus sudah
     * setidaknya sebaru versi masuk (boleh lebih baru bila ada penulis lain); bila tidak, penerapan dibatalkan.
     * Di dalam transaksi pemanggil lain pemeriksaan ini tidak berarti (belum komit), jadi dilewati.
     */
    private suspend fun verifyCommitted(scope: SyncScope, type: SyncRecordType, recordId: String, incoming: IncomingVersion, localDeviceId: String) {
        if (db.inTransaction()) return
        val stored = dao.find(scope.wire, type.wire, recordId)
        val committed = stored != null &&
            SyncOrdering.compare(stored.changedAt, stored.deleted, stored.deviceId ?: localDeviceId, incoming.changedAtMillis, incoming.deleted, incoming.deviceId) >= 0
        if (!committed) {
            throw SyncApplyAbortedException("Penerapan ${type.wire} dibatalkan: transaksi ditandai gagal (galat repositori ditelan di penangan?)")
        }
    }

    private suspend fun applyInTransaction(
        scope: SyncScope,
        type: SyncRecordType,
        recordId: String,
        incoming: IncomingVersion,
        localDeviceId: String,
        applyBlock: suspend () -> Unit,
    ): ApplyDecision = db.withTransaction {
        val localEntity = dao.find(scope.wire, type.wire, recordId)
        val local = localEntity?.toMeta()
        val decision = SyncOrdering.decide(local, incoming, localDeviceId)
        if (decision == IncomingDecision.IGNORE) return@withTransaction ApplyDecision.IGNORED

        if (decision == IncomingDecision.APPLY_OVER_DIRTY_LOCAL && local != null) {
            dao.insertIssue(
                SyncIssueEntity(
                    scope = scope.wire,
                    recordType = type.wire,
                    recordId = recordId,
                    serverId = incoming.serverId,
                    kind = SyncIssueKind.OVERWRITTEN.name,
                    detail = "Edit lokal (changed_at=${local.changedAtMillis}, deleted=${local.deleted}) kalah dari versi masuk " +
                        "(changed_at=${incoming.changedAtMillis}, deleted=${incoming.deleted}, device=${incoming.deviceId})",
                    rawRow = null,
                    attempts = 0,
                    createdAt = nowMillis(),
                ),
            )
        }

        withSyncApplying { applyBlock() }

        dao.upsert(
            SyncRecordEntity(
                scope = scope.wire,
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

    override suspend fun cursor(scope: SyncScope): Long = dao.cursor(scope.wire) ?: 0L

    override suspend fun setCursor(scope: SyncScope, afterId: Long) = dao.upsertCursor(SyncCursorEntity(scope.wire, afterId))

    override suspend fun recordIssue(issue: SyncIssue): Long = dao.insertIssue(
        SyncIssueEntity(
            scope = issue.scope.wire,
            recordType = issue.recordType,
            recordId = issue.recordId,
            serverId = issue.serverId,
            kind = issue.kind.name,
            detail = issue.detail,
            rawRow = issue.rawRow,
            attempts = issue.attempts,
            createdAt = issue.createdAtMillis,
        ),
    )

    override suspend fun resolveIssue(id: Long) = dao.deleteIssue(id)

    override suspend fun incrementIssueAttempts(id: Long) = dao.incrementAttempts(id)

    override suspend fun issues(scope: SyncScope?): List<SyncIssue> =
        (if (scope == null) dao.allIssues() else dao.issuesOf(scope.wire)).mapNotNull { it.toIssue() }

    override suspend fun pruneIssues(nowMillis: Long, maxRows: Int, maxAgeMillis: Long): Int = db.withTransaction {
        dao.deleteIssuesOlderThan(nowMillis - maxAgeMillis) + dao.trimIssues(maxRows)
    }

    override suspend fun sharedRoom(roomId: RoomId): SharedRoomInfo? = dao.sharedRoom(roomId.value)?.toInfo()

    override suspend fun sharedRooms(): List<SharedRoomInfo> = dao.sharedRooms().mapNotNull { it.toInfo() }

    override suspend fun saveSharedRoom(info: SharedRoomInfo) = dao.upsertSharedRoom(
        SharedRoomEntity(info.roomId.value, info.role.name, info.status.name, info.joinedAtMillis),
    )
}

private fun SyncRecordEntity.toMeta(): SyncRecordMeta? {
    val parsedScope = SyncScope.parse(scope) ?: return null
    val parsedType = SyncRecordType.fromWire(recordType) ?: return null
    return SyncRecordMeta(parsedScope, parsedType, recordId, changedAt, deleted, dirty, pushedChangedAt, author, deviceId)
}

private fun SyncIssueEntity.toIssue(): SyncIssue? {
    val parsedScope = SyncScope.parse(scope) ?: return null
    // Jenis tak dikenal (mis. dari versi aplikasi lebih baru) dilewati, bukan membuat daftar masalah gagal dibaca.
    val parsedKind = SyncIssueKind.entries.firstOrNull { it.name == kind } ?: return null
    return SyncIssue(parsedScope, recordType, recordId, parsedKind, serverId, detail, rawRow, attempts, createdAt, id)
}

private fun SharedRoomEntity.toInfo(): SharedRoomInfo? {
    val parsedRole = SharedRoomRole.entries.firstOrNull { it.name == role } ?: return null
    val parsedStatus = SharedRoomStatus.entries.firstOrNull { it.name == status } ?: return null
    return SharedRoomInfo(RoomId(roomId), parsedRole, parsedStatus, joinedAt)
}
