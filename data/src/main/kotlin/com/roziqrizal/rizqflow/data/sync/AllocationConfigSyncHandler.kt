package com.roziqrizal.rizqflow.data.sync

import com.roziqrizal.rizqflow.data.db.AllocationRuleEntity
import com.roziqrizal.rizqflow.data.db.AppSettingEntity
import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.data.repo.ALLOCATION_MODE_KEY
import com.roziqrizal.rizqflow.domain.allocation.AllocationMode
import com.roziqrizal.rizqflow.domain.sync.DecodedRecord
import com.roziqrizal.rizqflow.domain.sync.OutgoingRecord
import com.roziqrizal.rizqflow.domain.sync.SyncRecordHandler
import com.roziqrizal.rizqflow.domain.sync.SyncRecordIds
import com.roziqrizal.rizqflow.domain.sync.SyncRecordType
import com.roziqrizal.rizqflow.domain.sync.SyncScope
import com.roziqrizal.rizqflow.domain.sync.SyncStore

private const val VERSION = 1

/**
 * Handler [SyncRecordType.ALLOCATION_CONFIG] (Tahap 3 sync-desain.md): satu rekaman singleton
 * ([SyncRecordIds.ALLOCATION_CONFIG]) atomik — mode alokasi, persen (`rules`), dan batas atas
 * (`caps`) per ruang, dibungkus satu `d` (bagian A: "atomik karena total wajib 100%"). Merujuk
 * `room_id` di [AllocationConfigPayloadDto.rules]/[AllocationConfigPayloadDto.caps] (R1 bagian B).
 */
class AllocationConfigSyncHandler(private val db: RizqflowDatabase, private val store: SyncStore) : SyncRecordHandler {
    override val type: SyncRecordType = SyncRecordType.ALLOCATION_CONFIG

    override suspend fun collectDirty(scope: SyncScope): List<OutgoingRecord> {
        if (scope != SyncScope.Personal) return emptyList()
        val metas = store.dirtyRecords(scope, type)
        return metas.map { meta ->
            val data = if (meta.deleted) {
                JsonObjects.empty // tidak pernah terjadi (singleton tidak pernah dihapus), tapi aman bila suatu saat ada.
            } else {
                buildPayload().toJsonObject(AllocationConfigPayloadDto.serializer())
            }
            OutgoingRecord(meta.recordId, VERSION, meta.changedAtMillis, meta.deleted, data)
        }
    }

    private suspend fun buildPayload(): AllocationConfigPayloadDto {
        val activeRules = db.rooms().rules()
        val mode = db.settings().get(ALLOCATION_MODE_KEY) ?: AllocationMode.PERCENTAGE.name
        return AllocationConfigPayloadDto(
            mode = mode,
            rules = activeRules.map { AllocationRuleEntryDto(it.roomId, it.shareBp) },
            caps = activeRules.mapNotNull { r -> r.capAmount?.let { AllocationCapEntryDto(r.roomId, it) } },
        )
    }

    override suspend fun missingReferences(scope: SyncScope, decoded: DecodedRecord): List<String> {
        if (decoded.data.isEmpty()) return emptyList()
        val dto = decoded.data.decodeAs(AllocationConfigPayloadDto.serializer())
        val roomIds = (dto.rules.map { it.roomId } + dto.caps.map { it.roomId }).toSet()
        val missing = mutableListOf<String>()
        for (roomId in roomIds) if (db.rooms().find(roomId) == null) missing += "room:$roomId"
        return missing
    }

    override suspend fun apply(scope: SyncScope, decoded: DecodedRecord) {
        if (decoded.data.isEmpty()) return // singleton tidak pernah dihapus; aman dilewati.
        val dto = decoded.data.decodeAs(AllocationConfigPayloadDto.serializer())
        val shareByRoom = dto.rules.associate { it.roomId to it.shareBp }
        val capByRoom = dto.caps.associate { it.roomId to it.capAmountMinor }
        val roomIds = shareByRoom.keys + capByRoom.keys

        db.settings().put(AppSettingEntity(ALLOCATION_MODE_KEY, dto.mode))
        db.rooms().clearActiveRules()
        if (roomIds.isNotEmpty()) {
            db.rooms().upsertRules(
                roomIds.map { roomId ->
                    AllocationRuleEntity(roomId = roomId, shareBp = shareByRoom[roomId] ?: 0, capAmount = capByRoom[roomId])
                },
            )
        }
    }
}
