package com.roziqrizal.rizqflow.data.sync

import com.roziqrizal.rizqflow.data.db.AccountEntity
import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.domain.model.AccountKind
import com.roziqrizal.rizqflow.domain.sync.DecodedRecord
import com.roziqrizal.rizqflow.domain.sync.OutgoingRecord
import com.roziqrizal.rizqflow.domain.sync.SyncRecordHandler
import com.roziqrizal.rizqflow.domain.sync.SyncRecordType
import com.roziqrizal.rizqflow.domain.sync.SyncScope
import com.roziqrizal.rizqflow.domain.sync.SyncStore

private const val VERSION = 1

/**
 * Handler [SyncRecordType.ACCOUNT] (Tahap 3 sync-desain.md): tidak merujuk entitas lain, jadi
 * [missingReferences] selalu kosong. Saldo TIDAK disimpan atau disinkronkan (bagian A): hanya
 * `opening_balance`, nama, jenis, arsip, urutan, `last_reconciled_on`. Akun anggota virtual
 * (`shared_from` terisi) tidak pernah ditandai kotor di cakupan pribadi ([SyncRecorder]) sehingga
 * tidak pernah muncul di [collectDirty]; [apply] tidak pernah membuat baris dengan `shared_from`
 * terisi (field itu selalu null untuk hasil pull cakupan pribadi).
 */
class AccountSyncHandler(private val db: RizqflowDatabase, private val store: SyncStore) : SyncRecordHandler {
    override val type: SyncRecordType = SyncRecordType.ACCOUNT

    override suspend fun collectDirty(scope: SyncScope): List<OutgoingRecord> {
        if (scope != SyncScope.Personal) return emptyList()
        val metas = store.dirtyRecords(scope, type)
        return metas.mapNotNull { meta ->
            val data = if (meta.deleted) {
                JsonObjects.empty
            } else {
                val entity = db.accounts().find(meta.recordId) ?: return@mapNotNull null
                entity.toPayload().toJsonObject(AccountPayloadDto.serializer())
            }
            OutgoingRecord(meta.recordId, VERSION, meta.changedAtMillis, meta.deleted, data)
        }
    }

    override suspend fun missingReferences(scope: SyncScope, decoded: DecodedRecord): List<String> = emptyList()

    override suspend fun apply(scope: SyncScope, decoded: DecodedRecord) {
        if (decoded.data.isEmpty()) return // tombstone: tidak ada API hapus akun lokal; dilewati dengan aman.
        val dto = decoded.data.decodeAs(AccountPayloadDto.serializer())
        val kind = AccountKind.entries.firstOrNull { it.name == dto.kind } ?: return
        db.accounts().upsert(
            AccountEntity(
                id = decoded.id,
                name = dto.name,
                kind = kind,
                currency = dto.currency,
                openingBalance = dto.openingBalanceMinor,
                archived = dto.archived,
                sortOrder = dto.sortOrder,
                lastReconciledOn = dto.lastReconciledOnEpochDay,
                sharedFrom = null,
            ),
        )
    }
}

private fun AccountEntity.toPayload() = AccountPayloadDto(
    name = name,
    kind = kind.name,
    currency = currency,
    openingBalanceMinor = openingBalance,
    archived = archived,
    sortOrder = sortOrder,
    lastReconciledOnEpochDay = lastReconciledOn,
)
