package com.roziqrizal.rizqflow.data.sync

import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.data.db.ZakatProfileEntity
import com.roziqrizal.rizqflow.domain.sync.DecodedRecord
import com.roziqrizal.rizqflow.domain.sync.OutgoingRecord
import com.roziqrizal.rizqflow.domain.sync.SyncRecordHandler
import com.roziqrizal.rizqflow.domain.sync.SyncRecordType
import com.roziqrizal.rizqflow.domain.sync.SyncScope
import com.roziqrizal.rizqflow.domain.sync.SyncStore
import com.roziqrizal.rizqflow.domain.zakat.HaulBreakPolicy

private const val VERSION = 1

/**
 * Handler [SyncRecordType.ZAKAT_PROFILE] (Tahap 4 sync-desain.md): tidak merujuk entitas lain, jadi
 * [missingReferences] selalu kosong. Profil zakat tidak punya jalur hapus lokal (hanya diarsipkan),
 * jadi tombstone tidak pernah dibuat; bila suatu saat datang, [apply] membiarkannya (sama seperti kategori).
 */
class ZakatProfileSyncHandler(private val db: RizqflowDatabase, private val store: SyncStore) : SyncRecordHandler {
    override val type: SyncRecordType = SyncRecordType.ZAKAT_PROFILE

    override suspend fun collectDirty(scope: SyncScope): List<OutgoingRecord> {
        if (scope != SyncScope.Personal) return emptyList()
        val metas = store.dirtyRecords(scope, type)
        return metas.mapNotNull { meta ->
            val data = if (meta.deleted) {
                JsonObjects.empty
            } else {
                val entity = db.zakat().profile(meta.recordId) ?: return@mapNotNull null
                ZakatProfilePayloadDto(entity.name, entity.haulBreakPolicy, entity.archived).toJsonObject(ZakatProfilePayloadDto.serializer())
            }
            OutgoingRecord(meta.recordId, VERSION, meta.changedAtMillis, meta.deleted, data)
        }
    }

    override suspend fun missingReferences(scope: SyncScope, decoded: DecodedRecord): List<String> = emptyList()

    override suspend fun apply(scope: SyncScope, decoded: DecodedRecord) {
        if (decoded.data.isEmpty()) return // tombstone: tidak ada API hapus profil lokal; dilewati dengan aman.
        val dto = decoded.data.decodeAs(ZakatProfilePayloadDto.serializer())
        // Kebijakan tak dikenal (versi aplikasi lain) dilewati: lebih baik tidak menulis daripada membuat baris yang tak terbaca.
        if (HaulBreakPolicy.entries.none { it.name == dto.haulBreakPolicy }) return
        db.zakat().upsertProfile(
            ZakatProfileEntity(id = decoded.id, name = dto.name, haulBreakPolicy = dto.haulBreakPolicy, archived = dto.archived),
        )
    }
}
