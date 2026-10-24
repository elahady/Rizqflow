package com.roziqrizal.rizqflow.data.repo

import androidx.room.withTransaction
import com.roziqrizal.rizqflow.data.db.AppSettingEntity
import com.roziqrizal.rizqflow.data.db.AllocationRuleEntity
import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
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
import com.roziqrizal.rizqflow.domain.money.Money
import java.time.LocalDate

/*
 * Implementasi repositori di atas Room. Setiap fungsi yang menulis lebih dari satu baris
 * dibungkus satu transaksi database: gagal di tengah membatalkan semuanya.
 */

class LocalWorkspaceRepository(private val db: RizqflowDatabase) : WorkspaceRepository {
    override suspend fun isEmpty(): Boolean = db.rooms().count() == 0 && db.accounts().count() == 0

    override suspend fun initialize(snapshot: WorkspaceSnapshot) {
        db.withTransaction {
            // Urutan mengikuti kunci asing: ruang lebih dulu, baru kategori dan aturan.
            snapshot.rooms.forEach { db.rooms().upsert(it.toEntity()) }
            db.rooms().upsertCategories(snapshot.categories.map(Category::toEntity))
            db.rooms().upsertRules(snapshot.rules.map(AllocationRule::toEntity))
            db.accounts().upsertAll(snapshot.accounts.map(Account::toEntity))
        }
    }
}

class LocalAccountRepository(private val db: RizqflowDatabase) : AccountRepository {
    override suspend fun find(id: AccountId): Account? = db.accounts().find(id.value)?.toDomain()

    override suspend fun activeAccounts(): List<Account> = db.accounts().active().map { it.toDomain() }

    override suspend fun allAccounts(): List<Account> = db.accounts().all().map { it.toDomain() }

    override suspend fun save(account: Account) = db.accounts().upsert(account.toEntity())

    override suspend fun balance(id: AccountId): Money {
        val account = requireNotNull(find(id)) { "Akun tidak ada: ${id.value}" }
        return Money(db.accounts().balance(id.value), account.currency)
    }
}

class LocalFavoriteRepository(private val db: RizqflowDatabase) : FavoriteRepository {
    override suspend fun all(): List<QuickFavorite> = db.favorites().all().map { it.toDomain() }

    override suspend fun find(id: String): QuickFavorite? = db.favorites().find(id)?.toDomain()

    override suspend fun save(favorite: QuickFavorite) = db.favorites().upsert(favorite.toEntity())

    override suspend fun delete(id: String) = db.favorites().delete(id)
}

class LocalRoomRepository(private val db: RizqflowDatabase) : RoomRepository {
    override suspend fun activeRooms(): List<Room> = db.rooms().active().map { it.toDomain() }

    override suspend fun allRooms(): List<Room> = db.rooms().all().map { it.toDomain() }

    override suspend fun allCategories(): List<Category> = db.rooms().allCategories().map { it.toDomain() }

    override suspend fun find(id: RoomId): Room? = db.rooms().find(id.value)?.toDomain()

    override suspend fun rules(): List<AllocationRule> = db.rooms().rules().map { it.toDomain() }

    override suspend fun categories(roomId: RoomId): List<Category> = db.rooms().categories(roomId.value).map { it.toDomain() }

    override suspend fun findCategory(id: CategoryId): Category? = db.rooms().findCategory(id.value)?.toDomain()

    override suspend fun saveCategory(category: Category) = db.rooms().upsertCategory(category.toEntity())

    override suspend fun addRoom(room: Room, categories: List<Category>) {
        db.withTransaction {
            db.rooms().upsert(room.toEntity())
            db.rooms().upsertCategories(categories.map(Category::toEntity))
        }
    }

