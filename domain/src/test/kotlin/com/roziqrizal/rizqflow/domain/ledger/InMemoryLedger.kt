package com.roziqrizal.rizqflow.domain.ledger

import com.roziqrizal.rizqflow.domain.allocation.AllocationCap
import com.roziqrizal.rizqflow.domain.allocation.AllocationMode
import com.roziqrizal.rizqflow.domain.allocation.AllocationRule
import com.roziqrizal.rizqflow.domain.model.AccountId
import com.roziqrizal.rizqflow.domain.model.CategoryId
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.model.TransactionId
import com.roziqrizal.rizqflow.domain.model.TransactionKind
import com.roziqrizal.rizqflow.domain.money.Money
import java.time.LocalDate

/**
 * Penyimpanan tiruan di memori untuk menguji layanan tanpa database. Perilakunya meniru
 * yang dijanjikan antarmuka: urutan menurut prioritas ruang, saldo dihitung dari transaksi,
 * dan hapus transaksi ikut menghapus potret alokasinya. Perilaku Room yang sebenarnya diuji
 * terpisah di :data.
 */
class InMemoryLedger : WorkspaceRepository, AccountRepository, RoomRepository, TransactionRepository, FavoriteRepository {
    val accountRows = linkedMapOf<AccountId, Account>()
    val roomRows = linkedMapOf<RoomId, Room>()
    val categoryRows = linkedMapOf<CategoryId, Category>()
    var ruleRows: List<AllocationRule> = emptyList()
    var modeRow: AllocationMode = AllocationMode.PERCENTAGE
    var capRows: List<AllocationCap> = emptyList()
    val transactionRows = linkedMapOf<TransactionId, MoneyTransaction>()
    val entryRows = linkedMapOf<String, AllocationEntry>()
    val favoriteRows = linkedMapOf<String, QuickFavorite>()
    val syncedAtRows = mutableMapOf<TransactionId, Long>()

    /** Bila diisi, penyimpanan berikutnya melempar galat ini: untuk menguji atomisitas di pemanggil. */
    var failNextWrite: Throwable? = null

    private fun maybeFail() {
        failNextWrite?.let {
            failNextWrite = null
            throw it
        }
    }

    // ---- WorkspaceRepository
    override suspend fun isEmpty() = roomRows.isEmpty() && accountRows.isEmpty()

    override suspend fun initialize(snapshot: WorkspaceSnapshot) {
        maybeFail()
        snapshot.rooms.forEach { roomRows[it.id] = it }
        snapshot.categories.forEach { categoryRows[it.id] = it }
        snapshot.accounts.forEach { accountRows[it.id] = it }
        ruleRows = snapshot.rules
    }

    // ---- AccountRepository
    override suspend fun find(id: AccountId) = accountRows[id]

    override suspend fun activeAccounts() = accountRows.values.filter { !it.archived }.sortedBy { it.sortOrder }

    override suspend fun allAccounts() = accountRows.values.sortedBy { it.sortOrder }

    override suspend fun save(account: Account) {
        accountRows[account.id] = account
    }

    override suspend fun balance(id: AccountId): Money {
        val account = accountRows.getValue(id)
        var total = account.openingBalance
        for (t in transactionRows.values) {
            when (t.kind) {
                TransactionKind.INCOME -> if (t.accountId == id) total += t.amount
                TransactionKind.EXPENSE -> if (t.accountId == id) total -= t.amount
                TransactionKind.TRANSFER -> {
                    if (t.accountId == id) total -= t.amount
                    if (t.toAccountId == id) total += t.amount
                }
                TransactionKind.LOAN_OUT -> if (t.accountId == id) total -= t.amount
                TransactionKind.LOAN_IN -> if (t.accountId == id) total += t.amount
            }
        }
        return total
    }

    // ---- FavoriteRepository
    override suspend fun all(): List<QuickFavorite> =
        favoriteRows.values.sortedWith(compareByDescending<QuickFavorite> { it.useCount }.thenByDescending { it.lastUsedAtMillis ?: 0L }.thenBy { it.name.lowercase() })

    override suspend fun find(id: String): QuickFavorite? = favoriteRows[id]

    override suspend fun save(favorite: QuickFavorite) {
        maybeFail()
        favoriteRows[favorite.id] = favorite
    }

    override suspend fun delete(id: String) {
        favoriteRows.remove(id)
    }

    // ---- RoomRepository
    override suspend fun saveCategory(category: Category) {
        maybeFail()
        categoryRows[category.id] = category
    }

    override suspend fun activeRooms() = roomRows.values.filter { !it.archived }.sortedBy { it.sortOrder }

    override suspend fun allRooms() = roomRows.values.sortedBy { it.sortOrder }

    override suspend fun allCategories() = categoryRows.values.toList()

    override suspend fun find(id: RoomId) = roomRows[id]

    override suspend fun rules(): List<AllocationRule> {
        val order = activeRooms().map { it.id }
        return ruleRows.filter { it.roomId in order }.sortedBy { order.indexOf(it.roomId) }
    }

    override suspend fun categories(roomId: RoomId) =
        categoryRows.values.filter { it.roomId == roomId && !it.archived }.sortedBy { it.sortOrder }

    override suspend fun findCategory(id: CategoryId) = categoryRows[id]

    override suspend fun addRoom(room: Room, categories: List<Category>) {
        maybeFail()
        roomRows[room.id] = room
        categories.forEach { categoryRows[it.id] = it }
    }

