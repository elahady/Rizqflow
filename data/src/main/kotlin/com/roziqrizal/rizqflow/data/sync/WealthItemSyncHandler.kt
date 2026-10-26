package com.roziqrizal.rizqflow.data.sync

import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.data.db.WealthItemEntity
import com.roziqrizal.rizqflow.domain.model.WealthKind
import com.roziqrizal.rizqflow.domain.sync.DecodedRecord
import com.roziqrizal.rizqflow.domain.sync.OutgoingRecord
import com.roziqrizal.rizqflow.domain.sync.SyncRecordHandler
import com.roziqrizal.rizqflow.domain.sync.SyncRecordType
import com.roziqrizal.rizqflow.domain.sync.SyncScope
import com.roziqrizal.rizqflow.domain.sync.SyncStore

private const val VERSION = 1

/**
 * Handler [SyncRecordType.WEALTH_ITEM] (Tahap 4 sync-desain.md): baris harta/pengurang satu profil.
 * Merujuk [WealthItemPayloadDto.profileId]; rujukan belum ada ditunda lewat `DEFERRED_MISSING_REF`.
 * Tombstone datang dari [com.roziqrizal.rizqflow.data.repo.LocalZakatRepository.replaceItems] (baris
 * yang hilang dari daftar baru).
 */
class WealthItemSyncHandler(private val db: RizqflowDatabase, private val store: SyncStore) : SyncRecordHandler {
    override val type: SyncRecordType = SyncRecordType.WEALTH_ITEM

    override suspend fun collectDirty(scope: SyncScope): List<OutgoingRecord> {
        if (scope != SyncScope.Personal) return emptyList()
        val metas = store.dirtyRecords(scope, type)
        return metas.mapNotNull { meta ->
            val data = if (meta.deleted) {
                JsonObjects.empty
            } else {
                val entity = db.zakat().findItem(meta.recordId) ?: return@mapNotNull null
                WealthItemPayloadDto(
                    profileId = entity.profileId,
                    kind = entity.kind.name,
                    label = entity.label,
                    valueMinor = entity.value,
                    goldMilligrams = entity.goldMilligrams,
                    updatedAtMillis = entity.updatedAt,
                ).toJsonObject(WealthItemPayloadDto.serializer())
            }
            OutgoingRecord(meta.recordId, VERSION, meta.changedAtMillis, meta.deleted, data)
        }
    }

    override suspend fun missingReferences(scope: SyncScope, decoded: DecodedRecord): List<String> {
        if (decoded.data.isEmpty()) return emptyList() // tombstone: hapus tidak butuh rujukan ada.
        val dto = decoded.data.decodeAs(WealthItemPayloadDto.serializer())
        return if (db.zakat().profile(dto.profileId) == null) listOf("zakat_profile:${dto.profileId}") else emptyList()
    }

    override suspend fun apply(scope: SyncScope, decoded: DecodedRecord) {
        if (decoded.data.isEmpty()) {
            db.zakat().deleteItem(decoded.id)
            return
        }
        val dto = decoded.data.decodeAs(WealthItemPayloadDto.serializer())
        val kind = WealthKind.entries.firstOrNull { it.name == dto.kind } ?: return
        db.zakat().upsertItemFromSync(
            WealthItemEntity(
                id = decoded.id,
                profileId = dto.profileId,
                kind = kind,
                label = dto.label,
                value = dto.valueMinor,
                goldMilligrams = dto.goldMilligrams,
                updatedAt = dto.updatedAtMillis,
            ),
        )
    }
}
