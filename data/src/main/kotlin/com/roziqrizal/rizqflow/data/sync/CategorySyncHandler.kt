package com.roziqrizal.rizqflow.data.sync

import com.roziqrizal.rizqflow.data.db.CategoryEntity
import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.domain.sync.DecodedRecord
import com.roziqrizal.rizqflow.domain.sync.OutgoingRecord
import com.roziqrizal.rizqflow.domain.sync.SyncRecordHandler
import com.roziqrizal.rizqflow.domain.sync.SyncRecordType
import com.roziqrizal.rizqflow.domain.sync.SyncScope
import com.roziqrizal.rizqflow.domain.sync.SyncStore

private const val VERSION = 1

/**
 * Handler [SyncRecordType.CATEGORY] (Tahap 3 sync-desain.md): merujuk `room_id` (R1 bagian B, id
 * apa adanya). [missingReferences] memeriksa ruangnya sudah ada secara lokal; bila belum, mesin
 * sinkron mencatat `sync_issue(DEFERRED_MISSING_REF)` dan menundanya (bagian B "Referensi belum tiba").
 */
class CategorySyncHandler(private val db: RizqflowDatabase, private val store: SyncStore) : SyncRecordHandler {
    override val type: SyncRecordType = SyncRecordType.CATEGORY

    override suspend fun collectDirty(scope: SyncScope): List<OutgoingRecord> {
        if (scope != SyncScope.Personal) return emptyList()
        val metas = store.dirtyRecords(scope, type)
        return metas.mapNotNull { meta ->
            val data = if (meta.deleted) {
                JsonObjects.empty
            } else {
                val entity = db.rooms().findCategory(meta.recordId) ?: return@mapNotNull null
                entity.toPayload().toJsonObject(CategoryPayloadDto.serializer())
            }
            OutgoingRecord(meta.recordId, VERSION, meta.changedAtMillis, meta.deleted, data)
        }
    }

    override suspend fun missingReferences(scope: SyncScope, decoded: DecodedRecord): List<String> {
        if (decoded.data.isEmpty()) return emptyList() // tombstone: tidak butuh room ada untuk dilewati dengan aman.
        val dto = decoded.data.decodeAs(CategoryPayloadDto.serializer())
        return if (db.rooms().find(dto.roomId) == null) listOf("room:${dto.roomId}") else emptyList()
    }

    override suspend fun apply(scope: SyncScope, decoded: DecodedRecord) {
        if (decoded.data.isEmpty()) return // tombstone: tidak ada API hapus kategori lokal; dilewati dengan aman.
        val dto = decoded.data.decodeAs(CategoryPayloadDto.serializer())
        db.rooms().upsertCategory(
            CategoryEntity(
                id = decoded.id,
                roomId = dto.roomId,
                name = dto.name,
                weight = dto.weight,
                isSystem = dto.isSystem,
                archived = dto.archived,
                sortOrder = dto.sortOrder,
            ),
        )
    }
}

private fun CategoryEntity.toPayload() = CategoryPayloadDto(
    roomId = roomId,
    name = name,
    weight = weight,
    isSystem = isSystem,
    archived = archived,
    sortOrder = sortOrder,
)
