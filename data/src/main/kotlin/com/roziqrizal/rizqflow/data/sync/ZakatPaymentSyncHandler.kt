package com.roziqrizal.rizqflow.data.sync

import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.data.db.ZakatPaymentEntity
import com.roziqrizal.rizqflow.domain.sync.DecodedRecord
import com.roziqrizal.rizqflow.domain.sync.OutgoingRecord
import com.roziqrizal.rizqflow.domain.sync.SyncRecordHandler
import com.roziqrizal.rizqflow.domain.sync.SyncRecordType
import com.roziqrizal.rizqflow.domain.sync.SyncScope
import com.roziqrizal.rizqflow.domain.sync.SyncStore

private const val VERSION = 1

/**
 * Handler [SyncRecordType.ZAKAT_PAYMENT] (Tahap 4 sync-desain.md): riwayat zakat dibayar (setara
 * `HaulEvent.ZakatPaid`). Merujuk profil DAN transaksi. Transaksi di-RESTRICT oleh baris ini
 * (`zakat_payment.transaction_id`), jadi transaksi harus sudah ada sebelum baris ini diterapkan; bila
 * belum, [missingReferences] menundanya lewat `DEFERRED_MISSING_REF`. Transaksi berurutan lebih dulu
 * (peringkat 50 < 130) dalam satu halaman, jadi kasus tunda biasanya hanya lintas halaman.
 */
class ZakatPaymentSyncHandler(private val db: RizqflowDatabase, private val store: SyncStore) : SyncRecordHandler {
    override val type: SyncRecordType = SyncRecordType.ZAKAT_PAYMENT

    override suspend fun collectDirty(scope: SyncScope): List<OutgoingRecord> {
        if (scope != SyncScope.Personal) return emptyList()
        val metas = store.dirtyRecords(scope, type)
        return metas.mapNotNull { meta ->
            val data = if (meta.deleted) {
                JsonObjects.empty
            } else {
                val entity = db.zakat().findPayment(meta.recordId) ?: return@mapNotNull null
                ZakatPaymentPayloadDto(
                    profileId = entity.profileId,
                    day = entity.day,
                    transactionId = entity.transactionId,
                ).toJsonObject(ZakatPaymentPayloadDto.serializer())
            }
            OutgoingRecord(meta.recordId, VERSION, meta.changedAtMillis, meta.deleted, data)
        }
    }

    override suspend fun missingReferences(scope: SyncScope, decoded: DecodedRecord): List<String> {
        if (decoded.data.isEmpty()) return emptyList()
        val dto = decoded.data.decodeAs(ZakatPaymentPayloadDto.serializer())
        val missing = mutableListOf<String>()
        if (db.zakat().profile(dto.profileId) == null) missing += "zakat_profile:${dto.profileId}"
        if (db.transactions().find(dto.transactionId) == null) missing += "transaction:${dto.transactionId}"
        return missing
    }

    override suspend fun apply(scope: SyncScope, decoded: DecodedRecord) {
        if (decoded.data.isEmpty()) {
            db.zakat().deletePayment(decoded.id)
            return
        }
        val dto = decoded.data.decodeAs(ZakatPaymentPayloadDto.serializer())
        db.zakat().upsertPaymentFromSync(
            ZakatPaymentEntity(id = decoded.id, profileId = dto.profileId, day = dto.day, transactionId = dto.transactionId),
        )
    }
}
