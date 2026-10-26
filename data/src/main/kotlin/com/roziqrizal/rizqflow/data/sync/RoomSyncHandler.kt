package com.roziqrizal.rizqflow.data.sync

import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.data.db.RoomEntity
import com.roziqrizal.rizqflow.domain.model.RoomKind
import com.roziqrizal.rizqflow.domain.sync.DecodedRecord
import com.roziqrizal.rizqflow.domain.sync.OutgoingRecord
import com.roziqrizal.rizqflow.domain.sync.SyncRecordHandler
import com.roziqrizal.rizqflow.domain.sync.SyncRecordType
import com.roziqrizal.rizqflow.domain.sync.SyncScope
import com.roziqrizal.rizqflow.domain.sync.SyncStore

private const val VERSION = 1

/**
 * Handler [SyncRecordType.ROOM] (Tahap 3 sync-desain.md): tidak merujuk entitas lain, jadi
 * [missingReferences] selalu kosong. Dipakai hanya cakupan pribadi ([SyncScope.Personal]).
 */
class RoomSyncHandler(private val db: RizqflowDatabase, private val store: SyncStore) : SyncRecordHandler {
    override val type: SyncRecordType = SyncRecordType.ROOM

    override suspend fun collectDirty(scope: SyncScope): List<OutgoingRecord> {
        if (scope != SyncScope.Personal) return emptyList()
        val metas = store.dirtyRecords(scope, type)
        return metas.map { meta ->
            val data = if (meta.deleted) {
                JsonObjects.empty
            } else {
                val entity = db.rooms().find(meta.recordId) ?: return@map null
                entity.toPayload().toJsonObject(RoomPayloadDto.serializer())
            }
            OutgoingRecord(meta.recordId, VERSION, meta.changedAtMillis, meta.deleted, data)
        }.filterNotNull()
    }

    override suspend fun missingReferences(scope: SyncScope, decoded: DecodedRecord): List<String> = emptyList()

    override suspend fun apply(scope: SyncScope, decoded: DecodedRecord) {
        if (decoded.data.isEmpty()) return // tombstone: tidak ada API hapus ruang lokal (belum ada di repositori); dilewati dengan aman.
        val dto = decoded.data.decodeAs(RoomPayloadDto.serializer())
        val kind = RoomKind.entries.firstOrNull { it.name == dto.kind } ?: return
        db.rooms().upsert(
            RoomEntity(
                id = decoded.id,
                name = dto.name,
                kind = kind,
                iconKey = dto.iconKey,
                colorSlot = dto.colorSlot,
                sortOrder = dto.sortOrder,
                archived = dto.archived,
                givingMode = dto.givingMode,
            ),
        )
    }
}

private fun RoomEntity.toPayload() = RoomPayloadDto(
    name = name,
    kind = kind.name,
    iconKey = iconKey,
    colorSlot = colorSlot,
    sortOrder = sortOrder,
    archived = archived,
    givingMode = givingMode,
)

/** JsonObject kosong bersama, dipakai semua handler untuk tombstone (bagian C: tidak ada isi untuk tipe rekaman apa pun). */
internal object JsonObjects {
    val empty = kotlinx.serialization.json.JsonObject(emptyMap())
}
