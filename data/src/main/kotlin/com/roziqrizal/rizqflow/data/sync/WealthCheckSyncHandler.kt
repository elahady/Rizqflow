package com.roziqrizal.rizqflow.data.sync

import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.data.db.WealthCheckEntity
import com.roziqrizal.rizqflow.domain.sync.DecodedRecord
import com.roziqrizal.rizqflow.domain.sync.OutgoingRecord
import com.roziqrizal.rizqflow.domain.sync.SyncRecordHandler
import com.roziqrizal.rizqflow.domain.sync.SyncRecordType
import com.roziqrizal.rizqflow.domain.sync.SyncScope
import com.roziqrizal.rizqflow.domain.sync.SyncStore

private const val VERSION = 1

/**
 * Handler [SyncRecordType.WEALTH_CHECK] (Tahap 4 sync-desain.md): cek harta harian satu profil
 * (setara `HaulEvent.WealthChecked`). Merujuk [WealthCheckPayloadDto.profileId].
 *
 * Catatan: dua perangkat yang sama-sama mencatat cek harta di hari yang sama menghasilkan dua baris
 * (id berbeda). Penerapan di sini TIDAK membuang saudara sehari itu secara diam-diam, karena itu
 * akan membuat dua perangkat saling menghapus; keduanya dibiarkan dan ditangani di lapisan domain.
 */
class WealthCheckSyncHandler(private val db: RizqflowDatabase, private val store: SyncStore) : SyncRecordHandler {
    override val type: SyncRecordType = SyncRecordType.WEALTH_CHECK

    override suspend fun collectDirty(scope: SyncScope): List<OutgoingRecord> {
        if (scope != SyncScope.Personal) return emptyList()
        val metas = store.dirtyRecords(scope, type)
        return metas.mapNotNull { meta ->
            val data = if (meta.deleted) {
                JsonObjects.empty
            } else {
                val entity = db.zakat().findCheck(meta.recordId) ?: return@mapNotNull null
                WealthCheckPayloadDto(
                    profileId = entity.profileId,
                    day = entity.day,
                    netWealthMinor = entity.netWealth,
                    nisabMinor = entity.nisab,
                ).toJsonObject(WealthCheckPayloadDto.serializer())
            }
            OutgoingRecord(meta.recordId, VERSION, meta.changedAtMillis, meta.deleted, data)
        }
    }

    override suspend fun missingReferences(scope: SyncScope, decoded: DecodedRecord): List<String> {
        if (decoded.data.isEmpty()) return emptyList()
        val dto = decoded.data.decodeAs(WealthCheckPayloadDto.serializer())
        return if (db.zakat().profile(dto.profileId) == null) listOf("zakat_profile:${dto.profileId}") else emptyList()
    }

    override suspend fun apply(scope: SyncScope, decoded: DecodedRecord) {
        if (decoded.data.isEmpty()) {
            db.zakat().deleteCheck(decoded.id)
            return
        }
        val dto = decoded.data.decodeAs(WealthCheckPayloadDto.serializer())
        db.zakat().upsertCheckFromSync(
            WealthCheckEntity(
                id = decoded.id,
                profileId = dto.profileId,
                day = dto.day,
                netWealth = dto.netWealthMinor,
                nisab = dto.nisabMinor,
            ),
        )
    }
}
