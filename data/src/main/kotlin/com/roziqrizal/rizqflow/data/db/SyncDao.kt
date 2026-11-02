package com.roziqrizal.rizqflow.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert

/** Hasil [SyncDao.workspaceUsage]: satu kolom per tabel, nama kolom = nama tabel. */
data class WorkspaceUsageCounts(
    @ColumnInfo(name = "money_transaction") val moneyTransaction: Int,
    @ColumnInfo(name = "allocation_entry") val allocationEntry: Int,
    @ColumnInfo(name = "debt") val debt: Int,
    @ColumnInfo(name = "debt_payment") val debtPayment: Int,
    @ColumnInfo(name = "bill") val bill: Int,
    @ColumnInfo(name = "recurring_rule") val recurringRule: Int,
    @ColumnInfo(name = "zakat_profile") val zakatProfile: Int,
    @ColumnInfo(name = "wealth_item") val wealthItem: Int,
    @ColumnInfo(name = "wealth_check") val wealthCheck: Int,
    @ColumnInfo(name = "zakat_payment") val zakatPayment: Int,
    @ColumnInfo(name = "gold_price") val goldPrice: Int,
    @ColumnInfo(name = "trader_profile") val traderProfile: Int,
    @ColumnInfo(name = "dca_plan") val dcaPlan: Int,
    @ColumnInfo(name = "quick_favorite") val quickFavorite: Int,
    @ColumnInfo(name = "day_check") val dayCheck: Int,
) {
    /** Peta nama tabel ke jumlah baris, dasar pesan diagnosis dan tes cakupan. */
    fun byTable(): Map<String, Int> = mapOf(
        "money_transaction" to moneyTransaction,
        "allocation_entry" to allocationEntry,
        "debt" to debt,
        "debt_payment" to debtPayment,
        "bill" to bill,
        "recurring_rule" to recurringRule,
        "zakat_profile" to zakatProfile,
        "wealth_item" to wealthItem,
        "wealth_check" to wealthCheck,
        "zakat_payment" to zakatPayment,
        "gold_price" to goldPrice,
        "trader_profile" to traderProfile,
        "dca_plan" to dcaPlan,
        "quick_favorite" to quickFavorite,
        "day_check" to dayCheck,
    )
}

