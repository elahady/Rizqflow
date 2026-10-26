package com.roziqrizal.rizqflow.data.repo

import androidx.room.withTransaction
import com.roziqrizal.rizqflow.data.db.AppSettingEntity
import com.roziqrizal.rizqflow.data.db.AllocationRuleEntity
import com.roziqrizal.rizqflow.data.db.PendingRoomTombstoneEntity
import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.data.sync.SharedRoomAutoSync
import com.roziqrizal.rizqflow.data.sync.SyncRecorder
import com.roziqrizal.rizqflow.domain.allocation.AllocationCap
import com.roziqrizal.rizqflow.domain.allocation.AllocationMode
import com.roziqrizal.rizqflow.domain.allocation.AllocationRule
import com.roziqrizal.rizqflow.domain.ledger.Account
import com.roziqrizal.rizqflow.domain.ledger.AccountRepository
import com.roziqrizal.rizqflow.domain.ledger.AllocationEntry
import com.roziqrizal.rizqflow.domain.ledger.Category
import com.roziqrizal.rizqflow.domain.ledger.FavoriteRepository
import com.roziqrizal.rizqflow.domain.ledger.QuickFavorite
import com.roziqrizal.rizqflow.domain.ledger.MoneyTransaction
import com.roziqrizal.rizqflow.domain.ledger.PendingSyncTransaction
import com.roziqrizal.rizqflow.domain.ledger.PendingTombstone
import com.roziqrizal.rizqflow.domain.ledger.Room
import com.roziqrizal.rizqflow.domain.ledger.RoomRepository
import com.roziqrizal.rizqflow.domain.ledger.RoomTotals
import com.roziqrizal.rizqflow.domain.ledger.TransactionRepository
import com.roziqrizal.rizqflow.domain.ledger.WorkspaceRepository
import com.roziqrizal.rizqflow.domain.ledger.WorkspaceSnapshot
import com.roziqrizal.rizqflow.domain.model.AccountId
import com.roziqrizal.rizqflow.domain.model.CategoryId
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.model.TransactionId
import com.roziqrizal.rizqflow.domain.model.TransactionKind
import com.roziqrizal.rizqflow.domain.model.TransactionOrigin
import com.roziqrizal.rizqflow.domain.money.Money
import com.roziqrizal.rizqflow.domain.sync.SyncRecordIds
import com.roziqrizal.rizqflow.domain.sync.SyncRecordType
import java.time.LocalDate

/*
 * Implementasi repositori di atas Room. Setiap fungsi yang menulis lebih dari satu baris
 * dibungkus satu transaksi database: gagal di tengah membatalkan semuanya.
 *
 * Sinkron entitas penuh (Tahap 1 sync-desain.md): setiap jalur tulis memanggil [SyncRecorder] di
 * transaksi yang sama, jadi data dan penanda kotor/tombstone-nya tersimpan atau batal bersama. Di
 * konteks `applying` (hasil pull sedang diterapkan) recorder tidak mencatat apa pun.
 */

/** Kunci `app_setting` mode alokasi; bagian dari rekaman ALLOCATION_CONFIG, bukan pengaturan perangkat. */
internal const val ALLOCATION_MODE_KEY = "allocation_mode"

class LocalWorkspaceRepository(private val db: RizqflowDatabase, private val recorder: SyncRecorder) : WorkspaceRepository {
    override suspend fun isEmpty(): Boolean = db.rooms().count() == 0 && db.accounts().count() == 0

    override suspend fun initialize(snapshot: WorkspaceSnapshot) {
        db.withTransaction {
            // Urutan mengikuti kunci asing: ruang lebih dulu, baru kategori dan aturan.
            snapshot.rooms.forEach { db.rooms().upsert(it.toEntity()) }
            db.rooms().upsertCategories(snapshot.categories.map(Category::toEntity))
            db.rooms().upsertRules(snapshot.rules.map(AllocationRule::toEntity))
            db.accounts().upsertAll(snapshot.accounts.map(Account::toEntity))
            recorder.changed(SyncRecordType.ROOM, snapshot.rooms.map { it.id.value })
            recorder.changed(SyncRecordType.CATEGORY, snapshot.categories.map { it.id.value })
            recorder.changed(SyncRecordType.ACCOUNT, snapshot.accounts.map { it.id.value })
            if (snapshot.rules.isNotEmpty()) recorder.changed(SyncRecordType.ALLOCATION_CONFIG, SyncRecordIds.ALLOCATION_CONFIG)
        }
    }
}

class LocalAccountRepository(private val db: RizqflowDatabase, private val recorder: SyncRecorder) : AccountRepository {
    override suspend fun find(id: AccountId): Account? = db.accounts().find(id.value)?.toDomain()

