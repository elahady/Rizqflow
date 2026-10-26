package com.roziqrizal.rizqflow.data.sync

import com.roziqrizal.rizqflow.data.db.DcaPlanEntity
import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.domain.sync.DecodedRecord
import com.roziqrizal.rizqflow.domain.sync.OutgoingRecord
import com.roziqrizal.rizqflow.domain.sync.SyncRecordHandler
import com.roziqrizal.rizqflow.domain.sync.SyncRecordType
import com.roziqrizal.rizqflow.domain.sync.SyncScope
import com.roziqrizal.rizqflow.domain.sync.SyncStore

private const val VERSION = 1

/**
 * Handler [SyncRecordType.DCA_PLAN] (Tahap 4 sync-desain.md, peran Investor dari Tahap 7/Pro):
 * record id = id ruang. Merujuk ruang, akun, dan kategori (ketiganya RESTRICT/CASCADE lokal), jadi
 * semuanya dicek di [missingReferences] sebelum [apply]. Tombstone datang dari
 * [com.roziqrizal.rizqflow.data.repo.LocalRoleRepository.remove].
 */
class DcaPlanSyncHandler(private val db: RizqflowDatabase, private val store: SyncStore) : SyncRecordHandler {
    override val type: SyncRecordType = SyncRecordType.DCA_PLAN

    override suspend fun collectDirty(scope: SyncScope): List<OutgoingRecord> {
        if (scope != SyncScope.Personal) return emptyList()
        val metas = store.dirtyRecords(scope, type)
        return metas.mapNotNull { meta ->
            val data = if (meta.deleted) {
                JsonObjects.empty
            } else {
                val entity = db.roles().dca(meta.recordId) ?: return@mapNotNull null
                DcaPlanPayloadDto(
                    amountMinor = entity.amount,
                    dayOfMonth = entity.dayOfMonth,
                    accountId = entity.accountId,
                    categoryId = entity.categoryId,
                ).toJsonObject(DcaPlanPayloadDto.serializer())
            }
            OutgoingRecord(meta.recordId, VERSION, meta.changedAtMillis, meta.deleted, data)
        }
    }

    override suspend fun missingReferences(scope: SyncScope, decoded: DecodedRecord): List<String> {
        if (decoded.data.isEmpty()) return emptyList()
        val dto = decoded.data.decodeAs(DcaPlanPayloadDto.serializer())
        val missing = mutableListOf<String>()
        if (db.rooms().find(decoded.id) == null) missing += "room:${decoded.id}"
        if (db.accounts().find(dto.accountId) == null) missing += "account:${dto.accountId}"
        if (db.rooms().findCategory(dto.categoryId) == null) missing += "category:${dto.categoryId}"
        return missing
    }

    override suspend fun apply(scope: SyncScope, decoded: DecodedRecord) {
        if (decoded.data.isEmpty()) {
            db.roles().deleteDca(decoded.id)
            return
        }
        val dto = decoded.data.decodeAs(DcaPlanPayloadDto.serializer())
        db.roles().upsertDca(
            DcaPlanEntity(
                roomId = decoded.id,
                amount = dto.amountMinor,
                dayOfMonth = dto.dayOfMonth,
                accountId = dto.accountId,
                categoryId = dto.categoryId,
            ),
        )
    }
}
