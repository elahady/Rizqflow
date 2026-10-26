package com.roziqrizal.rizqflow.data.sync

import com.roziqrizal.rizqflow.data.db.DayCheckEntity
import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.domain.sync.DecodedRecord
import com.roziqrizal.rizqflow.domain.sync.OutgoingRecord
import com.roziqrizal.rizqflow.domain.sync.SyncRecordHandler
import com.roziqrizal.rizqflow.domain.sync.SyncRecordType
import com.roziqrizal.rizqflow.domain.sync.SyncScope
import com.roziqrizal.rizqflow.domain.sync.SyncStore

private const val VERSION = 1

/**
 * Handler [SyncRecordType.DAY_CHECK] (Tahap 4 sync-desain.md): hari yang ditandai "Tidak ada" di
 * Catat. Record id = `d:<epochDay>` ([com.roziqrizal.rizqflow.domain.sync.SyncRecordIds.dayCheck]).
 * Tidak merujuk entitas lain. Tidak ada jalur lokal yang membatalkan centang, jadi tombstone hanya
 * untuk kelengkapan.
 */
class DayCheckSyncHandler(private val db: RizqflowDatabase, private val store: SyncStore) : SyncRecordHandler {
    override val type: SyncRecordType = SyncRecordType.DAY_CHECK

    override suspend fun collectDirty(scope: SyncScope): List<OutgoingRecord> {
        if (scope != SyncScope.Personal) return emptyList()
        val metas = store.dirtyRecords(scope, type)
        return metas.mapNotNull { meta ->
            val data = if (meta.deleted) {
                JsonObjects.empty
            } else {
                val day = recordDay(meta.recordId) ?: return@mapNotNull null
                if (db.settings().isDayChecked(day) == 0) return@mapNotNull null
                DayCheckPayloadDto(day = day).toJsonObject(DayCheckPayloadDto.serializer())
            }
            OutgoingRecord(meta.recordId, VERSION, meta.changedAtMillis, meta.deleted, data)
        }
    }

    override suspend fun missingReferences(scope: SyncScope, decoded: DecodedRecord): List<String> = emptyList()

    override suspend fun apply(scope: SyncScope, decoded: DecodedRecord) {
        val day = recordDay(decoded.id) ?: return
        if (decoded.data.isEmpty()) {
            db.settings().unmarkDayCheckedFromSync(day)
            return
        }
        db.settings().markDayChecked(DayCheckEntity(day))
    }

    /** Hari (epochDay) dari id `d:<epochDay>`; null bila bentuk id tak dikenal (dilewati). */
    private fun recordDay(recordId: String): Long? = if (recordId.startsWith("d:")) recordId.substring(2).toLongOrNull() else null
}