    override suspend fun activeAccounts(): List<Account> = db.accounts().active().map { it.toDomain() }

    override suspend fun allAccounts(): List<Account> = db.accounts().all().map { it.toDomain() }

    override suspend fun save(account: Account) {
        db.withTransaction {
            db.accounts().upsert(account.toEntity())
            recorder.changed(SyncRecordType.ACCOUNT, account.id.value)
        }
    }

    override suspend fun balance(id: AccountId): Money {
        val account = requireNotNull(find(id)) { "Akun tidak ada: ${id.value}" }
        return Money(db.accounts().balance(id.value), account.currency)
    }
}

class LocalFavoriteRepository(private val db: RizqflowDatabase, private val recorder: SyncRecorder) : FavoriteRepository {
    override suspend fun all(): List<QuickFavorite> = db.favorites().all().map { it.toDomain() }

    override suspend fun find(id: String): QuickFavorite? = db.favorites().find(id)?.toDomain()

    override suspend fun save(favorite: QuickFavorite) {
        db.withTransaction {
            val entity = favorite.toEntity()
            val old = db.favorites().find(favorite.id)
            db.favorites().upsert(entity)
            // use_count dan last_used_at tidak ikut sinkron: perubahan pemakaian saja tidak menandai kotor.
            if (old == null || old.copy(useCount = entity.useCount, lastUsedAt = entity.lastUsedAt) != entity) {
                recorder.changed(SyncRecordType.QUICK_FAVORITE, favorite.id)
            }
        }
    }

    override suspend fun delete(id: String) {
        db.withTransaction {
            if (db.favorites().delete(id) > 0) recorder.deleted(SyncRecordType.QUICK_FAVORITE, id)
        }
    }
}

class LocalRoomRepository(private val db: RizqflowDatabase, private val recorder: SyncRecorder) : RoomRepository {
    override suspend fun activeRooms(): List<Room> = db.rooms().active().map { it.toDomain() }

    override suspend fun allRooms(): List<Room> = db.rooms().all().map { it.toDomain() }

    override suspend fun allCategories(): List<Category> = db.rooms().allCategories().map { it.toDomain() }

    override suspend fun find(id: RoomId): Room? = db.rooms().find(id.value)?.toDomain()

    override suspend fun rules(): List<AllocationRule> = db.rooms().rules().map { it.toDomain() }

    override suspend fun categories(roomId: RoomId): List<Category> = db.rooms().categories(roomId.value).map { it.toDomain() }

    override suspend fun findCategory(id: CategoryId): Category? = db.rooms().findCategory(id.value)?.toDomain()

    override suspend fun saveCategory(category: Category) {
        db.withTransaction {
            db.rooms().upsertCategory(category.toEntity())
            recorder.changed(SyncRecordType.CATEGORY, category.id.value)
        }
    }

    override suspend fun addRoom(room: Room, categories: List<Category>) {
        db.withTransaction {
            db.rooms().upsert(room.toEntity())
            db.rooms().upsertCategories(categories.map(Category::toEntity))
            recorder.changed(SyncRecordType.ROOM, room.id.value)
            recorder.changed(SyncRecordType.CATEGORY, categories.map { it.id.value })
        }
    }

    override suspend fun replaceRules(rules: List<AllocationRule>) {
        db.withTransaction {
            // Cap_amount ruang lanjutan (Pro) dibawa serta supaya mengubah persentase tidak menghapusnya.
            val capsByRoom = db.rooms().rules().associate { it.roomId to it.capAmount }
            db.rooms().clearActiveRules()
            db.rooms().upsertRules(rules.map { AllocationRuleEntity(roomId = it.roomId.value, shareBp = it.share.value, capAmount = capsByRoom[it.roomId.value]) })
            recorder.changed(SyncRecordType.ALLOCATION_CONFIG, SyncRecordIds.ALLOCATION_CONFIG)
        }
    }

    override suspend fun allocationMode(): AllocationMode {
        val raw = db.settings().get(ALLOCATION_MODE_KEY)
        return AllocationMode.entries.firstOrNull { it.name == raw } ?: AllocationMode.PERCENTAGE
    }

    override suspend fun setAllocationMode(mode: AllocationMode) {
        db.withTransaction {
            db.settings().put(AppSettingEntity(ALLOCATION_MODE_KEY, mode.name))
            recorder.changed(SyncRecordType.ALLOCATION_CONFIG, SyncRecordIds.ALLOCATION_CONFIG)
        }
    }

    override suspend fun caps(): List<AllocationCap> {
        val capsByRoom = db.rooms().rules().associate { it.roomId to it.capAmount }
        return db.rooms().active().map { AllocationCap(RoomId(it.id), capsByRoom[it.id]?.let(Money::rupiah)) }
    }