    override suspend fun replaceRules(rules: List<AllocationRule>) {
        db.withTransaction {
            // Cap_amount ruang lanjutan (Pro) dibawa serta supaya mengubah persentase tidak menghapusnya.
            val capsByRoom = db.rooms().rules().associate { it.roomId to it.capAmount }
            db.rooms().clearActiveRules()
            db.rooms().upsertRules(rules.map { AllocationRuleEntity(roomId = it.roomId.value, shareBp = it.share.value, capAmount = capsByRoom[it.roomId.value]) })
        }
    }

    override suspend fun allocationMode(): AllocationMode {
        val raw = db.settings().get(KEY_ALLOCATION_MODE)
        return AllocationMode.entries.firstOrNull { it.name == raw } ?: AllocationMode.PERCENTAGE
    }

    override suspend fun setAllocationMode(mode: AllocationMode) {
        db.settings().put(AppSettingEntity(KEY_ALLOCATION_MODE, mode.name))
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
        }
    }

    override suspend fun addRooms(rooms: List<Room>, categories: List<Category>, rules: List<AllocationRule>) {
        db.withTransaction {
            db.rooms().upsertAll(rooms.map { it.toEntity() })
            db.rooms().upsertCategories(categories.map(Category::toEntity))
            db.rooms().upsertRules(rules.map(AllocationRule::toEntity))
        }
    }

    override suspend fun saveRooms(rooms: List<Room>, rules: List<AllocationRule>?) {
        db.withTransaction {
            db.rooms().upsertAll(rooms.map { it.toEntity() })
            if (rules != null) {
                // Ruang yang baru dipulihkan sudah aktif di sini, jadi aturan lamanya ikut terhapus dan diganti.
                // Cap_amount ruang lain dibawa serta supaya tidak ikut terhapus (lihat replaceRules).
                val capsByRoom = db.rooms().rules().associate { it.roomId to it.capAmount }
                db.rooms().clearActiveRules()
                db.rooms().upsertRules(rules.map { AllocationRuleEntity(roomId = it.roomId.value, shareBp = it.share.value, capAmount = capsByRoom[it.roomId.value]) })
            }
        }
    }

    private companion object {
        const val KEY_ALLOCATION_MODE = "allocation_mode"
    }
}

class LocalTransactionRepository(private val db: RizqflowDatabase) : TransactionRepository {
    override suspend fun find(id: TransactionId): MoneyTransaction? = db.transactions().find(id.value)?.toDomain()

    override suspend fun entriesOf(incomeId: TransactionId): List<AllocationEntry> {
        val currency = db.transactions().find(incomeId.value)?.toDomain()?.amount?.currency ?: return emptyList()
        return db.transactions().entriesOf(incomeId.value).map { it.toDomain(currency) }
    }

    override suspend fun saveIncome(transaction: MoneyTransaction, entries: List<AllocationEntry>) {
        db.withTransaction {
            db.transactions().insert(transaction.toEntity())
            db.transactions().insertEntries(entries.map(AllocationEntry::toEntity))
        }
    }

    override suspend fun save(transaction: MoneyTransaction) = db.transactions().insert(transaction.toEntity())

    override suspend fun update(transaction: MoneyTransaction) = db.transactions().update(transaction.toEntity())

    override suspend fun replaceIncome(transaction: MoneyTransaction, entries: List<AllocationEntry>) {
        db.withTransaction {
            db.transactions().update(transaction.toEntity())
            db.transactions().deleteEntries(transaction.id.value)
            db.transactions().insertEntries(entries.map(AllocationEntry::toEntity))
        }
    }

    override suspend fun delete(id: TransactionId) = db.transactions().delete(id.value)

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

    override suspend fun applyFromSync(id: TransactionId, transaction: MoneyTransaction?, changedAtMillis: Long, deleted: Boolean) {
        if (deleted) {
            db.transactions().delete(id.value)
            return
        }
        requireNotNull(transaction) { "transaction wajib diisi bila deleted = false" }
        val existing = db.transactions().find(id.value)
        if (existing == null || changedAtMillis >= existing.updatedAt) {
            db.transactions().upsertFromSync(transaction.toEntity().copy(syncedAt = changedAtMillis))
        }
    }
}
