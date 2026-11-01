package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.model.RoomId

/**
 * Penyimpanan sinkron di memori untuk tes logika "Riwayat sinkron" dan "sinkron ulang penuh": hanya bagian yang dipakai
 * (kursor, masalah, ruang bersama, hitungan kotor); sisanya melempar supaya pemakaian tak sengaja ketahuan.
 */
internal class FakeSyncStore(
    var dirty: Map<SyncScope, Int> = emptyMap(),
    var issueList: List<SyncIssue> = emptyList(),
    var shared: List<SharedRoomInfo> = emptyList(),
) : SyncStore {
    val cursors = mutableMapOf<SyncScope, Long>()
    val cursorWrites = mutableListOf<Pair<SyncScope, Long>>()

    override suspend fun dirtyCount(scope: SyncScope): Int = dirty[scope] ?: 0

    override suspend fun cursor(scope: SyncScope): Long = cursors[scope] ?: 0L

    override suspend fun setCursor(scope: SyncScope, afterId: Long) {
        cursors[scope] = afterId
        cursorWrites += scope to afterId
    }

    override suspend fun issues(scope: SyncScope?): List<SyncIssue> = issueList.filter { scope == null || it.scope == scope }

    override suspend fun sharedRooms(): List<SharedRoomInfo> = shared

    override suspend fun sharedRoom(roomId: RoomId): SharedRoomInfo? = shared.firstOrNull { it.roomId == roomId }

    override suspend fun meta(scope: SyncScope, type: SyncRecordType, recordId: String): SyncRecordMeta? = error("tidak dipakai")

    override suspend fun markDirty(scope: SyncScope, type: SyncRecordType, recordId: String, deleted: Boolean, clock: SyncClock): Long? = error("tidak dipakai")

    override suspend fun dirtyRecords(scope: SyncScope, limit: Int): List<SyncRecordMeta> = error("tidak dipakai")

    override suspend fun markPushed(scope: SyncScope, sent: List<SentVersion>): Int = error("tidak dipakai")

    override suspend fun applyIncoming(
        scope: SyncScope,
        type: SyncRecordType,
        recordId: String,
        incoming: IncomingVersion,
        localDeviceId: String,
        applyBlock: suspend () -> Unit,
    ): ApplyDecision = error("tidak dipakai")

    override suspend fun recordIssue(issue: SyncIssue): Long = error("tidak dipakai")

    override suspend fun resolveIssue(id: Long) = error("tidak dipakai")

    override suspend fun incrementIssueAttempts(id: Long) = error("tidak dipakai")

    override suspend fun pruneIssues(nowMillis: Long, maxRows: Int, maxAgeMillis: Long): Int = error("tidak dipakai")

    override suspend fun saveSharedRoom(info: SharedRoomInfo) = error("tidak dipakai")
}

internal class FakeCursorStore : SyncCursorStore {
    val values = mutableMapOf<String, Long>()
    val writes = mutableListOf<Pair<String, Long>>()

    override fun read(roomId: String): Long = values[roomId] ?: 0L

    override fun save(roomId: String, cursor: Long) {
        values[roomId] = cursor
        writes += roomId to cursor
    }
}