    override suspend fun replaceCaps(caps: List<AllocationCap>) {
        db.withTransaction {
            // Share_bp ruang mode persentase dibawa serta supaya mengubah batas atas tidak menghapusnya.
            val sharesByRoom = db.rooms().rules().associate { it.roomId to it.shareBp }
            db.rooms().clearActiveRules()
            db.rooms().upsertRules(
                caps.map { AllocationRuleEntity(roomId = it.roomId.value, shareBp = sharesByRoom[it.roomId.value] ?: 0, capAmount = it.capAmount?.minor) },
            )
            recorder.changed(SyncRecordType.ALLOCATION_CONFIG, SyncRecordIds.ALLOCATION_CONFIG)
        }
    }

    override suspend fun addRooms(rooms: List<Room>, categories: List<Category>, rules: List<AllocationRule>) {
        db.withTransaction {
            db.rooms().upsertAll(rooms.map { it.toEntity() })
            db.rooms().upsertCategories(categories.map(Category::toEntity))
            db.rooms().upsertRules(rules.map(AllocationRule::toEntity))
            recorder.changed(SyncRecordType.ROOM, rooms.map { it.id.value })
            recorder.changed(SyncRecordType.CATEGORY, categories.map { it.id.value })
            if (rules.isNotEmpty()) recorder.changed(SyncRecordType.ALLOCATION_CONFIG, SyncRecordIds.ALLOCATION_CONFIG)
        }
    }

    override suspend fun saveRooms(rooms: List<Room>, rules: List<AllocationRule>?) {
        db.withTransaction {
            db.rooms().upsertAll(rooms.map { it.toEntity() })
            recorder.changed(SyncRecordType.ROOM, rooms.map { it.id.value })
            if (rules != null) {
                // Ruang yang baru dipulihkan sudah aktif di sini, jadi aturan lamanya ikut terhapus dan diganti.
                // Cap_amount ruang lain dibawa serta supaya tidak ikut terhapus (lihat replaceRules).
                val capsByRoom = db.rooms().rules().associate { it.roomId to it.capAmount }
                db.rooms().clearActiveRules()
                db.rooms().upsertRules(rules.map { AllocationRuleEntity(roomId = it.roomId.value, shareBp = it.share.value, capAmount = capsByRoom[it.roomId.value]) })
                recorder.changed(SyncRecordType.ALLOCATION_CONFIG, SyncRecordIds.ALLOCATION_CONFIG)
            }
        }
    }
}

