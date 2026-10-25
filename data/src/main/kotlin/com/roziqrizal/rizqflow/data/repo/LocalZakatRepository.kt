package com.roziqrizal.rizqflow.data.repo

import androidx.room.withTransaction
import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.data.sync.SyncRecorder
import com.roziqrizal.rizqflow.domain.model.GoldPriceSource
import com.roziqrizal.rizqflow.domain.sync.SyncRecordIds
import com.roziqrizal.rizqflow.domain.sync.SyncRecordType
import com.roziqrizal.rizqflow.domain.zakat.GoldPrice
import com.roziqrizal.rizqflow.domain.zakat.WealthCheck
import com.roziqrizal.rizqflow.domain.zakat.WealthItem
import com.roziqrizal.rizqflow.domain.zakat.ZakatPayment
import com.roziqrizal.rizqflow.domain.zakat.ZakatProfile
import com.roziqrizal.rizqflow.domain.zakat.ZakatRepository

class LocalZakatRepository(private val db: RizqflowDatabase, private val recorder: SyncRecorder) : ZakatRepository {
    override suspend fun profiles(): List<ZakatProfile> = db.zakat().activeProfiles().map { it.toDomain() }

    override suspend fun findProfile(id: String): ZakatProfile? = db.zakat().profile(id)?.toDomain()

    override suspend fun saveProfile(profile: ZakatProfile) {
        db.withTransaction {
            db.zakat().upsertProfile(profile.toEntity())
            recorder.changed(SyncRecordType.ZAKAT_PROFILE, profile.id)
        }
    }

    override suspend fun items(profileId: String): List<WealthItem> = db.zakat().items(profileId).map { it.toDomain() }

    override suspend fun replaceItems(profileId: String, items: List<WealthItem>) {
        db.withTransaction {
            val before = db.zakat().items(profileId).associateBy { it.id }
            db.zakat().clearItems(profileId)
            val entities = items.map { it.toEntity() }
            if (entities.isNotEmpty()) db.zakat().insertItems(entities)
            // Baris yang hilang dari daftar baru jadi tombstone; baris yang isinya tak berubah tidak ditandai ulang.
            val keptIds = entities.mapTo(HashSet()) { it.id }
            recorder.deleted(SyncRecordType.WEALTH_ITEM, before.keys.filter { it !in keptIds })
            recorder.changed(SyncRecordType.WEALTH_ITEM, entities.filter { before[it.id] != it }.map { it.id })
        }
    }

    override suspend fun latestGoldPrice(): GoldPrice? = db.zakat().latestGoldPrice()?.toDomain()

    override suspend fun saveGoldPrice(price: GoldPrice) {
        db.withTransaction {
            val old = db.zakat().findGoldPrice(price.day.toEpochDay())
            db.zakat().upsertGoldPrice(price.toEntity())
            val recordId = SyncRecordIds.goldPrice(price.day.toEpochDay())
            // Hanya harga MANUAL yang ikut sinkron; AUTO per perangkat. AUTO yang menimpa MANUAL di hari yang sama menghapusnya.
            if (price.source == GoldPriceSource.MANUAL) {
                recorder.changed(SyncRecordType.GOLD_PRICE, recordId)
            } else if (old?.source == GoldPriceSource.MANUAL) {
                recorder.deleted(SyncRecordType.GOLD_PRICE, recordId)
            }
        }
    }

    override suspend fun checks(profileId: String): List<WealthCheck> = db.zakat().checks(profileId).map { it.toDomain() }

    override suspend fun saveCheck(check: WealthCheck) {
        db.withTransaction {
            val replacedIds = db.zakat().checkIdsOfDay(check.profileId, check.day.toEpochDay())
            db.zakat().clearCheckOfDay(check.profileId, check.day.toEpochDay())
            db.zakat().insertCheck(check.toEntity())
            recorder.deleted(SyncRecordType.WEALTH_CHECK, replacedIds.filter { it != check.id })
            recorder.changed(SyncRecordType.WEALTH_CHECK, check.id)
        }
    }

    override suspend fun payments(profileId: String): List<ZakatPayment> = db.zakat().payments(profileId).map { it.toDomain() }

    override suspend fun savePayment(payment: ZakatPayment) {
        db.withTransaction {
            db.zakat().insertPayment(payment.toEntity())
            recorder.changed(SyncRecordType.ZAKAT_PAYMENT, payment.id)
        }
    }
}