/** Penyimpanan sinkron lokal (Tahap 1 sync-desain.md): sync_record, sync_cursor, sync_issue, shared_room. */
@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_record WHERE scope = :scope AND record_type = :type AND record_id = :recordId")
    suspend fun find(scope: String, type: String, recordId: String): SyncRecordEntity?

    @Upsert
    suspend fun upsert(record: SyncRecordEntity)

    @Query("SELECT * FROM sync_record WHERE scope = :scope AND dirty = 1 ORDER BY changed_at, record_type, record_id LIMIT :limit")
    suspend fun dirty(scope: String, limit: Int): List<SyncRecordEntity>

    /** Sama seperti [dirty], disaring satu [type] saja (Tahap 3: setiap handler hanya butuh tipenya sendiri). */
    @Query("SELECT * FROM sync_record WHERE scope = :scope AND record_type = :type AND dirty = 1 ORDER BY changed_at, record_id LIMIT :limit")
    suspend fun dirtyOfType(scope: String, type: String, limit: Int): List<SyncRecordEntity>

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

    /**
     * Membersihkan penanda kotor satu rekaman tanpa mengubah `changed_at` atau `pushed_changed_at`. Dipakai saat versi
     * masuk dari perangkat lain menggantikan baris lokal yang masih menunggu kirim (versi lokal itu sudah kalah).
     */
    @Query("UPDATE sync_record SET dirty = 0 WHERE scope = :scope AND record_type = :type AND record_id = :recordId AND dirty = 1")
    suspend fun clearDirty(scope: String, type: String, recordId: String): Int

    /** Menandai rekaman ruang bersama (cakupan `r:*`) bertipe [type] dengan id-id ini terkirim, tanpa syarat versi. */
    @Query("UPDATE sync_record SET dirty = 0, pushed_changed_at = changed_at WHERE scope LIKE 'r:%' AND record_type = :type AND record_id IN (:ids)")
    suspend fun markSharedPushedByIds(type: String, ids: List<String>): Int

    /**
     * Riwayat pengeluaran buatan sendiri di ruang [roomId] yang belum punya rekaman `shared_expense` di cakupan [scope]
     * (`r:<roomId>`): ditandai kotor dengan `changed_at` = `updated_at` transaksi (minimal 1). `INSERT OR IGNORE`, jadi
     * rekaman yang sudah ada (terkirim, menunggu, atau tombstone) tidak tertimpa dan hasilnya idempoten.
     *
     * [sinceDay] null (bawaan dari pemanggil) menandai seluruh riwayat. Diisi (keputusan pemilik #4, opsi "mulai hari
     * ini"): hanya baris dengan `occurred_on` (tanggal transaksi, epoch day — BUKAN `updated_at`/waktu ditulis ke
     * database) >= [sinceDay] yang ditandai; konversi dari milidetik ke epoch day dilakukan pemanggil (`RoomSyncStore`).
     */
    @Query(
        """
        INSERT OR IGNORE INTO sync_record (scope, record_type, record_id, changed_at, deleted, dirty, pushed_changed_at, author, device_id)
        SELECT :scope, 'shared_expense', id, MAX(updated_at, 1), 0, 1, NULL, NULL, NULL
        FROM money_transaction
        WHERE room_id = :roomId AND kind = 'EXPENSE' AND origin <> 'SHARED' AND (:sinceDay IS NULL OR occurred_on >= :sinceDay)
        """,
    )
    suspend fun backfillSharedRoomExpenses(scope: String, roomId: String, sinceDay: Long?)

    @Query("SELECT after_id FROM sync_cursor WHERE scope = :scope")
    suspend fun cursor(scope: String): Long?

    @Upsert
    suspend fun upsertCursor(cursor: SyncCursorEntity)

    @Insert
    suspend fun insertIssue(issue: SyncIssueEntity): Long

    /**
     * Id masalah yang sudah mencatat BARIS SERVER yang sama (cakupan, tipe, id rekaman, jenis, dan id baris server).
     * Dipakai [com.roziqrizal.rizqflow.data.sync.RoomSyncStore.recordIssue] supaya menarik ulang baris yang sama
     * ("sinkron ulang penuh") tidak menggandakan catatan dan tidak mengembalikan hitungan umur/percobaannya ke nol.
     */
    @Query(
        """
        SELECT id FROM sync_issue
        WHERE scope = :scope AND record_type = :recordType AND record_id = :recordId AND kind = :kind AND server_id = :serverId
        ORDER BY id LIMIT 1
        """,
    )
    suspend fun findIssueForServerRow(scope: String, recordType: String, recordId: String, kind: String, serverId: Long): Long?

    @Query("DELETE FROM sync_issue WHERE id = :id")
    suspend fun deleteIssue(id: Long)

    @Query("UPDATE sync_issue SET attempts = attempts + 1 WHERE id = :id")
    suspend fun incrementAttempts(id: Long)

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

    /**
     * Membuang jejak `sync_record` hasil onboarding bawaan (`room`/`account`/`category`/`allocation_config`
     * cakupan pribadi) saat ruang kerja terbukti murni dan diganti data server (Tahap 3 sync-desain.md
     * bagian B "Perangkat baru", lihat `PureWorkspaceReplacer`). Baris server yang ditarik ulang nanti
     * masuk sebagai versi baru, tidak dibandingkan terhadap jejak onboarding yang sudah tidak ada gunanya.
     */
    @Query("DELETE FROM sync_record WHERE scope = 'a' AND record_type IN ('room', 'account', 'category', 'allocation_config')")
    suspend fun deletePureOnboardingRecords()

    /**
     * Jumlah baris per tabel "pemakaian nyata" untuk pemeriksaan kemurnian ruang kerja (Tahap 3/4
     * sync-desain.md bagian B "Perangkat baru", lihat `RoomWorkspacePurityChecker`). Harga emas hanya
     * `MANUAL` (harga `AUTO` milik perangkat, bukan isian pengguna). Profil zakat dihitung termasuk yang
     * diarsipkan.
     */
    @Query(
        """
        SELECT
            (SELECT COUNT(*) FROM money_transaction) AS money_transaction,
            (SELECT COUNT(*) FROM allocation_entry) AS allocation_entry,
            (SELECT COUNT(*) FROM debt) AS debt,
            (SELECT COUNT(*) FROM debt_payment) AS debt_payment,
            (SELECT COUNT(*) FROM bill) AS bill,
            (SELECT COUNT(*) FROM recurring_rule) AS recurring_rule,
            (SELECT COUNT(*) FROM zakat_profile) AS zakat_profile,
            (SELECT COUNT(*) FROM wealth_item) AS wealth_item,
            (SELECT COUNT(*) FROM wealth_check) AS wealth_check,
            (SELECT COUNT(*) FROM zakat_payment) AS zakat_payment,
            (SELECT COUNT(*) FROM gold_price WHERE source = 'MANUAL') AS gold_price,
            (SELECT COUNT(*) FROM trader_profile) AS trader_profile,
            (SELECT COUNT(*) FROM dca_plan) AS dca_plan,
            (SELECT COUNT(*) FROM quick_favorite) AS quick_favorite,
            (SELECT COUNT(*) FROM day_check) AS day_check
        """,
    )
    suspend fun workspaceUsage(): WorkspaceUsageCounts

    /*
     * Hapus semua data pribadi untuk jalur "Pulihkan" (ruang kerja TIDAK murni, setelah salinan otomatis dan
     * konfirmasi eksplisit; lihat `PureWorkspaceReplacer.wipeAllAndResetCursor`). Urutan pemanggilan menjaga FK
     * RESTRICT: anak lebih dulu, ruang dan akun terakhir. Harga emas AUTO dan pengaturan perangkat tidak disentuh.
     */

    @Query("DELETE FROM zakat_payment")
    suspend fun wipeZakatPayments()

    @Query("DELETE FROM wealth_check")
    suspend fun wipeWealthChecks()

    @Query("DELETE FROM wealth_item")
    suspend fun wipeWealthItems()

    @Query("DELETE FROM zakat_profile")
    suspend fun wipeZakatProfiles()

    @Query("DELETE FROM gold_price WHERE source = 'MANUAL'")
    suspend fun wipeManualGoldPrices()

    @Query("DELETE FROM debt_payment")
    suspend fun wipeDebtPayments()

    @Query("DELETE FROM debt")
    suspend fun wipeDebts()

    @Query("DELETE FROM bill")
    suspend fun wipeBills()

    @Query("DELETE FROM recurring_rule")
    suspend fun wipeRecurringRules()

    @Query("DELETE FROM dca_plan")
    suspend fun wipeDcaPlans()

    @Query("DELETE FROM trader_profile")
    suspend fun wipeTraderProfiles()

    @Query("DELETE FROM quick_favorite")
    suspend fun wipeQuickFavorites()

    @Query("DELETE FROM day_check")
    suspend fun wipeDayChecks()

    @Query("DELETE FROM allocation_entry")
    suspend fun wipeAllocationEntries()

    @Query("DELETE FROM money_transaction")
    suspend fun wipeTransactions()

    @Query("DELETE FROM sync_record")
    suspend fun wipeSyncRecords()

    @Query("DELETE FROM sync_issue")
    suspend fun wipeSyncIssues()

    @Query("DELETE FROM sync_cursor")
    suspend fun wipeSyncCursors()

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
