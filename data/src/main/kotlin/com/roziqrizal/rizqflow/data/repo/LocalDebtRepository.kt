package com.roziqrizal.rizqflow.data.repo

import androidx.room.withTransaction
import com.roziqrizal.rizqflow.data.db.DebtEntity
import com.roziqrizal.rizqflow.data.db.DebtPaymentEntity
import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.data.sync.SyncRecorder
import com.roziqrizal.rizqflow.domain.debt.Debt
import com.roziqrizal.rizqflow.domain.debt.DebtPayment
import com.roziqrizal.rizqflow.domain.debt.DebtRepository
import com.roziqrizal.rizqflow.domain.model.AccountId
import com.roziqrizal.rizqflow.domain.model.TransactionId
import com.roziqrizal.rizqflow.domain.money.Money
import com.roziqrizal.rizqflow.domain.sync.SyncRecordType
import java.time.LocalDate

class LocalDebtRepository(private val db: RizqflowDatabase, private val recorder: SyncRecorder) : DebtRepository {
    override suspend fun all(): List<Debt> = db.debts().all().map { it.toDomain() }

    override suspend fun find(id: String): Debt? = db.debts().find(id)?.toDomain()

    override suspend fun save(debt: Debt) {
        db.withTransaction {
            db.debts().upsert(debt.toEntity())
            recorder.changed(SyncRecordType.DEBT, debt.id)
        }
    }

    override suspend fun delete(id: String) {
        db.withTransaction {
            // Pelunasan ikut terhapus lewat ON DELETE CASCADE; tiap pelunasan butuh tombstone sendiri.
            val paymentIds = db.debts().paymentIdsOf(id)
            if (db.debts().delete(id) > 0) {
                recorder.deleted(SyncRecordType.DEBT, id)
                recorder.deleted(SyncRecordType.DEBT_PAYMENT, paymentIds)
            }
        }
    }

    override suspend fun allPayments(): List<DebtPayment> = db.debts().allPayments().map { it.toDomain() }

    override suspend fun findPayment(id: String): DebtPayment? = db.debts().findPayment(id)?.toDomain()

    override suspend fun savePayment(payment: DebtPayment) {
        db.withTransaction {
            db.debts().upsertPayment(payment.toEntity())
            recorder.changed(SyncRecordType.DEBT_PAYMENT, payment.id)
        }
    }

    override suspend fun deletePayment(id: String) {
        db.withTransaction {
            if (db.debts().deletePayment(id) > 0) recorder.deleted(SyncRecordType.DEBT_PAYMENT, id)
        }
    }
}

internal fun DebtEntity.toDomain() = Debt(
    id = id,
    direction = direction,
    party = party,
    principal = money(principal, currency),
    accountId = accountId?.let(::AccountId),
    initialTransactionId = initialTransactionId?.let(::TransactionId),
    startDate = LocalDate.ofEpochDay(startDate),
    dueDate = dueDate?.let(LocalDate::ofEpochDay),
    note = note,
    collectible = collectible,
)

internal fun Debt.toEntity() = DebtEntity(
    id = id,
    direction = direction,
    party = party,
    principal = principal.minor,
    currency = principal.currency.code,
    accountId = accountId?.value,
    initialTransactionId = initialTransactionId?.value,
    startDate = startDate.toEpochDay(),
    dueDate = dueDate?.toEpochDay(),
    note = note,
    collectible = collectible,
)

internal fun DebtPaymentEntity.toDomain() = DebtPayment(
    id = id,
    debtId = debtId,
    // Pelunasan selalu sematanya utang (Rupiah saja untuk sekarang); kolom mata uang menunggu multi-mata uang.
    amount = Money.rupiah(amount),
    paidOn = LocalDate.ofEpochDay(paidOn),
    transactionId = transactionId?.let(::TransactionId),
)

internal fun DebtPayment.toEntity() = DebtPaymentEntity(
    id = id,
    debtId = debtId,
    amount = amount.minor,
    paidOn = paidOn.toEpochDay(),
    transactionId = transactionId?.value,
)
