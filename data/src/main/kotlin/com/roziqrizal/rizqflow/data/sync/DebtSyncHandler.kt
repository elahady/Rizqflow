package com.roziqrizal.rizqflow.data.sync

import com.roziqrizal.rizqflow.data.db.DebtEntity
import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.domain.debt.DebtDirection
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
 * Payload [SyncRecordType.DEBT] (Tahap 4 sync-desain.md bagian A dan C). Merujuk [accountId] dan
 * [initialTransactionId] (R1, id apa adanya). Entitas utang tidak punya kolom waktu bisnis, jadi
 * cap waktu hanya berasal dari `sync_record`.
 */
@Serializable
internal data class DebtPayloadDto(
    val direction: String,
    val party: String,
    @SerialName("principal_minor") val principalMinor: Long,
    val currency: String,
    @SerialName("account_id") val accountId: String? = null,
    @SerialName("initial_transaction_id") val initialTransactionId: String? = null,
    @SerialName("start_date") val startDateEpochDay: Long,
    @SerialName("due_date") val dueDateEpochDay: Long? = null,
    val note: String? = null,
    val collectible: Boolean,
)

/**
 * Handler [SyncRecordType.DEBT] (Tahap 4 sync-desain.md): pokok dan data utang-piutang. Rujukan
 * [DebtPayloadDto.accountId] dan [DebtPayloadDto.initialTransactionId] dicek lebih dulu lewat
 * [missingReferences] (pra-cek, bukan try/catch). FK SQLite tetap diperiksa walaupun `SET NULL`
 * berlaku saat induknya dihapus, jadi induk yang belum tiba harus menunda rekaman ini.
 */
class DebtSyncHandler(private val db: RizqflowDatabase, private val store: SyncStore) : SyncRecordHandler {
    override val type: SyncRecordType = SyncRecordType.DEBT

    override suspend fun collectDirty(scope: SyncScope): List<OutgoingRecord> {
        if (scope != SyncScope.Personal) return emptyList()
        val metas = store.dirtyRecords(scope, type)
        return metas.mapNotNull { meta ->
            val data = if (meta.deleted) {
                JsonObjects.empty
            } else {
                val entity = db.debts().find(meta.recordId) ?: return@mapNotNull null
                entity.toPayload().toJsonObject(DebtPayloadDto.serializer())
            }
            OutgoingRecord(meta.recordId, VERSION, meta.changedAtMillis, meta.deleted, data)
        }
    }

    override suspend fun missingReferences(scope: SyncScope, decoded: DecodedRecord): List<String> {
        if (decoded.data.isEmpty()) return emptyList() // tombstone: hapus tidak butuh rujukan ada.
        val dto = decoded.data.decodeAs(DebtPayloadDto.serializer())
        val missing = mutableListOf<String>()
        dto.accountId?.let { if (db.accounts().find(it) == null) missing += "account:$it" }
        dto.initialTransactionId?.let { if (db.transactions().find(it) == null) missing += "transaction:$it" }
        return missing
    }

    override suspend fun apply(scope: SyncScope, decoded: DecodedRecord) {
        if (decoded.data.isEmpty()) {
            // Pelunasan ikut terhapus lewat ON DELETE CASCADE; tombstone pelunasannya datang sendiri (DEBT_PAYMENT).
            db.debts().delete(decoded.id)
            return
        }
        val dto = decoded.data.decodeAs(DebtPayloadDto.serializer())
        val direction = DebtDirection.entries.firstOrNull { it.name == dto.direction } ?: return
        db.debts().upsert(
            DebtEntity(
                id = decoded.id,
                direction = direction,
                party = dto.party,
                principal = dto.principalMinor,
                currency = dto.currency,
                accountId = dto.accountId,
                initialTransactionId = dto.initialTransactionId,
                startDate = dto.startDateEpochDay,
                dueDate = dto.dueDateEpochDay,
                note = dto.note,
                collectible = dto.collectible,
            ),
        )
    }
}

private fun DebtEntity.toPayload() = DebtPayloadDto(
    direction = direction.name,
    party = party,
    principalMinor = principal,
    currency = currency,
    accountId = accountId,
    initialTransactionId = initialTransactionId,
    startDateEpochDay = startDate,
    dueDateEpochDay = dueDate,
    note = note,
    collectible = collectible,
)
