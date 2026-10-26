package com.roziqrizal.rizqflow.data.sync

import com.roziqrizal.rizqflow.data.db.DebtPaymentEntity
import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
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
 * Payload [SyncRecordType.DEBT_PAYMENT] (Tahap 4 sync-desain.md bagian A): pelunasan sebagian atau
 * penuh, rekaman terpisah yang merujuk utangnya ([debtId]) dan opsional transaksinya
 * ([transactionId]), keduanya id apa adanya (R1).
 */
@Serializable
internal data class DebtPaymentPayloadDto(
    @SerialName("debt_id") val debtId: String,
    @SerialName("amount_minor") val amountMinor: Long,
    @SerialName("paid_on") val paidOnEpochDay: Long,
    @SerialName("transaction_id") val transactionId: String? = null,
)

/**
 * Handler [SyncRecordType.DEBT_PAYMENT]. Urutan penerapan sudah benar lewat peringkat tipe (DEBT 60
 * sebelum DEBT_PAYMENT 70). [missingReferences] tetap memeriksa utang dan transaksi induknya, karena
 * pelunasan dari halaman lain bisa tiba lebih dulu.
 */
class DebtPaymentSyncHandler(private val db: RizqflowDatabase, private val store: SyncStore) : SyncRecordHandler {
    override val type: SyncRecordType = SyncRecordType.DEBT_PAYMENT

    override suspend fun collectDirty(scope: SyncScope): List<OutgoingRecord> {
        if (scope != SyncScope.Personal) return emptyList()
        val metas = store.dirtyRecords(scope, type)
        return metas.mapNotNull { meta ->
            val data = if (meta.deleted) {
                JsonObjects.empty
            } else {
                val entity = db.debts().findPayment(meta.recordId) ?: return@mapNotNull null
                entity.toPayload().toJsonObject(DebtPaymentPayloadDto.serializer())
            }
            OutgoingRecord(meta.recordId, VERSION, meta.changedAtMillis, meta.deleted, data)
        }
    }

    override suspend fun missingReferences(scope: SyncScope, decoded: DecodedRecord): List<String> {
        if (decoded.data.isEmpty()) return emptyList() // tombstone: hapus tidak butuh rujukan ada.
        val dto = decoded.data.decodeAs(DebtPaymentPayloadDto.serializer())
        val missing = mutableListOf<String>()
        if (db.debts().find(dto.debtId) == null) missing += "debt:${dto.debtId}"
        dto.transactionId?.let { if (db.transactions().find(it) == null) missing += "transaction:$it" }
        return missing
    }

    override suspend fun apply(scope: SyncScope, decoded: DecodedRecord) {
        if (decoded.data.isEmpty()) {
            db.debts().deletePayment(decoded.id)
            return
        }
        val dto = decoded.data.decodeAs(DebtPaymentPayloadDto.serializer())
        db.debts().upsertPayment(
            DebtPaymentEntity(
                id = decoded.id,
                debtId = dto.debtId,
                amount = dto.amountMinor,
                paidOn = dto.paidOnEpochDay,
                transactionId = dto.transactionId,
            ),
        )
    }
}

private fun DebtPaymentEntity.toPayload() = DebtPaymentPayloadDto(
    debtId = debtId,
    amountMinor = amount,
    paidOnEpochDay = paidOn,
    transactionId = transactionId,
)
