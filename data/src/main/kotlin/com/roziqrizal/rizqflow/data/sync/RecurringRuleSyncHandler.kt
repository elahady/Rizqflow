package com.roziqrizal.rizqflow.data.sync

import com.roziqrizal.rizqflow.data.db.RecurringRuleEntity
import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.domain.model.TransactionKind
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
 * Payload [SyncRecordType.RECURRING_RULE] (Tahap 4 sync-desain.md bagian A dan C). Merujuk
 * [accountId], [toAccountId], [roomId], dan [categoryId] (R1, id apa adanya). Transaksi yang
 * dihasilkan aturan sudah punya id deterministik (`rec-<id>-<tanggal>`), jadi `runDue` di dua
 * perangkat konvergen.
 */
@Serializable
internal data class RecurringRulePayloadDto(
    val kind: String,
    @SerialName("amount_minor") val amountMinor: Long,
    val currency: String,
    @SerialName("account_id") val accountId: String,
    @SerialName("to_account_id") val toAccountId: String? = null,
    @SerialName("room_id") val roomId: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("income_source") val incomeSource: String? = null,
    val note: String? = null,
    val frequency: String,
    @SerialName("start_date") val startDateEpochDay: Long,
    @SerialName("next_due") val nextDueEpochDay: Long,
    @SerialName("end_date") val endDateEpochDay: Long? = null,
    val active: Boolean,
)

/**
 * Handler [SyncRecordType.RECURRING_RULE] (Tahap 4 sync-desain.md). Rujukan akun (termasuk akun
 * tujuan transfer), ruang, dan kategori dicek lewat [missingReferences] sebelum menulis; ruang dan
 * kategori boleh kosong (pemasukan dan transfer tidak punya ruang), jadi hanya yang terisi yang dicek.
 */
class RecurringRuleSyncHandler(private val db: RizqflowDatabase, private val store: SyncStore) : SyncRecordHandler {
    override val type: SyncRecordType = SyncRecordType.RECURRING_RULE

    override suspend fun collectDirty(scope: SyncScope): List<OutgoingRecord> {
        if (scope != SyncScope.Personal) return emptyList()
        val metas = store.dirtyRecords(scope, type)
        return metas.mapNotNull { meta ->
            val data = if (meta.deleted) {
                JsonObjects.empty
            } else {
                val entity = db.recurring().find(meta.recordId) ?: return@mapNotNull null
                entity.toPayload().toJsonObject(RecurringRulePayloadDto.serializer())
            }
            OutgoingRecord(meta.recordId, VERSION, meta.changedAtMillis, meta.deleted, data)
        }
    }

    override suspend fun missingReferences(scope: SyncScope, decoded: DecodedRecord): List<String> {
        if (decoded.data.isEmpty()) return emptyList() // tombstone: hapus tidak butuh rujukan ada.
        val dto = decoded.data.decodeAs(RecurringRulePayloadDto.serializer())
        val missing = mutableListOf<String>()
        if (db.accounts().find(dto.accountId) == null) missing += "account:${dto.accountId}"
        dto.toAccountId?.let { if (db.accounts().find(it) == null) missing += "account:$it" }
        dto.roomId?.let { if (db.rooms().find(it) == null) missing += "room:$it" }
        dto.categoryId?.let { if (db.rooms().findCategory(it) == null) missing += "category:$it" }
        return missing
    }

    override suspend fun apply(scope: SyncScope, decoded: DecodedRecord) {
        if (decoded.data.isEmpty()) {
            db.recurring().delete(decoded.id)
            return
        }
        val dto = decoded.data.decodeAs(RecurringRulePayloadDto.serializer())
        val kind = TransactionKind.entries.firstOrNull { it.name == dto.kind } ?: return
        val frequency = Frequency.entries.firstOrNull { it.name == dto.frequency } ?: return
        db.recurring().upsert(
            RecurringRuleEntity(
                id = decoded.id,
                kind = kind,
                amount = dto.amountMinor,
                currency = dto.currency,
                accountId = dto.accountId,
                toAccountId = dto.toAccountId,
                roomId = dto.roomId,
                categoryId = dto.categoryId,
                incomeSource = dto.incomeSource,
                note = dto.note,
                frequency = frequency,
                startDate = dto.startDateEpochDay,
                nextDue = dto.nextDueEpochDay,
                endDate = dto.endDateEpochDay,
                active = dto.active,
            ),
        )
    }
}

private fun RecurringRuleEntity.toPayload() = RecurringRulePayloadDto(
    kind = kind.name,
    amountMinor = amount,
    currency = currency,
    accountId = accountId,
    toAccountId = toAccountId,
    roomId = roomId,
    categoryId = categoryId,
    incomeSource = incomeSource,
    note = note,
    frequency = frequency.name,
    startDateEpochDay = startDate,
    nextDueEpochDay = nextDue,
    endDateEpochDay = endDate,
    active = active,
)
