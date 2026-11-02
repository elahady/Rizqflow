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

/**
 * Versi 7 ke 8 (2026-10-25, Tahap 1 sync-desain.md bagian E, USULAN Claude): menambah tabel sinkron
 * `sync_record`, `sync_cursor`, `sync_issue`, `shared_room`, dan kolom `account.shared_from`.
 * Aditif dan non-destruktif: empat tabel baru yang kosong dan satu kolom yang selalu null, tidak ada
 * baris lama yang ditulis ulang. `money_transaction.synced_at` (v7) dibiarkan karena SQLite API 26
 * tidak bisa DROP COLUMN dan SyncEngine lama masih memakainya. Pernyataannya sama dengan `createSql`
 * di berkas skema 8.json supaya skema hasil migrasi identik dengan skema yang dibuat dari nol.
 * Salinan berkas sebelum migrasi dibuat oleh `PreMigrateGuard` di LocalLedger.open.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sync_record` (`scope` TEXT NOT NULL, `record_type` TEXT NOT NULL, " +
                "`record_id` TEXT NOT NULL, `changed_at` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, " +
                "`dirty` INTEGER NOT NULL, `pushed_changed_at` INTEGER, `author` TEXT, `device_id` TEXT, " +
                "PRIMARY KEY(`scope`, `record_type`, `record_id`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_record_scope_dirty` ON `sync_record` (`scope`, `dirty`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sync_cursor` (`scope` TEXT NOT NULL, `after_id` INTEGER NOT NULL, " +
                "PRIMARY KEY(`scope`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sync_issue` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`scope` TEXT NOT NULL, `record_type` TEXT NOT NULL, `record_id` TEXT NOT NULL, " +
                "`server_id` INTEGER, `kind` TEXT NOT NULL, `detail` TEXT, `raw_row` TEXT, " +
                "`attempts` INTEGER NOT NULL, `created_at` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `shared_room` (`room_id` TEXT NOT NULL, `role` TEXT NOT NULL, " +
                "`status` TEXT NOT NULL, `joined_at` INTEGER NOT NULL, PRIMARY KEY(`room_id`), " +
                "FOREIGN KEY(`room_id`) REFERENCES `room`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("ALTER TABLE `account` ADD COLUMN `shared_from` TEXT DEFAULT NULL")
    }
}

/**
 * Versi 8 ke 9 (2026-10-26, Tahap 9, roadmap.md "Sinkron penghapusan transaksi di ruang yang
 * dibagi"): menambah tabel `pending_room_tombstone`, kosong dan non-destruktif. Pernyataannya sama
 * dengan `createSql` di berkas skema 9.json supaya skema hasil migrasi identik dengan skema yang
 * dibuat dari nol.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `pending_room_tombstone` (`transaction_id` TEXT NOT NULL, " +
                "`room_id` TEXT NOT NULL, `deleted_at` INTEGER NOT NULL, PRIMARY KEY(`transaction_id`), " +
                "FOREIGN KEY(`room_id`) REFERENCES `room`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_room_tombstone_room_id` ON `pending_room_tombstone` (`room_id`)")
    }
}

/**
 * Versi 9 ke 10 (2026-11-01, sync-desain.md bagian A "pindah keluar dari ruang bersama"): kunci utama
 * `pending_room_tombstone` berubah dari (transaction_id) menjadi (transaction_id, room_id), supaya satu
 * transaksi bisa menunggu tombstone di lebih dari satu ruang bersama (ia dipindah keluar dari ruang A lalu dari
 * ruang B sebelum sempat tersinkron). SQLite tidak bisa mengganti kunci utama di tempat, jadi tabel dibangun
 * ulang: tabel lama diganti nama, tabel baru dibuat dengan pernyataan yang sama persis dengan `createSql` di 10.json
 * (bukan `ALTER ... RENAME` dari tabel baru, supaya teks skema hasil migrasi identik dengan skema dari nol), isinya
 * disalin utuh, lalu tabel lama dibuang. Tidak ada baris yang hilang: kunci lama (transaction_id) unik, jadi
 * pasangan (transaction_id, room_id) juga unik. Tabel ini hanya berisi tombstone yang menunggu kirim, tidak ada
 * tabel lain yang merujuknya, dan `synced_at` maupun `sync_record` tidak disentuh.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP INDEX IF EXISTS `index_pending_room_tombstone_room_id`")
        db.execSQL("ALTER TABLE `pending_room_tombstone` RENAME TO `pending_room_tombstone_old`")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `pending_room_tombstone` (`transaction_id` TEXT NOT NULL, " +
                "`room_id` TEXT NOT NULL, `deleted_at` INTEGER NOT NULL, PRIMARY KEY(`transaction_id`, `room_id`), " +
                "FOREIGN KEY(`room_id`) REFERENCES `room`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_room_tombstone_room_id` ON `pending_room_tombstone` (`room_id`)")
        db.execSQL(
            "INSERT INTO `pending_room_tombstone` (`transaction_id`, `room_id`, `deleted_at`) " +
                "SELECT `transaction_id`, `room_id`, `deleted_at` FROM `pending_room_tombstone_old`",
        )
        db.execSQL("DROP TABLE `pending_room_tombstone_old`")
    }
}

/**
 * Migrasi 10 ke 11 (2026-11-02, Tahap 9, sync-desain.md butir 2.7): status "menunggu kirim" ruang bersama pindah dari
 * `money_transaction.synced_at` dan `pending_room_tombstone` ke `sync_record` (cakupan `r:<roomId>`, tipe `shared_expense`,
 * `record_id` = id transaksi lokal). TIDAK ada perubahan skema, hanya isi: aditif (`INSERT OR IGNORE`, idempoten), dan kolom
 * serta tabel lama dibiarkan apa adanya (tidak lagi dibaca), jadi data lama tetap utuh dan tidak ada yang dihapus.
 *
 * Hanya ruang yang pernah dibagi (punya baris `shared_room`) yang dijangkau:
 *  1. pengeluaran buatan sendiri (`kind = EXPENSE`, `origin <> SHARED`) di ruang itu: `synced_at` kosong menjadi kotor
 *     (menunggu kirim), terisi menjadi bersih dengan `pushed_changed_at` = `updated_at`;
 *  2. `pending_room_tombstone` menjadi tombstone kotor, kecuali transaksinya kini justru hidup di ruang yang sama (tombstone
 *     yang sudah gugur; versi hidup di langkah 1 yang berlaku).
 * Baris pasangan (`origin = SHARED`) tidak diberi rekaman: bukan milik perangkat ini. Rekaman `r:*` yang sudah ada
 * (mis. dari uji awal) dipertahankan. `changed_at` minimal 1 (0 dianggap belum pernah ditulis oleh SyncClock).
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT OR IGNORE INTO `sync_record` (`scope`, `record_type`, `record_id`, `changed_at`, `deleted`, `dirty`, " +
                "`pushed_changed_at`, `author`, `device_id`) " +
                "SELECT 'r:' || t.`room_id`, 'shared_expense', t.`id`, MAX(t.`updated_at`, 1), 0, " +
                "CASE WHEN t.`synced_at` IS NULL THEN 1 ELSE 0 END, " +
                "CASE WHEN t.`synced_at` IS NULL THEN NULL ELSE MAX(t.`updated_at`, 1) END, NULL, NULL " +
                "FROM `money_transaction` t " +
                "WHERE t.`kind` = 'EXPENSE' AND t.`origin` <> 'SHARED' AND t.`room_id` IN (SELECT `room_id` FROM `shared_room`)",
        )
        db.execSQL(
            "INSERT OR IGNORE INTO `sync_record` (`scope`, `record_type`, `record_id`, `changed_at`, `deleted`, `dirty`, " +
                "`pushed_changed_at`, `author`, `device_id`) " +
                "SELECT 'r:' || p.`room_id`, 'shared_expense', p.`transaction_id`, MAX(p.`deleted_at`, 1), 1, 1, NULL, NULL, NULL " +
                "FROM `pending_room_tombstone` p " +
                "WHERE p.`room_id` IN (SELECT `room_id` FROM `shared_room`) " +
                "AND NOT EXISTS (SELECT 1 FROM `money_transaction` t WHERE t.`id` = p.`transaction_id` " +
                "AND t.`room_id` = p.`room_id` AND t.`kind` = 'EXPENSE' AND t.`origin` <> 'SHARED')",
        )
    }
}

/**
 * Migrasi 11 ke 12 (2026-11-02, peran-anggota.md bagian E.2/H langkah 5 "Rincian terpakai per
 * anggota"): satu kolom aditif, `account.shared_author` (`author` baris sync pembuat akun virtual itu, sama
 * dengan `member_id` server). Akun virtual yang sudah ada sebelum migrasi ini tetap `NULL` sampai sync berikutnya
 * menyentuh baris yang sama — `LocalAccountRepository.findOrCreateVirtualMember` kini self-healing, mengisi kolom
 * ini juga untuk baris yang sudah ada, bukan hanya baris baru (id tidak berubah, hashnya tidak mengandung kolom ini).
 * Sampai terisi, akun itu digabung sebagai "anggota tidak dikenal" oleh `roomMemberBreakdown` — belum ada pengguna
 * sungguhan, jadi dampaknya nol di rilis 1.
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `account` ADD COLUMN `shared_author` TEXT DEFAULT NULL")
    }
}

/**
 * Migrasi 12 ke 13 (2026-11-02, peran-anggota.md bagian E.2/H langkah 8 "Amplop"): dua tabel baru, aditif saja,
 * tanpa menyentuh data lain.
 *
 * - `shared_member`: cache lokal daftar anggota ruang bersama ([SharedMemberEntity]), kosong sampai
 *   `RuangKeluargaScreen` memuat ulang anggota dan mengisinya (tidak ada backfill dari data lama: server yang
 *   jadi sumber kebenaran, bukan sesuatu yang sudah ada di perangkat).
 * - `shared_envelope`: proyeksi lokal rekaman amplop ([SharedEnvelopeEntity]), juga kosong sampai amplop pertama
 *   ditulis atau ditarik lewat sync.
 */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `shared_member` (`room_id` TEXT NOT NULL, `member_id` TEXT NOT NULL, " +
                "`label` TEXT NOT NULL, `role` TEXT NOT NULL, `status` TEXT NOT NULL, `is_me` INTEGER NOT NULL, " +
                "`updated_at` INTEGER NOT NULL, PRIMARY KEY(`room_id`, `member_id`), " +
                "FOREIGN KEY(`room_id`) REFERENCES `room`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_shared_member_room_id` ON `shared_member` (`room_id`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `shared_envelope` (`room_id` TEXT NOT NULL, `member_id` TEXT NOT NULL, " +
                "`from_month` INTEGER NOT NULL, `amount` INTEGER NOT NULL, " +
                "PRIMARY KEY(`room_id`, `member_id`, `from_month`), " +
                "FOREIGN KEY(`room_id`) REFERENCES `room`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_shared_envelope_room_id` ON `shared_envelope` (`room_id`)")
    }
}
