package com.roziqrizal.rizqflow.data.sync

import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.data.db.TraderProfileEntity
import com.roziqrizal.rizqflow.domain.sync.DecodedRecord
import com.roziqrizal.rizqflow.domain.sync.OutgoingRecord
import com.roziqrizal.rizqflow.domain.sync.SyncRecordHandler
import com.roziqrizal.rizqflow.domain.sync.SyncRecordType
import com.roziqrizal.rizqflow.domain.sync.SyncScope
import com.roziqrizal.rizqflow.domain.sync.SyncStore

private const val VERSION = 1

/**
 * Handler [SyncRecordType.TRADER_PROFILE] (Tahap 4 sync-desain.md, peran Trader dari Tahap 7/Pro):
 * record id = id ruang (`room_id` kunci utama). Merujuk ruangnya saja. Tombstone datang dari
 * [com.roziqrizal.rizqflow.data.repo.LocalRoleRepository.remove].
 */
class TraderProfileSyncHandler(private val db: RizqflowDatabase, private val store: SyncStore) : SyncRecordHandler {
    override val type: SyncRecordType = SyncRecordType.TRADER_PROFILE

    override suspend fun collectDirty(scope: SyncScope): List<OutgoingRecord> {
        if (scope != SyncScope.Personal) return emptyList()
        val metas = store.dirtyRecords(scope, type)
        return metas.mapNotNull { meta ->
            val data = if (meta.deleted) {
                JsonObjects.empty
            } else {
                val entity = db.roles().trader(meta.recordId) ?: return@mapNotNull null
                TraderProfilePayloadDto(capitalMinor = entity.capital, riskBp = entity.riskBp).toJsonObject(TraderProfilePayloadDto.serializer())
            }
            OutgoingRecord(meta.recordId, VERSION, meta.changedAtMillis, meta.deleted, data)
        }
    }

    override suspend fun missingReferences(scope: SyncScope, decoded: DecodedRecord): List<String> {
        if (decoded.data.isEmpty()) return emptyList()
        return if (db.rooms().find(decoded.id) == null) listOf("room:${decoded.id}") else emptyList()
    }

    override suspend fun apply(scope: SyncScope, decoded: DecodedRecord) {
        if (decoded.data.isEmpty()) {
            db.roles().deleteTrader(decoded.id)
            return
        }
        val dto = decoded.data.decodeAs(TraderProfilePayloadDto.serializer())
        db.roles().upsertTrader(TraderProfileEntity(roomId = decoded.id, capital = dto.capitalMinor, riskBp = dto.riskBp))
    }
}
