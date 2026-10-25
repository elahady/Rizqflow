package com.roziqrizal.rizqflow.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert

/** Penyimpanan sinkron lokal (Tahap 1 sync-desain.md): sync_record, sync_cursor, sync_issue, shared_room. */
@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_record WHERE scope = :scope AND record_type = :type AND record_id = :recordId")
    suspend fun find(scope: String, type: String, recordId: String): SyncRecordEntity?

    @Upsert
    suspend fun upsert(record: SyncRecordEntity)

    @Query("SELECT * FROM sync_record WHERE scope = :scope AND dirty = 1 ORDER BY changed_at, record_type, record_id LIMIT :limit")
    suspend fun dirty(scope: String, limit: Int): List<SyncRecordEntity>

    @Query("SELECT COUNT(*) FROM sync_record WHERE scope = :scope AND dirty = 1")
    suspend fun dirtyCount(scope: String): Int

    @Query("SELECT COUNT(*) FROM sync_record WHERE scope = :scope")
    suspend fun count(scope: String): Int

    /**
     * Mencatat [sentChangedAt] sebagai versi terkirim dan membersihkan penanda kotor HANYA bila
     * `changed_at` masih sama dengan yang dikirim (satu pernyataan atomik). Edit yang terjadi selama
     * push berjalan sudah menaikkan `changed_at`, jadi tetap kotor dan terkirim di putaran berikutnya.
     * Mengembalikan jumlah baris yang cocok kuncinya (bukan jumlah yang dibersihkan).
     */
    @Query(
        """
        UPDATE sync_record
        SET pushed_changed_at = MAX(COALESCE(pushed_changed_at, 0), :sentChangedAt),
            dirty = CASE WHEN changed_at = :sentChangedAt THEN 0 ELSE dirty END
        WHERE scope = :scope AND record_type = :type AND record_id = :recordId
        """,
    )
    suspend fun markPushed(scope: String, type: String, recordId: String, sentChangedAt: Long): Int

    @Query("SELECT after_id FROM sync_cursor WHERE scope = :scope")
    suspend fun cursor(scope: String): Long?

    @Upsert
    suspend fun upsertCursor(cursor: SyncCursorEntity)

    @Insert
    suspend fun insertIssue(issue: SyncIssueEntity): Long

    @Query("SELECT * FROM sync_issue ORDER BY id")
    suspend fun allIssues(): List<SyncIssueEntity>

    @Query("SELECT * FROM sync_issue WHERE scope = :scope ORDER BY id")
    suspend fun issuesOf(scope: String): List<SyncIssueEntity>

    @Query("DELETE FROM sync_issue WHERE created_at < :cutoff")
    suspend fun deleteIssuesOlderThan(cutoff: Long): Int

    /** Membuang baris tertua sampai tersisa paling banyak [keep]. */
    @Query("DELETE FROM sync_issue WHERE id NOT IN (SELECT id FROM sync_issue ORDER BY id DESC LIMIT :keep)")
    suspend fun trimIssues(keep: Int): Int

    @Query("SELECT * FROM shared_room WHERE room_id = :roomId")
    suspend fun sharedRoom(roomId: String): SharedRoomEntity?

    @Query("SELECT * FROM shared_room ORDER BY joined_at, room_id")
    suspend fun sharedRooms(): List<SharedRoomEntity>

    @Upsert
    suspend fun upsertSharedRoom(room: SharedRoomEntity)

    /*
     * Backfill SyncBootstrap.markAllDirty: satu pernyataan berbasis himpunan per tabel, INSERT OR IGNORE
     * supaya rekaman yang sudah punya baris (diubah sejak migrasi) tidak ditimpa dan hasilnya idempoten.
     * `changed_at` = cap waktu entitas bila ada (transaksi, harta), selain itu cap minimal 1 (lihat SyncBootstrap.BACKFILL_STAMP).
     */

    @Query(
        """
        INSERT OR IGNORE INTO sync_record (scope, record_type, record_id, changed_at, deleted, dirty, pushed_changed_at, author, device_id)
        SELECT 'a', 'room', id, 1, 0, 1, NULL, NULL, NULL FROM room
        """,
    )
    suspend fun backfillRooms()

    @Query(
        """
        INSERT OR IGNORE INTO sync_record (scope, record_type, record_id, changed_at, deleted, dirty, pushed_changed_at, author, device_id)
        SELECT 'a', 'account', id, 1, 0, 1, NULL, NULL, NULL FROM account WHERE shared_from IS NULL
        """,
    )
    suspend fun backfillAccounts()

    @Query(
        """
        INSERT OR IGNORE INTO sync_record (scope, record_type, record_id, changed_at, deleted, dirty, pushed_changed_at, author, device_id)
        SELECT 'a', 'category', id, 1, 0, 1, NULL, NULL, NULL FROM category
        """,
    )
    suspend fun backfillCategories()

    @Query(
        """
        INSERT OR IGNORE INTO sync_record (scope, record_type, record_id, changed_at, deleted, dirty, pushed_changed_at, author, device_id)
        SELECT 'a', 'allocation_config', 'allocation_config', 1, 0, 1, NULL, NULL, NULL
        WHERE EXISTS (SELECT 1 FROM allocation_rule) OR EXISTS (SELECT 1 FROM app_setting WHERE key = 'allocation_mode')
        """,
    )
    suspend fun backfillAllocationConfig()

    @Query(
        """
        INSERT OR IGNORE INTO sync_record (scope, record_type, record_id, changed_at, deleted, dirty, pushed_changed_at, author, device_id)
        SELECT 'a', 'transaction', id, updated_at, 0, 1, NULL, NULL, NULL FROM money_transaction WHERE origin <> 'SHARED'
        """,
    )
    suspend fun backfillTransactions()

    @Query(
        """
        INSERT OR IGNORE INTO sync_record (scope, record_type, record_id, changed_at, deleted, dirty, pushed_changed_at, author, device_id)
        SELECT 'a', 'debt', id, 1, 0, 1, NULL, NULL, NULL FROM debt
        """,
    )
    suspend fun backfillDebts()

    @Query(
        """
        INSERT OR IGNORE INTO sync_record (scope, record_type, record_id, changed_at, deleted, dirty, pushed_changed_at, author, device_id)
        SELECT 'a', 'debt_payment', id, 1, 0, 1, NULL, NULL, NULL FROM debt_payment
        """,
    )
    suspend fun backfillDebtPayments()

    @Query(
        """
        INSERT OR IGNORE INTO sync_record (scope, record_type, record_id, changed_at, deleted, dirty, pushed_changed_at, author, device_id)
        SELECT 'a', 'bill', id, 1, 0, 1, NULL, NULL, NULL FROM bill
        """,
    )
    suspend fun backfillBills()

    @Query(
        """
        INSERT OR IGNORE INTO sync_record (scope, record_type, record_id, changed_at, deleted, dirty, pushed_changed_at, author, device_id)
        SELECT 'a', 'recurring_rule', id, 1, 0, 1, NULL, NULL, NULL FROM recurring_rule
        """,
    )
    suspend fun backfillRecurringRules()

    @Query(
        """
        INSERT OR IGNORE INTO sync_record (scope, record_type, record_id, changed_at, deleted, dirty, pushed_changed_at, author, device_id)
        SELECT 'a', 'zakat_profile', id, 1, 0, 1, NULL, NULL, NULL FROM zakat_profile
        """,
    )
    suspend fun backfillZakatProfiles()

    @Query(
        """
        INSERT OR IGNORE INTO sync_record (scope, record_type, record_id, changed_at, deleted, dirty, pushed_changed_at, author, device_id)
        SELECT 'a', 'wealth_item', id, updated_at, 0, 1, NULL, NULL, NULL FROM wealth_item
        """,
    )
    suspend fun backfillWealthItems()

    @Query(
        """
        INSERT OR IGNORE INTO sync_record (scope, record_type, record_id, changed_at, deleted, dirty, pushed_changed_at, author, device_id)
        SELECT 'a', 'wealth_check', id, 1, 0, 1, NULL, NULL, NULL FROM wealth_check
        """,
    )
    suspend fun backfillWealthChecks()

    @Query(
        """
        INSERT OR IGNORE INTO sync_record (scope, record_type, record_id, changed_at, deleted, dirty, pushed_changed_at, author, device_id)
        SELECT 'a', 'zakat_payment', id, 1, 0, 1, NULL, NULL, NULL FROM zakat_payment
        """,
    )
    suspend fun backfillZakatPayments()

    @Query(
        """
        INSERT OR IGNORE INTO sync_record (scope, record_type, record_id, changed_at, deleted, dirty, pushed_changed_at, author, device_id)
        SELECT 'a', 'gold_price', 'g:' || day, 1, 0, 1, NULL, NULL, NULL FROM gold_price WHERE source = 'MANUAL'
        """,
    )
    suspend fun backfillGoldPrices()

    @Query(
        """
        INSERT OR IGNORE INTO sync_record (scope, record_type, record_id, changed_at, deleted, dirty, pushed_changed_at, author, device_id)
        SELECT 'a', 'trader_profile', room_id, 1, 0, 1, NULL, NULL, NULL FROM trader_profile
        """,
    )
    suspend fun backfillTraderProfiles()

    @Query(
        """
        INSERT OR IGNORE INTO sync_record (scope, record_type, record_id, changed_at, deleted, dirty, pushed_changed_at, author, device_id)
        SELECT 'a', 'dca_plan', room_id, 1, 0, 1, NULL, NULL, NULL FROM dca_plan
        """,
    )
    suspend fun backfillDcaPlans()

    @Query(
        """
        INSERT OR IGNORE INTO sync_record (scope, record_type, record_id, changed_at, deleted, dirty, pushed_changed_at, author, device_id)
        SELECT 'a', 'quick_favorite', id, 1, 0, 1, NULL, NULL, NULL FROM quick_favorite
        """,
    )
    suspend fun backfillQuickFavorites()

    @Query(
        """
        INSERT OR IGNORE INTO sync_record (scope, record_type, record_id, changed_at, deleted, dirty, pushed_changed_at, author, device_id)
        SELECT 'a', 'day_check', 'd:' || day, 1, 0, 1, NULL, NULL, NULL FROM day_check
        """,
    )
    suspend fun backfillDayChecks()
}