class LocalTransactionRepository(
    private val db: RizqflowDatabase,
    private val recorder: SyncRecorder,
    private val autoSync: SharedRoomAutoSync,
) : TransactionRepository {
    override suspend fun find(id: TransactionId): MoneyTransaction? = db.transactions().find(id.value)?.toDomain()

    override suspend fun entriesOf(incomeId: TransactionId): List<AllocationEntry> {
        val currency = db.transactions().find(incomeId.value)?.toDomain()?.amount?.currency ?: return emptyList()
        return db.transactions().entriesOf(incomeId.value).map { it.toDomain(currency) }
    }

    // Baris origin SHARED (pengeluaran anggota lain di ruang bersama, Tahap 2) bukan milik cakupan pribadi.
    private suspend fun recordChanged(transaction: MoneyTransaction) {
        if (transaction.origin != TransactionOrigin.SHARED) recorder.changed(SyncRecordType.TRANSACTION, transaction.id.value)
    }

    override suspend fun saveIncome(transaction: MoneyTransaction, entries: List<AllocationEntry>) {
        db.withTransaction {
            db.transactions().insert(transaction.toEntity())
            db.transactions().insertEntries(entries.map(AllocationEntry::toEntity))
            recordChanged(transaction)
        }
    }

    override suspend fun save(transaction: MoneyTransaction) {
        db.withTransaction {
            db.transactions().insert(transaction.toEntity())
            recordChanged(transaction)
        }
        // Di luar transaksi (sudah komit): sinkron tidak boleh membaca baris yang belum tersimpan. Sinkron hanya
        // untuk pengeluaran di ruang bersama; pemicunya sendiri yang memutuskan ruang itu bersama atau tidak.
        if (transaction.kind == TransactionKind.EXPENSE) transaction.roomId?.let { autoSync.onExpenseSaved(it) }
    }

    override suspend fun update(transaction: MoneyTransaction) {
        db.withTransaction {
            if (db.transactions().update(transaction.toEntity()) > 0) recordChanged(transaction)
        }
    }

    override suspend fun replaceIncome(transaction: MoneyTransaction, entries: List<AllocationEntry>) {
        db.withTransaction {
            val updated = db.transactions().update(transaction.toEntity())
            db.transactions().deleteEntries(transaction.id.value)
            db.transactions().insertEntries(entries.map(AllocationEntry::toEntity))
            if (updated > 0) recordChanged(transaction)
        }
    }

    override suspend fun delete(id: TransactionId) {
        db.withTransaction {
            val existing = db.transactions().find(id.value)
            // Kunci asing SET NULL mengubah utang/pelunasan yang merujuk transaksi ini tanpa lewat repositori
            // mereka; catat sebelum dihapus supaya perubahan itu ikut terkirim.
            val debtIds = db.debts().idsByInitialTransaction(id.value)
            val paymentIds = db.debts().paymentIdsByTransaction(id.value)
            val removed = db.transactions().delete(id.value)
            if (removed > 0 && existing?.origin != TransactionOrigin.SHARED) {
                recorder.deleted(SyncRecordType.TRANSACTION, id.value)
                recorder.changed(SyncRecordType.DEBT, debtIds)
                recorder.changed(SyncRecordType.DEBT_PAYMENT, paymentIds)
                // Tahap 9, roadmap.md "Sinkron penghapusan transaksi di ruang yang dibagi": baris asli
                // hilang fisik di sini, jadi catat tombstone-nya SEKARANG (bersama transaksi database
                // yang sama) supaya SyncEngine lama bisa mengirimkannya nanti — lihat TransactionRepository.pendingTombstones.
                val roomId = existing?.roomId
                if (existing != null && existing.kind == TransactionKind.EXPENSE && roomId != null) {
                    db.transactions().insertPendingTombstone(
                        PendingRoomTombstoneEntity(transactionId = id.value, roomId = roomId, deletedAt = System.currentTimeMillis()),
                    )
                }
            }
        }
    }

    override suspend fun between(from: LocalDate, to: LocalDate): List<MoneyTransaction> =
        db.transactions().between(from.toEpochDay(), to.toEpochDay()).map { it.toDomain() }

    override suspend fun latest(kind: TransactionKind?): MoneyTransaction? =
        (if (kind == null) db.transactions().latest() else db.transactions().latestOfKind(kind))?.toDomain()

    override suspend fun latestExpenseRoom(accountId: AccountId): RoomId? = db.transactions().latestExpenseRoom(accountId.value)?.let(::RoomId)

    // Versi 1 hanya rupiah (docs/model-data.md); multi-mata uang (Pro) menambah kolom mata uang di agregat ini.
    override suspend fun roomTotals(from: LocalDate, to: LocalDate): RoomTotals = RoomTotals(
        allocated = db.transactions().allocatedPerRoom(from.toEpochDay(), to.toEpochDay()).associate { RoomId(it.roomId) to Money.rupiah(it.total) },
        spent = db.transactions().spentPerRoom(from.toEpochDay(), to.toEpochDay()).associate { RoomId(it.roomId) to Money.rupiah(it.total) },
    )

    // ---- Sync ruang keluarga (Tahap 9)
    override suspend fun pendingSync(roomId: RoomId): List<PendingSyncTransaction> =
        db.transactions().pendingSync(roomId.value).map { PendingSyncTransaction(it.toDomain(), changedAtMillis = it.updatedAt) }

    override suspend fun markSynced(ids: List<TransactionId>, atMillis: Long) {
        if (ids.isEmpty()) return
        db.transactions().markSynced(ids.map { it.value }, atMillis)
    }

    override suspend fun pendingTombstones(roomId: RoomId): List<PendingTombstone> =
        db.transactions().pendingTombstones(roomId.value).map { PendingTombstone(TransactionId(it.transactionId), it.deletedAt) }

    override suspend fun clearPendingTombstones(ids: List<TransactionId>) {
        if (ids.isEmpty()) return
        db.transactions().clearPendingTombstones(ids.map { it.value })
    }

    // Hasil pull SyncEngine lama dari anggota lain: data dari luar, bukan edit lokal, jadi sengaja tidak
    // dicatat recorder maupun pending_room_tombstone (sama seperti konteks `applying`). Tahap 2 menggantikan jalur ini.
    override suspend fun applyFromSync(id: TransactionId, transaction: MoneyTransaction?, changedAtMillis: Long, deleted: Boolean) {
        val existing = db.transactions().find(id.value)
        if (deleted) {
            // LWW (server-sync.md "Model konflik sinkronisasi"): tombstone basi, lebih lama dari edit lokal
            // yang belum terkirim, tidak boleh menghapus edit yang lebih baru itu.
            if (existing == null || changedAtMillis >= existing.updatedAt) {
                db.transactions().delete(id.value)
            }
            return
        }
        requireNotNull(transaction) { "transaction wajib diisi bila deleted = false" }
        if (existing == null || changedAtMillis >= existing.updatedAt) {
            db.transactions().upsertFromSync(transaction.toEntity().copy(syncedAt = changedAtMillis))
        }
    }
}
