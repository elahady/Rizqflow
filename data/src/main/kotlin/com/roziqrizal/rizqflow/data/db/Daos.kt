package com.roziqrizal.rizqflow.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.roziqrizal.rizqflow.domain.model.TransactionKind

/** Jumlah per ruang, hasil agregat bulanan. */
data class RoomTotal(
    @ColumnInfo(name = "room_id") val roomId: String,
    val total: Long,
)

@Dao
interface AccountDao {
    @Upsert
    suspend fun upsert(account: AccountEntity)

    @Upsert
    suspend fun upsertAll(accounts: List<AccountEntity>)

    @Query("SELECT * FROM account WHERE id = :id")
    suspend fun find(id: String): AccountEntity?

    @Query("SELECT COUNT(*) FROM account")
    suspend fun count(): Int

    // shared_from IS NULL (Tahap 2 sync-desain.md bagian E): akun anggota virtual tidak pernah
    // boleh muncul di daftar aktif (pemilih akun, saldo, rekonsiliasi, kekayaan bersih, ekspor pribadi).
    @Query("SELECT * FROM account WHERE archived = 0 AND shared_from IS NULL ORDER BY sort_order")
    suspend fun active(): List<AccountEntity>

    @Query("SELECT * FROM account ORDER BY sort_order")
    suspend fun all(): List<AccountEntity>

    /** Saldo menurut catatan: saldo awal + pemasukan - pengeluaran +/- transfer. */
    @Query(
        """
        SELECT a.opening_balance
             + COALESCE((SELECT SUM(amount) FROM money_transaction WHERE kind = 'INCOME' AND account_id = a.id), 0)
             - COALESCE((SELECT SUM(amount) FROM money_transaction WHERE kind = 'EXPENSE' AND account_id = a.id), 0)
             - COALESCE((SELECT SUM(amount) FROM money_transaction WHERE kind = 'TRANSFER' AND account_id = a.id), 0)
             + COALESCE((SELECT SUM(amount) FROM money_transaction WHERE kind = 'LOAN_IN' AND account_id = a.id), 0)
             - COALESCE((SELECT SUM(amount) FROM money_transaction WHERE kind = 'LOAN_OUT' AND account_id = a.id), 0)
             + COALESCE((SELECT SUM(amount) FROM money_transaction WHERE kind = 'TRANSFER' AND to_account_id = a.id), 0)
        FROM account a WHERE a.id = :accountId
        """,
    )
    suspend fun balance(accountId: String): Long
}

@Dao
interface RoomDao {
    @Upsert
    suspend fun upsert(room: RoomEntity)

    @Upsert
    suspend fun upsertAll(rooms: List<RoomEntity>)

    @Query("SELECT COUNT(*) FROM room")
    suspend fun count(): Int

    @Query("SELECT * FROM room WHERE id = :id")
    suspend fun find(id: String): RoomEntity?

    @Query("SELECT * FROM room WHERE archived = 0 ORDER BY sort_order")
    suspend fun active(): List<RoomEntity>

    @Query("SELECT * FROM room ORDER BY sort_order")
    suspend fun all(): List<RoomEntity>

    @Query("SELECT * FROM category ORDER BY room_id, sort_order")
    suspend fun allCategories(): List<CategoryEntity>

    @Upsert
    suspend fun upsertRule(rule: AllocationRuleEntity)

    @Upsert
    suspend fun upsertRules(rules: List<AllocationRuleEntity>)

    /** Aturan ruang aktif saja; aturan ruang terarsip tidak disentuh supaya persentase lamanya tidak hilang. */
    @Query("DELETE FROM allocation_rule WHERE room_id IN (SELECT id FROM room WHERE archived = 0)")
    suspend fun clearActiveRules()

    @Query("SELECT r.* FROM allocation_rule r JOIN room ON room.id = r.room_id WHERE room.archived = 0 ORDER BY room.sort_order")
    suspend fun rules(): List<AllocationRuleEntity>

    @Upsert
    suspend fun upsertCategory(category: CategoryEntity)

    @Upsert
    suspend fun upsertCategories(categories: List<CategoryEntity>)

    @Query("SELECT * FROM category WHERE id = :id")
    suspend fun findCategory(id: String): CategoryEntity?

    @Query("SELECT * FROM category WHERE room_id = :roomId AND archived = 0 ORDER BY sort_order")
    suspend fun categories(roomId: String): List<CategoryEntity>
}

