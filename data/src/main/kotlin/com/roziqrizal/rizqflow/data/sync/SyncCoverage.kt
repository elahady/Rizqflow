package com.roziqrizal.rizqflow.data.sync

import com.roziqrizal.rizqflow.domain.sync.SyncRecordType

/**
 * Peta cakupan: setiap tabel di `RizqflowDatabase` harus punya entri di sini, dan tiap
 * [SyncRecordType] harus dicakup tepat oleh minimal satu tabel (Tahap 1 sync-desain.md bagian E).
 * `SyncCoverageTest` membaca daftar tabel dari skema dan GAGAL bila ada tabel yang tidak terdaftar,
 * sehingga menambah tabel tanpa memutuskan nasib sinkronnya (dicatat recorder, tertanam, atau
 * dikecualikan dengan alasan) tidak lolos.
 */
object SyncCoverage {
    sealed interface Entry {
        /** Tabel ini dicatat recorder sebagai [types]; [note] menjelaskan bila hanya sebagian barisnya. */
        data class Recorded(val types: Set<SyncRecordType>, val note: String? = null) : Entry

        /** Baris tabel ini ikut tertanam di rekaman [into] (tidak punya rekaman sendiri). */
        data class Embedded(val into: SyncRecordType, val reason: String) : Entry

        /** Tidak disinkronkan, dengan alasan tertulis. */
        data class Excluded(val reason: String) : Entry
    }

    val tables: Map<String, Entry> = mapOf(
        "room" to Entry.Recorded(setOf(SyncRecordType.ROOM)),
        "account" to Entry.Recorded(setOf(SyncRecordType.ACCOUNT), "kecuali akun anggota virtual (shared_from terisi) yang diturunkan dari cakupan bersama"),
        "category" to Entry.Recorded(setOf(SyncRecordType.CATEGORY)),
        "allocation_rule" to Entry.Recorded(setOf(SyncRecordType.ALLOCATION_CONFIG), "satu rekaman singleton bersama mode dan batas; total wajib 100% jadi atomik"),
        "money_transaction" to Entry.Recorded(setOf(SyncRecordType.TRANSACTION), "semua jenis; kecuali origin SHARED yang bukan milik cakupan pribadi"),
        "allocation_entry" to Entry.Embedded(SyncRecordType.TRANSACTION, "potret alokasi tertanam di payload pemasukan (satu rekaman atomik)"),
        "quick_favorite" to Entry.Recorded(setOf(SyncRecordType.QUICK_FAVORITE), "use_count dan last_used_at tidak ikut; perubahan pemakaian saja tidak menandai kotor"),
        "day_check" to Entry.Recorded(setOf(SyncRecordType.DAY_CHECK)),
        "zakat_profile" to Entry.Recorded(setOf(SyncRecordType.ZAKAT_PROFILE)),
        "wealth_item" to Entry.Recorded(setOf(SyncRecordType.WEALTH_ITEM)),
        "gold_price" to Entry.Recorded(setOf(SyncRecordType.GOLD_PRICE), "hanya source MANUAL; AUTO per perangkat (v1.1)"),
        "wealth_check" to Entry.Recorded(setOf(SyncRecordType.WEALTH_CHECK)),
        "zakat_payment" to Entry.Recorded(setOf(SyncRecordType.ZAKAT_PAYMENT)),
        "trader_profile" to Entry.Recorded(setOf(SyncRecordType.TRADER_PROFILE), "record_id = id ruang"),
        "dca_plan" to Entry.Recorded(setOf(SyncRecordType.DCA_PLAN), "record_id = id ruang"),
        "recurring_rule" to Entry.Recorded(setOf(SyncRecordType.RECURRING_RULE)),
        "bill" to Entry.Recorded(setOf(SyncRecordType.BILL)),
        "debt" to Entry.Recorded(setOf(SyncRecordType.DEBT)),
        "debt_payment" to Entry.Recorded(setOf(SyncRecordType.DEBT_PAYMENT)),
        "app_setting" to Entry.Recorded(
            setOf(SyncRecordType.ALLOCATION_CONFIG),
            "hanya kunci allocation_mode; pengingat, tema, penanda *_notified_*, onboarding dll milik perangkat",
        ),
        "sync_record" to Entry.Excluded("keadaan sinkron lokal, bukan data keuangan"),
        "sync_cursor" to Entry.Excluded("keadaan sinkron lokal, bukan data keuangan"),
        "sync_issue" to Entry.Excluded("keadaan sinkron lokal, bukan data keuangan"),
        "shared_room" to Entry.Excluded("keanggotaan ruang bersama di perangkat ini; tanpa rahasia, per perangkat"),
    )

    /** Tabel sistem SQLite/Room yang bukan tabel data. */
    val systemTables: Set<String> = setOf("room_master_table", "android_metadata", "sqlite_sequence")

    /** Tipe yang dicakup oleh minimal satu entri `Recorded`/`Embedded`. */
    fun coveredTypes(): Set<SyncRecordType> = tables.values.flatMapTo(mutableSetOf()) {
        when (it) {
            is Entry.Recorded -> it.types
            is Entry.Embedded -> setOf(it.into)
            is Entry.Excluded -> emptySet()
        }
    }
}
