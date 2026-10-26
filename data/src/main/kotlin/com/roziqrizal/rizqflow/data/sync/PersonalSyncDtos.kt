package com.roziqrizal.rizqflow.data.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Payload JSON per tipe cakupan pribadi (Tahap 3 sync-desain.md bagian A dan C, R1 bagian B):
 * referensi antar-entitas berupa id lokal apa adanya, TANPA fallback nama. Ini mengisi `d` di
 * amplop [com.roziqrizal.rizqflow.domain.sync.SyncEnvelope]; `id` amplop sendiri (di luar `d`)
 * sudah membawa id rekaman itu sendiri, jadi tidak diulang di sini.
 */
internal val personalSyncJson = Json { ignoreUnknownKeys = true }

/** [com.roziqrizal.rizqflow.domain.sync.SyncRecordType.ROOM]: tidak merujuk entitas lain. */
@Serializable
internal data class RoomPayloadDto(
    val name: String,
    val kind: String,
    @SerialName("icon_key") val iconKey: String,
    @SerialName("color_slot") val colorSlot: Int,
    @SerialName("sort_order") val sortOrder: Int,
    val archived: Boolean,
    @SerialName("giving_mode") val givingMode: String? = null,
)

/** [com.roziqrizal.rizqflow.domain.sync.SyncRecordType.ACCOUNT]: tidak merujuk entitas lain. */
@Serializable
internal data class AccountPayloadDto(
    val name: String,
    val kind: String,
    val currency: String,
    @SerialName("opening_balance_minor") val openingBalanceMinor: Long,
    val archived: Boolean,
    @SerialName("sort_order") val sortOrder: Int,
    @SerialName("last_reconciled_on") val lastReconciledOnEpochDay: Long? = null,
)

/** [com.roziqrizal.rizqflow.domain.sync.SyncRecordType.CATEGORY]: merujuk [roomId] (R1, id apa adanya). */
@Serializable
internal data class CategoryPayloadDto(
    @SerialName("room_id") val roomId: String,
    val name: String,
    val weight: Int? = null,
    @SerialName("is_system") val isSystem: Boolean,
    val archived: Boolean,
    @SerialName("sort_order") val sortOrder: Int,
)

@Serializable
internal data class AllocationRuleEntryDto(@SerialName("room_id") val roomId: String, @SerialName("share_bp") val shareBp: Int)

@Serializable
internal data class AllocationCapEntryDto(@SerialName("room_id") val roomId: String, @SerialName("cap_amount_minor") val capAmountMinor: Long? = null)

/**
 * [com.roziqrizal.rizqflow.domain.sync.SyncRecordType.ALLOCATION_CONFIG]: satu rekaman singleton
 * atomik (bagian A); merujuk `room_id` di [rules] dan [caps] (R1, id apa adanya).
 */
@Serializable
internal data class AllocationConfigPayloadDto(
    val mode: String,
    val rules: List<AllocationRuleEntryDto> = emptyList(),
    val caps: List<AllocationCapEntryDto> = emptyList(),
)

@Serializable
internal data class AllocationEntryPayloadDto(
    val id: String,
    @SerialName("room_id") val roomId: String,
    @SerialName("share_bp") val shareBp: Int,
    @SerialName("amount_minor") val amountMinor: Long,
    @SerialName("allocated_at") val allocatedAtMillis: Long,
)

/**
 * [com.roziqrizal.rizqflow.domain.sync.SyncRecordType.TRANSACTION]: SEMUA jenis (EXPENSE, INCOME,
 * TRANSFER, LOAN_OUT/LOAN_IN); pemasukan menyertakan potret alokasi [entries] (bagian A, satu
 * rekaman atomik). Merujuk [accountId]/[toAccountId]/[roomId]/[categoryId] (R1, id apa adanya).
 */
@Serializable
internal data class PersonalTransactionPayloadDto(
    val kind: String,
    @SerialName("amount_minor") val amountMinor: Long,
    val currency: String,
    @SerialName("account_id") val accountId: String,
    @SerialName("to_account_id") val toAccountId: String? = null,
    @SerialName("room_id") val roomId: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("income_source") val incomeSource: String? = null,
    @SerialName("occurred_on") val occurredOnEpochDay: Long,
    val note: String? = null,
    @SerialName("created_at") val createdAtMillis: Long,
    /**
     * Cap waktu BISNIS entitas (`money_transaction.updated_at`), BUKAN `changed_at` amplop sinkron
     * (bagian D, dipakai mesin sinkron murni untuk LWW, tidak pernah ikut menimpa kolom bisnis).
     * Dibawa di payload sendiri supaya [com.roziqrizal.rizqflow.domain.sync.SyncRecordHandler.apply]
     * (yang hanya menerima `decoded`, tanpa parameter cap sinkron) bisa menulisnya ulang apa adanya.
     */
    @SerialName("updated_at") val updatedAtMillis: Long,
    val entries: List<AllocationEntryPayloadDto> = emptyList(),
)

internal inline fun <reified T> T.toJsonObject(serializer: kotlinx.serialization.SerializationStrategy<T>): JsonObject =
    personalSyncJson.encodeToJsonElement(serializer, this) as JsonObject

internal fun <T> JsonObject.decodeAs(serializer: kotlinx.serialization.DeserializationStrategy<T>): T =
    personalSyncJson.decodeFromJsonElement(serializer, this)
