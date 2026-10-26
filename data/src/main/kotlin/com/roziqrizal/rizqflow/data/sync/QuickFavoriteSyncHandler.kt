package com.roziqrizal.rizqflow.data.sync

import com.roziqrizal.rizqflow.data.db.QuickFavoriteEntity
import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.domain.sync.DecodedRecord
import com.roziqrizal.rizqflow.domain.sync.OutgoingRecord
import com.roziqrizal.rizqflow.domain.sync.SyncRecordHandler
import com.roziqrizal.rizqflow.domain.sync.SyncRecordType
import com.roziqrizal.rizqflow.domain.sync.SyncScope
import com.roziqrizal.rizqflow.domain.sync.SyncStore

private const val VERSION = 1

/**
 * Handler [SyncRecordType.QUICK_FAVORITE] (Tahap 4 sync-desain.md): favorit Catat kilat. `use_count`
 * dan `last_used_at` TIDAK ikut (bagian A): saat menerapkan, nilai lokal keduanya dipertahankan apa
 * adanya (0 dan null bila baris belum ada). Merujuk ruang, kategori, dan akun.
 */
class QuickFavoriteSyncHandler(private val db: RizqflowDatabase, private val store: SyncStore) : SyncRecordHandler {
    override val type: SyncRecordType = SyncRecordType.QUICK_FAVORITE

    override suspend fun collectDirty(scope: SyncScope): List<OutgoingRecord> {
        if (scope != SyncScope.Personal) return emptyList()
        val metas = store.dirtyRecords(scope, type)
        return metas.mapNotNull { meta ->
            val data = if (meta.deleted) {
                JsonObjects.empty
            } else {
                val entity = db.favorites().find(meta.recordId) ?: return@mapNotNull null
                QuickFavoritePayloadDto(
                    name = entity.name,
                    amountMinor = entity.amount,
                    roomId = entity.roomId,
                    categoryId = entity.categoryId,
                    accountId = entity.accountId,
                ).toJsonObject(QuickFavoritePayloadDto.serializer())
            }
            OutgoingRecord(meta.recordId, VERSION, meta.changedAtMillis, meta.deleted, data)
        }
    }

    override suspend fun missingReferences(scope: SyncScope, decoded: DecodedRecord): List<String> {
        if (decoded.data.isEmpty()) return emptyList()
        val dto = decoded.data.decodeAs(QuickFavoritePayloadDto.serializer())
        val missing = mutableListOf<String>()
        if (db.rooms().find(dto.roomId) == null) missing += "room:${dto.roomId}"
        if (db.rooms().findCategory(dto.categoryId) == null) missing += "category:${dto.categoryId}"
        if (db.accounts().find(dto.accountId) == null) missing += "account:${dto.accountId}"
        return missing
    }

    override suspend fun apply(scope: SyncScope, decoded: DecodedRecord) {
        if (decoded.data.isEmpty()) {
            db.favorites().delete(decoded.id)
            return
        }
        val dto = decoded.data.decodeAs(QuickFavoritePayloadDto.serializer())
        val local = db.favorites().find(decoded.id)
        db.favorites().upsert(
            QuickFavoriteEntity(
                id = decoded.id,
                name = dto.name,
                amount = dto.amountMinor,
                roomId = dto.roomId,
                categoryId = dto.categoryId,
                accountId = dto.accountId,
                useCount = local?.useCount ?: 0,
                lastUsedAt = local?.lastUsedAt,
            ),
        )
    }
}
