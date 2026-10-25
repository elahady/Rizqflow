package com.roziqrizal.rizqflow.data.repo

import androidx.room.withTransaction
import com.roziqrizal.rizqflow.data.db.AppSettingEntity
import com.roziqrizal.rizqflow.data.db.DayCheckEntity
import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.data.sync.SyncRecorder
import com.roziqrizal.rizqflow.domain.ledger.SettingsRepository
import com.roziqrizal.rizqflow.domain.sync.SyncRecordIds
import com.roziqrizal.rizqflow.domain.sync.SyncRecordType
import java.time.LocalDate

/**
 * Pengaturan perangkat (pengingat, tema, penanda, dll) TIDAK disinkronkan; satu-satunya pengecualian adalah kunci
 * mode alokasi yang menjadi bagian rekaman ALLOCATION_CONFIG. Hari yang ditandai (day_check) ikut sinkron.
 */
class LocalSettingsRepository(private val db: RizqflowDatabase, private val recorder: SyncRecorder) : SettingsRepository {
    override suspend fun get(key: String): String? = db.settings().get(key)

    override suspend fun put(key: String, value: String) {
        if (key == ALLOCATION_MODE_KEY) {
            db.withTransaction {
                db.settings().put(AppSettingEntity(key, value))
                recorder.changed(SyncRecordType.ALLOCATION_CONFIG, SyncRecordIds.ALLOCATION_CONFIG)
            }
        } else {
            db.settings().put(AppSettingEntity(key, value))
        }
    }

    override suspend fun isDayChecked(day: LocalDate): Boolean = db.settings().isDayChecked(day.toEpochDay()) > 0

    override suspend fun markDayChecked(day: LocalDate) {
        db.withTransaction {
            val alreadyChecked = db.settings().isDayChecked(day.toEpochDay()) > 0
            db.settings().markDayChecked(DayCheckEntity(day.toEpochDay()))
            if (!alreadyChecked) recorder.changed(SyncRecordType.DAY_CHECK, SyncRecordIds.dayCheck(day.toEpochDay()))
        }
    }
}
