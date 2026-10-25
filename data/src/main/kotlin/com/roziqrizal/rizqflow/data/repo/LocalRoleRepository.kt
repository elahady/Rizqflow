package com.roziqrizal.rizqflow.data.repo

import androidx.room.withTransaction
import com.roziqrizal.rizqflow.data.db.DcaPlanEntity
import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.data.db.TraderProfileEntity
import com.roziqrizal.rizqflow.data.sync.SyncRecorder
import com.roziqrizal.rizqflow.domain.allocation.BasisPoints
import com.roziqrizal.rizqflow.domain.model.AccountId
import com.roziqrizal.rizqflow.domain.model.CategoryId
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.money.Money
import com.roziqrizal.rizqflow.domain.role.DcaPlan
import com.roziqrizal.rizqflow.domain.role.RoleRepository
import com.roziqrizal.rizqflow.domain.role.TraderProfile
import com.roziqrizal.rizqflow.domain.sync.SyncRecordType

class LocalRoleRepository(private val db: RizqflowDatabase, private val recorder: SyncRecorder) : RoleRepository {
    override suspend fun trader(roomId: RoomId): TraderProfile? = db.roles().trader(roomId.value)?.toDomain()

    override suspend fun saveTrader(profile: TraderProfile) {
        db.withTransaction {
            db.roles().upsertTrader(profile.toEntity())
            recorder.changed(SyncRecordType.TRADER_PROFILE, profile.roomId.value)
        }
    }

    override suspend fun dca(roomId: RoomId): DcaPlan? = db.roles().dca(roomId.value)?.toDomain()

    override suspend fun saveDca(plan: DcaPlan) {
        db.withTransaction {
            db.roles().upsertDca(plan.toEntity())
            recorder.changed(SyncRecordType.DCA_PLAN, plan.roomId.value)
        }
    }

    override suspend fun allDca(): List<DcaPlan> = db.roles().allDca().map { it.toDomain() }

    override suspend fun remove(roomId: RoomId) {
        db.withTransaction {
            if (db.roles().deleteTrader(roomId.value) > 0) recorder.deleted(SyncRecordType.TRADER_PROFILE, roomId.value)
            if (db.roles().deleteDca(roomId.value) > 0) recorder.deleted(SyncRecordType.DCA_PLAN, roomId.value)
        }
    }
}

internal fun TraderProfileEntity.toDomain() = TraderProfile(RoomId(roomId), Money.rupiah(capital), BasisPoints(riskBp))

internal fun TraderProfile.toEntity() = TraderProfileEntity(roomId.value, capital.minor, riskPerTrade.value)

internal fun DcaPlanEntity.toDomain() = DcaPlan(RoomId(roomId), Money.rupiah(amount), dayOfMonth, AccountId(accountId), CategoryId(categoryId))

internal fun DcaPlan.toEntity() = DcaPlanEntity(roomId.value, amount.minor, dayOfMonth, accountId.value, categoryId.value)
