package com.roziqrizal.rizqflow.data.sync

import com.roziqrizal.rizqflow.data.db.GoldPriceEntity
import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.domain.model.GoldPriceSource
import com.roziqrizal.rizqflow.domain.sync.DecodedRecord
import com.roziqrizal.rizqflow.domain.sync.OutgoingRecord
import com.roziqrizal.rizqflow.domain.sync.SyncRecordHandler
import com.roziqrizal.rizqflow.domain.sync.SyncRecordIds
import com.roziqrizal.rizqflow.domain.sync.SyncRecordType
import com.roziqrizal.rizqflow.domain.sync.SyncScope
import com.roziqrizal.rizqflow.domain.sync.SyncStore

private const val VERSION = 1

/**
 * Handler [SyncRecordType.GOLD_PRICE] (Tahap 4 sync-desain.md): HANYA harga input MANUAL. Harga AUTO
 * per perangkat dan ditunda ke v1.1; [collectDirty] tidak pernah mengirim baris AUTO, dan [apply]
 * selalu menulis sebagai MANUAL (rekaman yang datang memang hanya MANUAL). Rekaman id `g:<epochDay>`.
 * Tidak merujuk entitas lain.
 */
class GoldPriceSyncHandler(private val db: RizqflowDatabase, private val store: SyncStore) : SyncRecordHandler {
    override val type: SyncRecordType = SyncRecordType.GOLD_PRICE

    override suspend fun collectDirty(scope: SyncScope): List<OutgoingRecord> {
        if (scope != SyncScope.Personal) return emptyList()
        val metas = store.dirtyRecords(scope, type)
        return metas.mapNotNull { meta ->
            val data = if (meta.deleted) {
                JsonObjects.empty
            } else {
                val day = recordDay(meta.recordId) ?: return@mapNotNull null
                val entity = db.zakat().findGoldPrice(day) ?: return@mapNotNull null
                if (entity.source != GoldPriceSource.MANUAL) return@mapNotNull null // AUTO tidak pernah ikut sinkron.
                GoldPricePayloadDto(perGramMinor = entity.perGram).toJsonObject(GoldPricePayloadDto.serializer())
            }
            OutgoingRecord(meta.recordId, VERSION, meta.changedAtMillis, meta.deleted, data)
        }
    }

    override suspend fun missingReferences(scope: SyncScope, decoded: DecodedRecord): List<String> = emptyList()

    override suspend fun apply(scope: SyncScope, decoded: DecodedRecord) {
        val day = recordDay(decoded.id) ?: return
        if (decoded.data.isEmpty()) {
            db.zakat().deleteManualGoldPrice(day)
            return
        }
        val dto = decoded.data.decodeAs(GoldPricePayloadDto.serializer())
        db.zakat().upsertGoldPrice(GoldPriceEntity(day = day, perGram = dto.perGramMinor, source = GoldPriceSource.MANUAL))
    }

    /** Tanggal (epochDay) dari id `g:<epochDay>`; null bila bentuk id tak dikenal (dilewati). */
    private fun recordDay(recordId: String): Long? = if (recordId.startsWith("g:")) recordId.substring(2).toLongOrNull() else null
}
