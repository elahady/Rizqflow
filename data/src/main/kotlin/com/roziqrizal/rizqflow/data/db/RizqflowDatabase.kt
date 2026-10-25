package com.roziqrizal.rizqflow.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Database lokal. Setiap perubahan skema wajib menaikkan [version], menambah migrasi, dan
 * punya tes migrasi; `fallbackToDestructiveMigration` dilarang (docs/model-data.md).
 *
 * Versi 2 (2026-09-23): menambah `allocation_rule.cap_amount` untuk aturan alokasi lanjutan
 * (Pro); lihat [MIGRATION_1_2] di Migrations.kt.
 *
 * Versi 3 (2026-09-23): menambah tabel `trader_profile` dan `dca_plan` untuk sistem per peran
 * (Pro); lihat [MIGRATION_2_3].
 *
 * Versi 4 (2026-09-24): menambah tabel `recurring_rule` untuk transaksi berulang (Tahap 10, Gratis);
 * lihat [MIGRATION_3_4].
 *
 * Versi 5 (2026-09-24): menambah tabel `bill` untuk tagihan dan cicilan (Tahap 10, Gratis);
 * lihat [MIGRATION_4_5].
 *
 * Versi 6 (2026-09-24): menambah tabel `debt` dan `debt_payment` untuk utang-piutang (Tahap 10, Gratis);
 * lihat [MIGRATION_5_6].
 *
 * Versi 7 (2026-10-24): menambah `money_transaction.synced_at` untuk sync ruang keluarga (Tahap 9);
 * lihat [MIGRATION_6_7].
 *
 * Versi 8 (2026-10-25, Tahap 1 sync-desain.md, USULAN Claude): menambah `sync_record`, `sync_cursor`,
 * `sync_issue`, `shared_room`, dan `account.shared_from` untuk sinkron entitas penuh; lihat
 * [MIGRATION_7_8]. Aditif saja; `money_transaction.synced_at` (v7, dipakai SyncEngine lama) dibiarkan.
 */
@Database(
    entities = [
        AccountEntity::class,
        RoomEntity::class,
        AllocationRuleEntity::class,
        CategoryEntity::class,
        TransactionEntity::class,
        AllocationEntryEntity::class,
        QuickFavoriteEntity::class,
        DayCheckEntity::class,
        AppSettingEntity::class,
        ZakatProfileEntity::class,
        WealthItemEntity::class,
        GoldPriceEntity::class,
        WealthCheckEntity::class,
        ZakatPaymentEntity::class,
        TraderProfileEntity::class,
        DcaPlanEntity::class,
        RecurringRuleEntity::class,
        BillEntity::class,
        DebtEntity::class,
        DebtPaymentEntity::class,
        SyncRecordEntity::class,
        SyncCursorEntity::class,
        SyncIssueEntity::class,
        SharedRoomEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class RizqflowDatabase : RoomDatabase() {
    abstract fun accounts(): AccountDao
    abstract fun rooms(): RoomDao
    abstract fun transactions(): TransactionDao
    abstract fun settings(): SettingsDao
    abstract fun favorites(): FavoriteDao
    abstract fun zakat(): ZakatDao
    abstract fun roles(): RoleDao
    abstract fun recurring(): RecurringDao
    abstract fun bills(): BillDao
    abstract fun debts(): DebtDao
    abstract fun sync(): SyncDao
}
