package com.roziqrizal.rizqflow.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Versi 1 ke 2 (2026-09-23): menambah `allocation_rule.cap_amount` (rupiah, boleh kosong) untuk
 * aturan alokasi lanjutan (Pro, docs/monetisasi.md). Aditif saja: baris lama tidak tersentuh,
 * kolom baru selalu null sampai pengguna mengatur batas atas.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE allocation_rule ADD COLUMN cap_amount INTEGER DEFAULT NULL")
    }
}

/**
 * Versi 2 ke 3 (2026-09-23): menambah `trader_profile` dan `dca_plan` untuk sistem per peran (Pro,
 * docs/monetisasi.md). Aditif saja: dua tabel baru yang kosong, tidak ada baris lama yang tersentuh.
 * Pernyataannya sama dengan `createSql` di berkas skema 3.json supaya skema hasil migrasi identik
 * dengan skema yang dibuat dari nol.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `trader_profile` (`room_id` TEXT NOT NULL, `capital` INTEGER NOT NULL, " +
                "`risk_bp` INTEGER NOT NULL, PRIMARY KEY(`room_id`), " +
                "FOREIGN KEY(`room_id`) REFERENCES `room`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `dca_plan` (`room_id` TEXT NOT NULL, `amount` INTEGER NOT NULL, " +
                "`day_of_month` INTEGER NOT NULL, `account_id` TEXT NOT NULL, `category_id` TEXT NOT NULL, " +
                "PRIMARY KEY(`room_id`), " +
                "FOREIGN KEY(`room_id`) REFERENCES `room`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`account_id`) REFERENCES `account`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , " +
                "FOREIGN KEY(`category_id`) REFERENCES `category`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_dca_plan_account_id` ON `dca_plan` (`account_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_dca_plan_category_id` ON `dca_plan` (`category_id`)")
    }
}

/**
 * Versi 3 ke 4 (2026-09-24): menambah `recurring_rule` untuk transaksi berulang (Tahap 10, Gratis).
 * Aditif saja: satu tabel baru yang kosong, tidak ada baris lama yang tersentuh. Pernyataannya
 * sama dengan `createSql` di berkas skema 4.json supaya skema hasil migrasi identik dengan skema
 * yang dibuat dari nol.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `recurring_rule` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                "`amount` INTEGER NOT NULL, `currency` TEXT NOT NULL, `account_id` TEXT NOT NULL, " +
                "`to_account_id` TEXT, `room_id` TEXT, `category_id` TEXT, `income_source` TEXT, `note` TEXT, " +
                "`frequency` TEXT NOT NULL, `start_date` INTEGER NOT NULL, `next_due` INTEGER NOT NULL, " +
                "`end_date` INTEGER, `active` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`account_id`) REFERENCES `account`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , " +
                "FOREIGN KEY(`to_account_id`) REFERENCES `account`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , " +
                "FOREIGN KEY(`room_id`) REFERENCES `room`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , " +
                "FOREIGN KEY(`category_id`) REFERENCES `category`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_rule_account_id` ON `recurring_rule` (`account_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_rule_to_account_id` ON `recurring_rule` (`to_account_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_rule_room_id` ON `recurring_rule` (`room_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_rule_category_id` ON `recurring_rule` (`category_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_rule_next_due` ON `recurring_rule` (`next_due`)")
    }
}

/**
 * Versi 4 ke 5 (2026-09-24): menambah `bill` untuk tagihan dan cicilan (Tahap 10, Gratis). Aditif
 * saja: satu tabel baru yang kosong, tidak ada baris lama yang tersentuh. Pernyataannya sama
 * dengan `createSql` di berkas skema 5.json supaya skema hasil migrasi identik dengan skema yang
 * dibuat dari nol.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `bill` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `amount` INTEGER NOT NULL, " +
                "`currency` TEXT NOT NULL, `account_id` TEXT NOT NULL, `room_id` TEXT NOT NULL, `category_id` TEXT NOT NULL, " +
                "`note` TEXT, `frequency` TEXT, `start_date` INTEGER NOT NULL, `next_due` INTEGER NOT NULL, " +
                "`total_installments` INTEGER, `paid_count` INTEGER NOT NULL, `active` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`account_id`) REFERENCES `account`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , " +
                "FOREIGN KEY(`room_id`) REFERENCES `room`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , " +
                "FOREIGN KEY(`category_id`) REFERENCES `category`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_bill_account_id` ON `bill` (`account_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_bill_room_id` ON `bill` (`room_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_bill_category_id` ON `bill` (`category_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_bill_next_due` ON `bill` (`next_due`)")
    }
}

/**
 * Versi 5 ke 6 (2026-09-24): menambah `debt` dan `debt_payment` untuk utang-piutang (Tahap 10, Gratis).
 * Aditif saja: dua tabel baru yang kosong, tidak ada baris lama yang tersentuh. Pernyataannya sama
 * dengan `createSql` di berkas skema 6.json supaya skema hasil migrasi identik dengan skema yang
 * dibuat dari nol.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `debt` (`id` TEXT NOT NULL, `direction` TEXT NOT NULL, `party` TEXT NOT NULL, " +
                "`principal` INTEGER NOT NULL, `currency` TEXT NOT NULL, `account_id` TEXT, `initial_transaction_id` TEXT, " +
                "`start_date` INTEGER NOT NULL, `due_date` INTEGER, `note` TEXT, `collectible` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`account_id`) REFERENCES `account`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , " +
                "FOREIGN KEY(`initial_transaction_id`) REFERENCES `money_transaction`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_debt_account_id` ON `debt` (`account_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_debt_initial_transaction_id` ON `debt` (`initial_transaction_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_debt_due_date` ON `debt` (`due_date`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `debt_payment` (`id` TEXT NOT NULL, `debt_id` TEXT NOT NULL, `amount` INTEGER NOT NULL, " +
                "`paid_on` INTEGER NOT NULL, `transaction_id` TEXT, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`debt_id`) REFERENCES `debt`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`transaction_id`) REFERENCES `money_transaction`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_debt_payment_debt_id` ON `debt_payment` (`debt_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_debt_payment_transaction_id` ON `debt_payment` (`transaction_id`)")
    }
}

/**
 * Versi 6 ke 7 (2026-10-24): menambah `money_transaction.synced_at` untuk sync ruang keluarga
 * (Tahap 9, roadmap.md "Ruang keluarga bersama"). Aditif saja: baris lama tidak tersentuh, kolom
 * baru selalu null (belum pernah terkirim ke sync) sampai `SyncEngine` menandainya terkirim —
 * lihat `TransactionRepository.pendingSync`/`markSynced` di `:domain`.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE money_transaction ADD COLUMN synced_at INTEGER DEFAULT NULL")
    }
}
