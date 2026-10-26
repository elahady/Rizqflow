package com.roziqrizal.rizqflow.data.repo

import com.roziqrizal.rizqflow.data.db.AccountEntity
import com.roziqrizal.rizqflow.data.db.AllocationEntryEntity
import com.roziqrizal.rizqflow.data.db.AllocationRuleEntity
import com.roziqrizal.rizqflow.data.db.CategoryEntity
import com.roziqrizal.rizqflow.data.db.QuickFavoriteEntity
import com.roziqrizal.rizqflow.data.db.RoomEntity
import com.roziqrizal.rizqflow.data.db.TransactionEntity
import com.roziqrizal.rizqflow.domain.allocation.AllocationRule
import com.roziqrizal.rizqflow.domain.allocation.BasisPoints
import com.roziqrizal.rizqflow.domain.ledger.Account
import com.roziqrizal.rizqflow.domain.ledger.AllocationEntry
import com.roziqrizal.rizqflow.domain.ledger.Category
import com.roziqrizal.rizqflow.domain.ledger.MoneyTransaction
import com.roziqrizal.rizqflow.domain.ledger.QuickFavorite
import com.roziqrizal.rizqflow.domain.ledger.Room
import com.roziqrizal.rizqflow.domain.model.AccountId
import com.roziqrizal.rizqflow.domain.model.CategoryId
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.model.TransactionId
import com.roziqrizal.rizqflow.domain.money.Currency
import com.roziqrizal.rizqflow.domain.money.Money
import java.time.LocalDate

/*
 * Pemeta entity Room dan model domain. Aturan penyimpanan (docs/model-data.md): uang berupa Long
 * satuan terkecil dengan kode mata uang terpisah, tanggal transaksi berupa epochDay, dan waktu
 * sistem berupa epochMillis.
 */

private fun currencyOf(code: String): Currency =
    Currency.entries.firstOrNull { it.code == code } ?: error("Mata uang tidak dikenal di database: $code")

internal fun money(minor: Long, code: String) = Money(minor, currencyOf(code))

internal fun AccountEntity.toDomain() = Account(
    id = AccountId(id),
    name = name,
    kind = kind,
    openingBalance = money(openingBalance, currency),
    archived = archived,
    sortOrder = sortOrder,
    lastReconciledOn = lastReconciledOn?.let(LocalDate::ofEpochDay),
    sharedFrom = sharedFrom?.let(::RoomId),
)

internal fun Account.toEntity() = AccountEntity(
    id = id.value,
    name = name,
    kind = kind,
    currency = currency.code,
    openingBalance = openingBalance.minor,
    archived = archived,
    sortOrder = sortOrder,
    lastReconciledOn = lastReconciledOn?.toEpochDay(),
    sharedFrom = sharedFrom?.value,
)

internal fun RoomEntity.toDomain() = Room(
    id = RoomId(id),
    name = name,
    kind = kind,
    iconKey = iconKey,
    colorSlot = colorSlot,
    sortOrder = sortOrder,
    archived = archived,
    givingMode = givingMode,
)

internal fun Room.toEntity() = RoomEntity(
    id = id.value,
    name = name,
    kind = kind,
    iconKey = iconKey,
    colorSlot = colorSlot,
    sortOrder = sortOrder,
    archived = archived,
    givingMode = givingMode,
)

internal fun CategoryEntity.toDomain() = Category(
    id = CategoryId(id),
    roomId = RoomId(roomId),
    name = name,
    weight = weight,
    isSystem = isSystem,
    archived = archived,
    sortOrder = sortOrder,
)

internal fun Category.toEntity() = CategoryEntity(
    id = id.value,
    roomId = roomId.value,
    name = name,
    weight = weight,
    isSystem = isSystem,
    archived = archived,
    sortOrder = sortOrder,
)

internal fun QuickFavoriteEntity.toDomain() = QuickFavorite(
    id = id,
    name = name,
    amount = Money.rupiah(amount),
    roomId = RoomId(roomId),
    categoryId = CategoryId(categoryId),
    accountId = AccountId(accountId),
    useCount = useCount,
    lastUsedAtMillis = lastUsedAt,
)

internal fun QuickFavorite.toEntity() = QuickFavoriteEntity(
    id = id,
    name = name,
    amount = amount.minor,
    roomId = roomId.value,
    categoryId = categoryId.value,
    accountId = accountId.value,
    useCount = useCount,
    lastUsedAt = lastUsedAtMillis,
)

internal fun AllocationRuleEntity.toDomain() = AllocationRule(RoomId(roomId), BasisPoints(shareBp))

internal fun AllocationRule.toEntity() = AllocationRuleEntity(roomId = roomId.value, shareBp = share.value)

internal fun TransactionEntity.toDomain() = MoneyTransaction(
    id = TransactionId(id),
    kind = kind,
    amount = money(amount, currency),
    accountId = AccountId(accountId),
    toAccountId = toAccountId?.let(::AccountId),
    roomId = roomId?.let(::RoomId),
    categoryId = categoryId?.let(::CategoryId),
    incomeSource = incomeSource,
    occurredOn = LocalDate.ofEpochDay(occurredOn),
    note = note,
    origin = origin,
    createdAtMillis = createdAt,
    updatedAtMillis = updatedAt,
)

internal fun MoneyTransaction.toEntity() = TransactionEntity(
    id = id.value,
    kind = kind,
    amount = amount.minor,
    currency = amount.currency.code,
    accountId = accountId.value,
    toAccountId = toAccountId?.value,
    roomId = roomId?.value,
    categoryId = categoryId?.value,
    incomeSource = incomeSource,
    occurredOn = occurredOn.toEpochDay(),
    note = note,
    origin = origin,
    createdAt = createdAtMillis,
    updatedAt = updatedAtMillis,
)

/** Mata uang potret alokasi selalu sama dengan mata uang pemasukannya, jadi dibawa dari transaksi. */
internal fun AllocationEntryEntity.toDomain(currency: Currency) = AllocationEntry(
    id = id,
    incomeId = TransactionId(incomeId),
    roomId = RoomId(roomId),
    share = BasisPoints(shareBp),
    amount = Money(amount, currency),
    allocatedAtMillis = allocatedAt,
)

internal fun AllocationEntry.toEntity() = AllocationEntryEntity(
    id = id,
    incomeId = incomeId.value,
    roomId = roomId.value,
    shareBp = share.value,
    amount = amount.minor,
    allocatedAt = allocatedAtMillis,
)
