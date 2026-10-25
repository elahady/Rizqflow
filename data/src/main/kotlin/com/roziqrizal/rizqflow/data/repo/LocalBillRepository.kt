package com.roziqrizal.rizqflow.data.repo

import com.roziqrizal.rizqflow.data.db.BillEntity
import androidx.room.withTransaction
import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.data.sync.SyncRecorder
import com.roziqrizal.rizqflow.domain.bill.Bill
import com.roziqrizal.rizqflow.domain.bill.BillRepository
import com.roziqrizal.rizqflow.domain.model.AccountId
import com.roziqrizal.rizqflow.domain.model.CategoryId
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.sync.SyncRecordType
import java.time.LocalDate

class LocalBillRepository(private val db: RizqflowDatabase, private val recorder: SyncRecorder) : BillRepository {
    override suspend fun all(): List<Bill> = db.bills().all().map { it.toDomain() }

    override suspend fun find(id: String): Bill? = db.bills().find(id)?.toDomain()

    override suspend fun save(bill: Bill) {
        db.withTransaction {
            db.bills().upsert(bill.toEntity())
            recorder.changed(SyncRecordType.BILL, bill.id)
        }
    }

    override suspend fun delete(id: String) {
        db.withTransaction {
            if (db.bills().delete(id) > 0) recorder.deleted(SyncRecordType.BILL, id)
        }
    }
}

internal fun BillEntity.toDomain() = Bill(
    id = id,
    name = name,
    amount = money(amount, currency),
    accountId = AccountId(accountId),
    roomId = RoomId(roomId),
    categoryId = CategoryId(categoryId),
    note = note,
    frequency = frequency,
    startDate = LocalDate.ofEpochDay(startDate),
    nextDue = LocalDate.ofEpochDay(nextDue),
    totalInstallments = totalInstallments,
    paidCount = paidCount,
    active = active,
)

internal fun Bill.toEntity() = BillEntity(
    id = id,
    name = name,
    amount = amount.minor,
    currency = amount.currency.code,
    accountId = accountId.value,
    roomId = roomId.value,
    categoryId = categoryId.value,
    note = note,
    frequency = frequency,
    startDate = startDate.toEpochDay(),
    nextDue = nextDue.toEpochDay(),
    totalInstallments = totalInstallments,
    paidCount = paidCount,
    active = active,
)
