package com.roziqrizal.rizqflow.data.sync

import com.roziqrizal.rizqflow.domain.ledger.MoneyTransaction
import com.roziqrizal.rizqflow.domain.model.TransactionId
import com.roziqrizal.rizqflow.domain.money.Currency
import com.roziqrizal.rizqflow.domain.money.Money
import com.roziqrizal.rizqflow.domain.sync.DecodedSyncTransaction
import com.roziqrizal.rizqflow.domain.sync.TransactionSyncCodec
import com.roziqrizal.rizqflow.domain.sync.TransactionSyncPayloadCorrupt
import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val syncJson = Json { ignoreUnknownKeys = true }

/** Implementasi [TransactionSyncCodec] sungguhan lewat kotlinx.serialization (Tahap 9). */
class JsonTransactionSyncCodec : TransactionSyncCodec {
    override fun encode(transaction: MoneyTransaction, accountName: String, categoryName: String?): ByteArray =
        syncJson.encodeToString(
            TransactionPayloadDto.serializer(),
            TransactionPayloadDto(
                id = transaction.id.value,
                amountMinor = transaction.amount.minor,
                currency = transaction.amount.currency.code,
                accountName = accountName,
                categoryName = categoryName,
                occurredOn = transaction.occurredOn.toEpochDay(),
                note = transaction.note,
            ),
        ).toByteArray()

    override fun decode(bytes: ByteArray): DecodedSyncTransaction {
        val dto = try {
            syncJson.decodeFromString(TransactionPayloadDto.serializer(), String(bytes))
        } catch (e: Exception) {
            throw TransactionSyncPayloadCorrupt()
        }
        return DecodedSyncTransaction(
            id = TransactionId(dto.id),
            amount = Money(dto.amountMinor, Currency.entries.first { it.code == dto.currency }),
            accountName = dto.accountName,
            categoryName = dto.categoryName,
            occurredOn = LocalDate.ofEpochDay(dto.occurredOn),
            note = dto.note,
        )
    }
}

@Serializable
private data class TransactionPayloadDto(
    val id: String,
    @SerialName("amount_minor") val amountMinor: Long,
    val currency: String,
    @SerialName("account_name") val accountName: String,
    @SerialName("category_name") val categoryName: String? = null,
    @SerialName("occurred_on") val occurredOn: Long,
    val note: String? = null,
)
