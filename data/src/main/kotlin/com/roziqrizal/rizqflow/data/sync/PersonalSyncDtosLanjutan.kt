package com.roziqrizal.rizqflow.data.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Payload JSON entitas pribadi lanjutan (Tahap 4 sync-desain.md bagian A): zakat, harga emas MANUAL,
 * peran (trader/DCA), favorit, dan centang harian. Dipisah dari [PersonalSyncDtos.kt] supaya penambahan
 * entitas tahap ini tidak mengubah berkas yang sama dengan entitas lain. Sama seperti DTO sebelumnya,
 * referensi antar-entitas berupa id lokal apa adanya (R1 bagian B).
 */

/** [com.roziqrizal.rizqflow.domain.sync.SyncRecordType.ZAKAT_PROFILE]: tidak merujuk entitas lain. */
@Serializable
internal data class ZakatProfilePayloadDto(
    val name: String,
    @SerialName("haul_break_policy") val haulBreakPolicy: String,
    val archived: Boolean,
)

/** [com.roziqrizal.rizqflow.domain.sync.SyncRecordType.WEALTH_ITEM]: merujuk [profileId]. */
@Serializable
internal data class WealthItemPayloadDto(
    @SerialName("profile_id") val profileId: String,
    val kind: String,
    val label: String,
    @SerialName("value_minor") val valueMinor: Long,
    @SerialName("gold_milligrams") val goldMilligrams: Long? = null,
    @SerialName("updated_at") val updatedAtMillis: Long,
)

/** [com.roziqrizal.rizqflow.domain.sync.SyncRecordType.WEALTH_CHECK]: merujuk [profileId]. */
@Serializable
internal data class WealthCheckPayloadDto(
    @SerialName("profile_id") val profileId: String,
    val day: Long,
    @SerialName("net_wealth_minor") val netWealthMinor: Long,
    @SerialName("nisab_minor") val nisabMinor: Long,
)

/** [com.roziqrizal.rizqflow.domain.sync.SyncRecordType.ZAKAT_PAYMENT]: merujuk [profileId] dan [transactionId] (RESTRICT lokal). */
@Serializable
internal data class ZakatPaymentPayloadDto(
    @SerialName("profile_id") val profileId: String,
    val day: Long,
    @SerialName("transaction_id") val transactionId: String,
)

/**
 * [com.roziqrizal.rizqflow.domain.sync.SyncRecordType.GOLD_PRICE]: HANYA `MANUAL` (AUTO per perangkat, v1.1).
 * Tanggal ada di id rekaman (`g:<epochDay>`), bukan di payload.
 */
@Serializable
internal data class GoldPricePayloadDto(@SerialName("per_gram_minor") val perGramMinor: Long)

/**
 * [com.roziqrizal.rizqflow.domain.sync.SyncRecordType.TRADER_PROFILE]: record id = id ruang
 * (bagian A "kunci utama room_id"), jadi ruangnya tidak diulang di payload.
 */
@Serializable
internal data class TraderProfilePayloadDto(
    @SerialName("capital_minor") val capitalMinor: Long,
    @SerialName("risk_bp") val riskBp: Int,
)

/**
 * [com.roziqrizal.rizqflow.domain.sync.SyncRecordType.DCA_PLAN]: record id = id ruang. Merujuk
 * [accountId] dan [categoryId] (R1, id apa adanya).
 */
@Serializable
internal data class DcaPlanPayloadDto(
    @SerialName("amount_minor") val amountMinor: Long,
    @SerialName("day_of_month") val dayOfMonth: Int,
    @SerialName("account_id") val accountId: String,
    @SerialName("category_id") val categoryId: String,
)

/**
 * [com.roziqrizal.rizqflow.domain.sync.SyncRecordType.QUICK_FAVORITE]: `use_count` dan `last_used_at`
 * sengaja TIDAK ikut (bagian A); merujuk [roomId], [categoryId], [accountId].
 */
@Serializable
internal data class QuickFavoritePayloadDto(
    val name: String,
    @SerialName("amount_minor") val amountMinor: Long,
    @SerialName("room_id") val roomId: String,
    @SerialName("category_id") val categoryId: String,
    @SerialName("account_id") val accountId: String,
)

/**
 * [com.roziqrizal.rizqflow.domain.sync.SyncRecordType.DAY_CHECK]: record id = `d:<epochDay>`. Payload
 * harus tidak kosong (kosong = tombstone di [com.roziqrizal.rizqflow.domain.sync.SyncRecordHandler]).
 */
@Serializable
internal data class DayCheckPayloadDto(val day: Long)
