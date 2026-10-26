package com.roziqrizal.rizqflow.data.sync

import com.roziqrizal.rizqflow.data.db.BillEntity
import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.domain.recurring.Frequency
import com.roziqrizal.rizqflow.domain.sync.DecodedRecord
import com.roziqrizal.rizqflow.domain.sync.OutgoingRecord
import com.roziqrizal.rizqflow.domain.sync.SyncRecordHandler
import com.roziqrizal.rizqflow.domain.sync.SyncRecordType
import com.roziqrizal.rizqflow.domain.sync.SyncScope
import com.roziqrizal.rizqflow.domain.sync.SyncStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val VERSION = 1

/**
 * Payload [SyncRecordType.BILL] (Tahap 4 sync-desain.md bagian A dan C). Merujuk [accountId],
 * [roomId], dan [categoryId] (R1, id apa adanya). Transaksi yang dihasilkan tagihan sudah punya id
 * deterministik (`bill-<id>-<jatuh tempo>`), jadi dua perangkat yang menjalankannya konvergen.
 */
@Serializable
internal data class BillPayloadDto(
    val name: String,
    @SerialName("amount_minor") val amountMinor: Long,
    val currency: String,
    @SerialName("account_id") val accountId: String,
    @SerialName("room_id") val roomId: String,
    @SerialName("category_id") val categoryId: String,
    val note: String? = null,
    val frequency: String? = null,
    @SerialName("start_date") val startDateEpochDay: Long,
    @SerialName("next_due") val nextDueEpochDay: Long,
    @SerialName("total_installments") val totalInstallments: Int? = null,
    @SerialName("paid_count") val paidCount: Int,
    val active: Boolean,
)

/**
 * Handler [SyncRecordType.BILL] (Tahap 4 sync-desain.md). Rujukan akun, ruang, dan kategori dicek
 * lewat [missingReferences] sebelum menulis, karena FK `RESTRICT` lokal akan menolak baris yang
 * induknya belum ada.
 */
class BillSyncHandler(private val db: RizqflowDatabase, private val store: SyncStore) : SyncRecordHandler {
    override val type: SyncRecordType = SyncRecordType.BILL

    override suspend fun collectDirty(scope: SyncScope): List<OutgoingRecord> {
        if (scope != SyncScope.Personal) return emptyList()
        val metas = store.dirtyRecords(scope, type)
        return metas.mapNotNull { meta ->
            val data = if (meta.deleted) {
                JsonObjects.empty
            } else {
                val entity = db.bills().find(meta.recordId) ?: return@mapNotNull null
                entity.toPayload().toJsonObject(BillPayloadDto.serializer())
            }
            OutgoingRecord(meta.recordId, VERSION, meta.changedAtMillis, meta.deleted, data)
        }
    }

    override suspend fun missingReferences(scope: SyncScope, decoded: DecodedRecord): List<String> {
        if (decoded.data.isEmpty()) return emptyList() // tombstone: hapus tidak butuh rujukan ada.
        val dto = decoded.data.decodeAs(BillPayloadDto.serializer())
        val missing = mutableListOf<String>()
        if (db.accounts().find(dto.accountId) == null) missing += "account:${dto.accountId}"
        if (db.rooms().find(dto.roomId) == null) missing += "room:${dto.roomId}"
        if (db.rooms().findCategory(dto.categoryId) == null) missing += "category:${dto.categoryId}"
        return missing
    }

    override suspend fun apply(scope: SyncScope, decoded: DecodedRecord) {
        if (decoded.data.isEmpty()) {
            db.bills().delete(decoded.id)
            return
        }
        val dto = decoded.data.decodeAs(BillPayloadDto.serializer())
        val frequency = dto.frequency?.let { raw -> Frequency.entries.firstOrNull { it.name == raw } ?: return }
        db.bills().upsert(
            BillEntity(
                id = decoded.id,
                name = dto.name,
                amount = dto.amountMinor,
                currency = dto.currency,
                accountId = dto.accountId,
                roomId = dto.roomId,
                categoryId = dto.categoryId,
                note = dto.note,
                frequency = frequency,
                startDate = dto.startDateEpochDay,
                nextDue = dto.nextDueEpochDay,
                totalInstallments = dto.totalInstallments,
                paidCount = dto.paidCount,
                active = dto.active,
            ),
        )
    }
}

private fun BillEntity.toPayload() = BillPayloadDto(
    name = name,
    amountMinor = amount,
    currency = currency,
    accountId = accountId,
    roomId = roomId,
    categoryId = categoryId,
    note = note,
    frequency = frequency?.name,
    startDateEpochDay = startDate,
    nextDueEpochDay = nextDue,
    totalInstallments = totalInstallments,
    paidCount = paidCount,
    active = active,
)