    override suspend fun replaceRules(rules: List<AllocationRule>) {
        maybeFail()
        // Meniru Room: hanya aturan ruang aktif yang diganti; aturan ruang terarsip dibiarkan.
        val activeIds = roomRows.values.filter { !it.archived }.map { it.id }.toSet()
        ruleRows = ruleRows.filter { it.roomId !in activeIds } + rules
    }

    override suspend fun addRooms(rooms: List<Room>, categories: List<Category>, rules: List<AllocationRule>) {
        maybeFail()
        rooms.forEach { roomRows[it.id] = it }
        categories.forEach { categoryRows[it.id] = it }
        ruleRows = ruleRows + rules
    }

    override suspend fun saveRooms(rooms: List<Room>, rules: List<AllocationRule>?) {
        maybeFail()
        rooms.forEach { roomRows[it.id] = it }
        if (rules != null) replaceRules(rules)
    }

    override suspend fun allocationMode(): AllocationMode = modeRow

    override suspend fun setAllocationMode(mode: AllocationMode) {
        modeRow = mode
    }

    override suspend fun caps(): List<AllocationCap> {
        val order = activeRooms().map { it.id }
        val byRoom = capRows.associate { it.roomId to it.capAmount }
        return order.map { AllocationCap(it, byRoom[it]) }
    }

    override suspend fun replaceCaps(caps: List<AllocationCap>) {
        maybeFail()
        val activeIds = roomRows.values.filter { !it.archived }.map { it.id }.toSet()
        capRows = capRows.filter { it.roomId !in activeIds } + caps
    }

    // ---- TransactionRepository
    override suspend fun find(id: TransactionId) = transactionRows[id]

    override suspend fun entriesOf(incomeId: TransactionId): List<AllocationEntry> {
        val order = roomRows.values.sortedBy { it.sortOrder }.map { it.id }
        return entryRows.values.filter { it.incomeId == incomeId }.sortedBy { order.indexOf(it.roomId) }
    }

    override suspend fun saveIncome(transaction: MoneyTransaction, entries: List<AllocationEntry>) {
        maybeFail()
        transactionRows[transaction.id] = transaction
        entries.forEach { entryRows[it.id] = it }
    }

    override suspend fun save(transaction: MoneyTransaction) {
        maybeFail()
        transactionRows[transaction.id] = transaction
        syncedAtRows.remove(transaction.id) // ubahan lokal baru selalu pending sync lagi
    }

    override suspend fun update(transaction: MoneyTransaction) {
        maybeFail()
        check(transaction.id in transactionRows) { "transaksi belum ada" }
        transactionRows[transaction.id] = transaction
        syncedAtRows.remove(transaction.id)
    }

    override suspend fun replaceIncome(transaction: MoneyTransaction, entries: List<AllocationEntry>) {
        maybeFail()
        entryRows.values.removeAll { it.incomeId == transaction.id }
        transactionRows[transaction.id] = transaction
        entries.forEach { entryRows[it.id] = it }
    }

    override suspend fun delete(id: TransactionId) {
        transactionRows.remove(id)
        entryRows.values.removeAll { it.incomeId == id }
        syncedAtRows.remove(id)
    }

    override suspend fun between(from: LocalDate, to: LocalDate) = transactionRows.values
        .filter { it.occurredOn in from..to }
        .sortedWith(compareByDescending<MoneyTransaction> { it.occurredOn }.thenByDescending { it.createdAtMillis })

    override suspend fun latest(kind: TransactionKind?): MoneyTransaction? =
        transactionRows.values.filter { kind == null || it.kind == kind }.maxByOrNull { it.createdAtMillis }

    override suspend fun latestExpenseRoom(accountId: AccountId): RoomId? =
        transactionRows.values.filter { it.kind == TransactionKind.EXPENSE && it.accountId == accountId }
            .maxByOrNull { it.createdAtMillis }?.roomId

    override suspend fun roomTotals(from: LocalDate, to: LocalDate): RoomTotals {
        val inRange = transactionRows.values.filter { it.occurredOn in from..to }
        val incomeIds = inRange.filter { it.kind == TransactionKind.INCOME }.map { it.id }.toSet()
        val allocated = entryRows.values.filter { it.incomeId in incomeIds }.groupBy { it.roomId }
            .mapValues { (_, list) -> list.fold(Money.zero()) { a, e -> a + e.amount } }
        val spent = inRange.filter { it.kind == TransactionKind.EXPENSE }.groupBy { it.roomId!! }
            .mapValues { (_, list) -> list.fold(Money.zero()) { a, e -> a + e.amount } }
        return RoomTotals(allocated, spent)
    }

    // ---- TransactionRepository: sync (Tahap 9)
    override suspend fun pendingSync(roomId: RoomId): List<PendingSyncTransaction> =
        transactionRows.values
            .filter { it.roomId == roomId && it.id !in syncedAtRows }
            .map { PendingSyncTransaction(it, changedAtMillis = it.updatedAtMillis) }

    override suspend fun markSynced(ids: List<TransactionId>, atMillis: Long) {
        ids.forEach { syncedAtRows[it] = atMillis }
    }

    override suspend fun applyFromSync(id: TransactionId, transaction: MoneyTransaction?, changedAtMillis: Long, deleted: Boolean) {
        if (deleted) {
            delete(id)
            return
        }
        requireNotNull(transaction) { "transaction wajib diisi bila deleted = false" }
        val existing = transactionRows[id]
        if (existing == null || changedAtMillis >= existing.updatedAtMillis) {
            transactionRows[id] = transaction
            syncedAtRows[id] = changedAtMillis // hasil terapan tidak pending push lagi
        }
    }
}
