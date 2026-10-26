package com.roziqrizal.rizqflow.data.sync

import com.roziqrizal.rizqflow.data.db.AllocationEntryEntity
import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.data.db.TransactionEntity
import com.roziqrizal.rizqflow.domain.model.TransactionKind
import com.roziqrizal.rizqflow.domain.model.TransactionOrigin
import com.roziqrizal.rizqflow.domain.sync.DecodedRecord
import com.roziqrizal.rizqflow.domain.sync.OutgoingRecord
import com.roziqrizal.rizqflow.domain.sync.SyncRecordHandler
import com.roziqrizal.rizqflow.domain.sync.SyncRecordType
import com.roziqrizal.rizqflow.domain.sync.SyncScope
import com.roziqrizal.rizqflow.domain.sync.SyncStore

private const val VERSION = 1

/**
 * Handler [SyncRecordType.TRANSACTION] (Tahap 3 sync-desain.md): SEMUA jenis transaksi (EXPENSE,
 * INCOME, TRANSFER, LOAN_OUT/LOAN_IN); pemasukan menyertakan potret alokasi (`entries`) di payload
 * yang SAMA (bagian A, satu rekaman atomik). Merujuk [PersonalTransactionPayloadDto.accountId]/[PersonalTransactionPayloadDto.toAccountId]
 * (akun), [PersonalTransactionPayloadDto.roomId] (EXPENSE) dan [PersonalTransactionPayloadDto.categoryId] (R1 bagian B, id apa adanya);
 * `entries[].roomId` untuk pemasukan juga dirujuk.
 *
 * Transaksi `origin = SHARED` (proyeksi pengeluaran anggota lain di ruang bersama, Tahap 2) BUKAN
 * cakupan pribadi (sudah disaring [com.roziqrizal.rizqflow.data.repo.LocalTransactionRepository.recordChanged]
 * sebelum sampai sini, lihat [SyncRecorder]): [collectDirty] tidak akan pernah menemukannya kotor di
 * cakupan ini. Transaksi yang DITERIMA lewat [apply] selalu `origin = MANUAL` (ini data milik
 * pengguna sendiri dari perangkat lain, bukan proyeksi anggota lain).
 */
class TransactionSyncHandler(private val db: RizqflowDatabase, private val store: SyncStore) : SyncRecordHandler {
    override val type: SyncRecordType = SyncRecordType.TRANSACTION

    override suspend fun collectDirty(scope: SyncScope): List<OutgoingRecord> {
        if (scope != SyncScope.Personal) return emptyList()
        val metas = store.dirtyRecords(scope, type)
        return metas.mapNotNull { meta ->
            val data = if (meta.deleted) {
                JsonObjects.empty
            } else {
                val entity = db.transactions().find(meta.recordId) ?: return@mapNotNull null
                val entries = if (entity.kind == TransactionKind.INCOME) db.transactions().entriesOf(meta.recordId) else emptyList()
                entity.toPayload(entries).toJsonObject(PersonalTransactionPayloadDto.serializer())
            }
            OutgoingRecord(meta.recordId, VERSION, meta.changedAtMillis, meta.deleted, data)
        }
    }

    override suspend fun missingReferences(scope: SyncScope, decoded: DecodedRecord): List<String> {
        if (decoded.data.isEmpty()) return emptyList() // tombstone: hapus tidak butuh rujukan ada.
        val dto = decoded.data.decodeAs(PersonalTransactionPayloadDto.serializer())
        val missing = mutableListOf<String>()
        if (db.accounts().find(dto.accountId) == null) missing += "account:${dto.accountId}"
        dto.toAccountId?.let { if (db.accounts().find(it) == null) missing += "account:$it" }
        dto.roomId?.let { if (db.rooms().find(it) == null) missing += "room:$it" }
        dto.categoryId?.let { if (db.rooms().findCategory(it) == null) missing += "category:$it" }
        for (entry in dto.entries) if (db.rooms().find(entry.roomId) == null) missing += "room:${entry.roomId}"
        return missing
    }

    override suspend fun apply(scope: SyncScope, decoded: DecodedRecord) {
        if (decoded.data.isEmpty()) {
            applyTombstone(decoded.id)
            return
        }
        val dto = decoded.data.decodeAs(PersonalTransactionPayloadDto.serializer())
        val kind = TransactionKind.entries.firstOrNull { it.name == dto.kind } ?: return
        db.transactions().upsertFromSync(
            TransactionEntity(
                id = decoded.id,
                kind = kind,
                amount = dto.amountMinor,
                currency = dto.currency,
                accountId = dto.accountId,
                toAccountId = dto.toAccountId,
                roomId = dto.roomId,
                categoryId = dto.categoryId,
                incomeSource = dto.incomeSource,
                occurredOn = dto.occurredOnEpochDay,
                note = dto.note,
                origin = TransactionOrigin.MANUAL,
                createdAt = dto.createdAtMillis,
                updatedAt = dto.updatedAtMillis,
                syncedAt = dto.updatedAtMillis,
            ),
        )
        if (kind == TransactionKind.INCOME) {
            db.transactions().deleteEntries(decoded.id)
            if (dto.entries.isNotEmpty()) {
                db.transactions().insertEntries(
                    dto.entries.map { entry ->
                        AllocationEntryEntity(
                            id = entry.id,
                            incomeId = decoded.id,
                            roomId = entry.roomId,
                            shareBp = entry.shareBp,
                            amount = entry.amountMinor,
                            allocatedAt = entry.allocatedAtMillis,
                        )
                    },
                )
            }
        }
    }

    /**
     * Potret alokasi ikut terhapus lewat `ON DELETE CASCADE` (lihat [AllocationEntryEntity]). Baris
     * yang direstrict FK lain (mis. `zakat_payment.transaction_id`, Tahap 4) melempar
     * `SQLiteConstraintException`; mesin sinkron (bukan di sini) yang menangkap dan mencatatnya
     * sebagai `sync_issue(DEFERRED_FK_RESTRICT)` — lihat KDoc [SyncRecordHandler.apply].
     */
    private suspend fun applyTombstone(id: String) {
        db.transactions().delete(id)
    }
}

private fun TransactionEntity.toPayload(entries: List<AllocationEntryEntity>) = PersonalTransactionPayloadDto(
    kind = kind.name,
    amountMinor = amount,
    currency = currency,
    accountId = accountId,
    toAccountId = toAccountId,
    roomId = roomId,
    categoryId = categoryId,
    incomeSource = incomeSource,
    occurredOnEpochDay = occurredOn,
    note = note,
    createdAtMillis = createdAt,
    updatedAtMillis = updatedAt,
    entries = entries.map { e ->
        AllocationEntryPayloadDto(id = e.id, roomId = e.roomId, shareBp = e.shareBp, amountMinor = e.amount, allocatedAtMillis = e.allocatedAt)
    },
)