@Dao
interface TransactionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(transaction: TransactionEntity)

    @Update
    suspend fun update(transaction: TransactionEntity): Int

    @Query("SELECT * FROM money_transaction WHERE id = :id")
    suspend fun find(id: String): TransactionEntity?

    /** Alokasi ikut terhapus lewat ON DELETE CASCADE. */
    @Query("DELETE FROM money_transaction WHERE id = :id")
    suspend fun delete(id: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEntries(entries: List<AllocationEntryEntity>)

    @Query("DELETE FROM allocation_entry WHERE income_id = :incomeId")
    suspend fun deleteEntries(incomeId: String)

    @Query("SELECT * FROM money_transaction ORDER BY created_at DESC, rowid DESC LIMIT 1")
    suspend fun latest(): TransactionEntity?

    @Query("SELECT * FROM money_transaction WHERE kind = :kind ORDER BY created_at DESC, rowid DESC LIMIT 1")
    suspend fun latestOfKind(kind: TransactionKind): TransactionEntity?

    /** Ruang dari pengeluaran terakhir di akun ini; dasar ruang usulan Koreksi saldo (S25). */
    @Query("SELECT room_id FROM money_transaction WHERE kind = 'EXPENSE' AND account_id = :accountId ORDER BY created_at DESC, rowid DESC LIMIT 1")
    suspend fun latestExpenseRoom(accountId: String): String?

    @Query("SELECT * FROM money_transaction WHERE occurred_on BETWEEN :fromDay AND :toDay ORDER BY occurred_on DESC, created_at DESC")
    suspend fun between(fromDay: Long, toDay: Long): List<TransactionEntity>

    /** Berurutan menurut prioritas ruang, supaya hitung ulang memakai pemutus seri yang sama. */
    @Query(
        """
        SELECT e.* FROM allocation_entry e JOIN room ON room.id = e.room_id
        WHERE e.income_id = :incomeId
        ORDER BY room.sort_order
        """,
    )
    suspend fun entriesOf(incomeId: String): List<AllocationEntryEntity>

    /** Jatah per ruang: jumlah alokasi dari pemasukan yang terjadi antara dua hari (inklusif). */
    @Query(
        """
        SELECT e.room_id AS room_id, SUM(e.amount) AS total
        FROM allocation_entry e JOIN money_transaction t ON t.id = e.income_id
        WHERE t.occurred_on BETWEEN :fromDay AND :toDay
        GROUP BY e.room_id
        """,
    )
    suspend fun allocatedPerRoom(fromDay: Long, toDay: Long): List<RoomTotal>

    /** Terpakai per ruang: jumlah pengeluaran antara dua hari (inklusif). */
    @Query(
        """
        SELECT room_id AS room_id, SUM(amount) AS total
        FROM money_transaction
        WHERE kind = 'EXPENSE' AND room_id IS NOT NULL AND occurred_on BETWEEN :fromDay AND :toDay
        GROUP BY room_id
        """,
    )
    suspend fun spentPerRoom(fromDay: Long, toDay: Long): List<RoomTotal>

    /** Pengeluaran di [roomId] yang belum terkirim ke sync (Tahap 9) — lihat `TransactionRepository.pendingSync`. */
    @Query("SELECT * FROM money_transaction WHERE room_id = :roomId AND kind = 'EXPENSE' AND synced_at IS NULL")
    suspend fun pendingSync(roomId: String): List<TransactionEntity>

    @Query("UPDATE money_transaction SET synced_at = :atMillis WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>, atMillis: Long)

    /** Insert atau timpa hasil pull dari anggota lain, dengan `synced_at` sudah terisi (tidak pending push lagi). */
    @Upsert
    suspend fun upsertFromSync(transaction: TransactionEntity)

    /** Tombstone menunggu kirim (Tahap 9) — lihat `PendingRoomTombstoneEntity`. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPendingTombstone(tombstone: PendingRoomTombstoneEntity)

    @Query("SELECT * FROM pending_room_tombstone WHERE room_id = :roomId")
    suspend fun pendingTombstones(roomId: String): List<PendingRoomTombstoneEntity>

    @Query("DELETE FROM pending_room_tombstone WHERE transaction_id IN (:ids)")
    suspend fun clearPendingTombstones(ids: List<String>)
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM quick_favorite ORDER BY use_count DESC, COALESCE(last_used_at, 0) DESC, name COLLATE NOCASE")
    suspend fun all(): List<QuickFavoriteEntity>

    @Query("SELECT * FROM quick_favorite WHERE id = :id")
    suspend fun find(id: String): QuickFavoriteEntity?

    @Upsert
    suspend fun upsert(favorite: QuickFavoriteEntity)

    @Query("DELETE FROM quick_favorite WHERE id = :id")
    suspend fun delete(id: String): Int
}

@Dao
interface ZakatDao {
    /** Berurutan menurut pembuatan (rowid naik): profil pertama adalah "Utama". */
    @Query("SELECT * FROM zakat_profile WHERE archived = 0 ORDER BY rowid")
    suspend fun activeProfiles(): List<ZakatProfileEntity>

    @Query("SELECT * FROM zakat_profile WHERE id = :id")
    suspend fun profile(id: String): ZakatProfileEntity?

    @Upsert
    suspend fun upsertProfile(profile: ZakatProfileEntity)

    @Query("SELECT * FROM wealth_item WHERE profile_id = :profileId")
    suspend fun items(profileId: String): List<WealthItemEntity>

    @Query("DELETE FROM wealth_item WHERE profile_id = :profileId")
    suspend fun clearItems(profileId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertItems(items: List<WealthItemEntity>)

    @Query("SELECT * FROM gold_price ORDER BY day DESC LIMIT 1")
    suspend fun latestGoldPrice(): GoldPriceEntity?

    @Upsert
    suspend fun upsertGoldPrice(price: GoldPriceEntity)

    @Query("SELECT * FROM gold_price WHERE day = :day")
    suspend fun findGoldPrice(day: Long): GoldPriceEntity?

    @Query("SELECT * FROM wealth_check WHERE profile_id = :profileId ORDER BY day")
    suspend fun checks(profileId: String): List<WealthCheckEntity>

    /** Satu pemeriksaan per hari: baris hari yang sama dihapus dulu supaya tidak menumpuk. */
    @Query("DELETE FROM wealth_check WHERE profile_id = :profileId AND day = :day")
    suspend fun clearCheckOfDay(profileId: String, day: Long)

    @Query("SELECT id FROM wealth_check WHERE profile_id = :profileId AND day = :day")
    suspend fun checkIdsOfDay(profileId: String, day: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCheck(check: WealthCheckEntity)

    @Query("SELECT * FROM zakat_payment WHERE profile_id = :profileId ORDER BY day DESC")
    suspend fun payments(profileId: String): List<ZakatPaymentEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPayment(payment: ZakatPaymentEntity)
}

@Dao
interface RoleDao {
    @Query("SELECT * FROM trader_profile WHERE room_id = :roomId")
    suspend fun trader(roomId: String): TraderProfileEntity?

    @Upsert
    suspend fun upsertTrader(profile: TraderProfileEntity)

    @Query("SELECT * FROM dca_plan WHERE room_id = :roomId")
    suspend fun dca(roomId: String): DcaPlanEntity?

    @Upsert
    suspend fun upsertDca(plan: DcaPlanEntity)

    @Query("SELECT * FROM dca_plan")
    suspend fun allDca(): List<DcaPlanEntity>

    @Query("DELETE FROM trader_profile WHERE room_id = :roomId")
    suspend fun deleteTrader(roomId: String): Int

    @Query("DELETE FROM dca_plan WHERE room_id = :roomId")
    suspend fun deleteDca(roomId: String): Int
}

@Dao
interface SettingsDao {
    @Upsert
    suspend fun put(setting: AppSettingEntity)

    @Query("SELECT value FROM app_setting WHERE key = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun markDayChecked(check: DayCheckEntity)

    @Query("SELECT COUNT(*) FROM day_check WHERE day = :day")
    suspend fun isDayChecked(day: Long): Int
}

@Dao
interface RecurringDao {
    @Query("SELECT * FROM recurring_rule ORDER BY next_due, id")
    suspend fun all(): List<RecurringRuleEntity>

    @Query("SELECT * FROM recurring_rule WHERE id = :id")
    suspend fun find(id: String): RecurringRuleEntity?

    @Upsert
    suspend fun upsert(rule: RecurringRuleEntity)

    @Query("DELETE FROM recurring_rule WHERE id = :id")
    suspend fun delete(id: String): Int
}

@Dao
interface BillDao {
    @Query("SELECT * FROM bill ORDER BY next_due, id")
    suspend fun all(): List<BillEntity>

    @Query("SELECT * FROM bill WHERE id = :id")
    suspend fun find(id: String): BillEntity?

    @Upsert
    suspend fun upsert(bill: BillEntity)

    @Query("DELETE FROM bill WHERE id = :id")
    suspend fun delete(id: String): Int
}

@Dao
interface DebtDao {
    @Query("SELECT * FROM debt ORDER BY start_date, id")
    suspend fun all(): List<DebtEntity>

    @Query("SELECT * FROM debt WHERE id = :id")
    suspend fun find(id: String): DebtEntity?

    @Upsert
    suspend fun upsert(debt: DebtEntity)

    @Query("DELETE FROM debt WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("SELECT * FROM debt_payment ORDER BY paid_on, id")
    suspend fun allPayments(): List<DebtPaymentEntity>

    @Query("SELECT * FROM debt_payment WHERE id = :id")
    suspend fun findPayment(id: String): DebtPaymentEntity?

    @Query("SELECT id FROM debt_payment WHERE debt_id = :debtId")
    suspend fun paymentIdsOf(debtId: String): List<String>

    /** Utang yang transaksi awalnya [transactionId]; kolomnya jadi NULL (SET NULL) bila transaksi itu dihapus. */
    @Query("SELECT id FROM debt WHERE initial_transaction_id = :transactionId")
    suspend fun idsByInitialTransaction(transactionId: String): List<String>

    /** Pelunasan yang transaksinya [transactionId]; kolomnya jadi NULL (SET NULL) bila transaksi itu dihapus. */
    @Query("SELECT id FROM debt_payment WHERE transaction_id = :transactionId")
    suspend fun paymentIdsByTransaction(transactionId: String): List<String>

    @Upsert
    suspend fun upsertPayment(payment: DebtPaymentEntity)

    @Query("DELETE FROM debt_payment WHERE id = :id")
    suspend fun deletePayment(id: String): Int
}
