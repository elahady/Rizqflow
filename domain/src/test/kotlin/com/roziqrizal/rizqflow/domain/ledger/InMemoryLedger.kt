package com.roziqrizal.rizqflow.domain.ledger

import com.roziqrizal.rizqflow.domain.allocation.AllocationCap
import com.roziqrizal.rizqflow.domain.allocation.AllocationMode
import com.roziqrizal.rizqflow.domain.allocation.AllocationRule
import com.roziqrizal.rizqflow.domain.model.AccountId
import com.roziqrizal.rizqflow.domain.model.CategoryId
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.model.TransactionId
import com.roziqrizal.rizqflow.domain.model.TransactionKind
import com.roziqrizal.rizqflow.domain.model.TransactionOrigin
import com.roziqrizal.rizqflow.domain.money.Money
import java.time.LocalDate

/**
 * Penyimpanan tiruan di memori untuk menguji layanan tanpa database. Perilakunya meniru
 * yang dijanjikan antarmuka: urutan menurut prioritas ruang, saldo dihitung dari transaksi,
 * dan hapus transaksi ikut menghapus potret alokasinya. Perilaku Room yang sebenarnya diuji
 * terpisah di :data.
 *
 * [nowMillis] dipakai hanya untuk mencap waktu tombstone di [delete] (Tahap 9) — bawaan jam
 * sistem, tes bisa menyuntik lambda deterministik.
 */
class InMemoryLedger(private val nowMillis: () -> Long = System::currentTimeMillis) :
    WorkspaceRepository, AccountRepository, RoomRepository, TransactionRepository, FavoriteRepository {
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

    /** Tombstone menunggu kirim (Tahap 9): (id transaksi, ruang) -> cap waktu hapus. Satu transaksi bisa menunggu di beberapa ruang. */
    val pendingTombstoneRows = linkedMapOf<Pair<TransactionId, RoomId>, Long>()

    /**
     * Ruang yang berstatus bersama AKTIF (di Room: `shared_room.status = ACTIVE`). Hanya dipakai [update] dan
     * [replaceIncome] untuk memutuskan apakah pindah keluar dari sebuah ruang perlu tombstone; [delete] tetap mencatat
     * tombstone untuk ruang mana pun seperti repositori Room.
     */
    val sharedRoomIds = linkedSetOf<RoomId>()

    /** Meniru `LocalTransactionRepository.settleSharedRooms`: tombstone gugur bila masuk ruang itu lagi; pindah keluar dari ruang bersama aktif menerbitkan satu. */
    private fun settleSharedRooms(existing: MoneyTransaction?, updated: MoneyTransaction) {
        val newRoom = updated.roomId?.takeIf { updated.kind == TransactionKind.EXPENSE }
        if (newRoom != null) pendingTombstoneRows.remove(updated.id to newRoom)
        val old = existing ?: return
        val oldRoom = old.roomId ?: return
        if (old.kind != TransactionKind.EXPENSE || old.origin == TransactionOrigin.SHARED) return
        if (newRoom == oldRoom || oldRoom !in sharedRoomIds) return
        pendingTombstoneRows[updated.id to oldRoom] = nowMillis()
    }

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

    override suspend fun activeAccounts() = accountRows.values.filter { !it.archived && it.sharedFrom == null }.sortedBy { it.sortOrder }

    override suspend fun allAccounts() = accountRows.values.filter { it.sharedFrom == null }.sortedBy { it.sortOrder }

    override suspend fun allAccountsIncludingMembers() = accountRows.values.sortedBy { it.sortOrder }

    override suspend fun save(account: Account) {
        accountRows[account.id] = account
    }

    override suspend fun delete(id: AccountId): LedgerResult<Unit> {
        val account = accountRows[id]
        if (account == null || account.sharedFrom != null) return failure(LedgerError.ACCOUNT_NOT_FOUND)
        if (transactionRows.values.any { it.accountId == id || it.toAccountId == id }) return failure(LedgerError.ACCOUNT_IN_USE)
        if (!account.archived && activeAccounts().size <= 1) return failure(LedgerError.LAST_ACCOUNT)
        accountRows.remove(id)
        favoriteRows.values.removeAll { it.accountId == id }
        return LedgerResult.Success(Unit)
    }

    override suspend fun findOrCreateVirtualMember(roomId: RoomId, author: String, accountName: String): Account {
        val id = com.roziqrizal.rizqflow.domain.sync.VirtualMemberAccount.id(roomId, author, accountName)
        accountRows[id]?.let { existing ->
            if (existing.sharedAuthor == author) return existing
            val healed = existing.copy(sharedAuthor = author)
            accountRows[id] = healed
            return healed
        }
        val account = Account(
            id = id,
            name = com.roziqrizal.rizqflow.domain.sync.VirtualMemberAccount.displayName(accountName, author),
            kind = com.roziqrizal.rizqflow.domain.model.AccountKind.CASH,
            openingBalance = Money.zero(),
            sharedFrom = roomId,
            sharedAuthor = author,
        )
        accountRows[id] = account
        return account
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

    // Hapus meniru aturan ringkas repositori Room (rujukan transaksi -> ditolak); aturan lengkap (FK RESTRICT, tombstone,
    // urutan cascade) diuji pada Room di :data (DeleteApiTest).
    override suspend fun deleteCategory(id: CategoryId): LedgerResult<Unit> {
        val category = categoryRows[id] ?: return failure(LedgerError.CATEGORY_NOT_FOUND)
        if (category.isSystem) return failure(LedgerError.CATEGORY_IS_SYSTEM)
        if (transactionRows.values.any { it.categoryId == id }) return failure(LedgerError.CATEGORY_IN_USE)
        categoryRows.remove(id)
        favoriteRows.values.removeAll { it.categoryId == id }
        return LedgerResult.Success(Unit)
    }

    override suspend fun deleteRoom(id: RoomId): LedgerResult<Unit> {
        val room = roomRows[id] ?: return failure(LedgerError.ROOM_NOT_FOUND)
        if (!room.archived) return failure(LedgerError.ROOM_NOT_ARCHIVED)
        if (transactionRows.values.any { it.roomId == id }) return failure(LedgerError.ROOM_IN_USE)
        categoryRows.values.removeAll { it.roomId == id }
        favoriteRows.values.removeAll { it.roomId == id }
        ruleRows = ruleRows.filter { it.roomId != id }
        roomRows.remove(id)
        return LedgerResult.Success(Unit)
    }

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
        settleSharedRooms(existing = null, updated = transaction)
    }

    override suspend fun update(transaction: MoneyTransaction) {
        maybeFail()
        val existing = transactionRows[transaction.id]
        check(existing != null) { "transaksi belum ada" }
        transactionRows[transaction.id] = transaction
        syncedAtRows.remove(transaction.id)
        settleSharedRooms(existing, transaction)
    }

    override suspend fun replaceIncome(transaction: MoneyTransaction, entries: List<AllocationEntry>) {
        maybeFail()
        val existing = transactionRows[transaction.id]
        entryRows.values.removeAll { it.incomeId == transaction.id }
        transactionRows[transaction.id] = transaction
        entries.forEach { entryRows[it.id] = it }
        settleSharedRooms(existing, transaction)
    }

    override suspend fun delete(id: TransactionId) {
        val existing = transactionRows.remove(id)
        entryRows.values.removeAll { it.incomeId == id }
        syncedAtRows.remove(id)
        // Tahap 9: hapus pengeluaran yang punya ruang mencatat tombstone untuk sync — lihat TransactionRepository.delete.
        // Baris SHARED (pengeluaran anggota lain) tidak ikut: itu bukan milik lokal, sudah dihapus lewat tombstone masuk.
        if (existing != null && existing.kind == TransactionKind.EXPENSE && existing.origin != TransactionOrigin.SHARED) {
            existing.roomId?.let { roomId -> pendingTombstoneRows[id to roomId] = nowMillis() }
        }
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

    // Keputusan pemilik #4: dasar dialog konfirmasi "riwayat ruang ikut dibagikan?" saat ruang mulai dibagi.
    override suspend fun historyStats(roomId: RoomId): RoomHistoryStats? {
        val own = transactionRows.values.filter { it.roomId == roomId && it.kind == TransactionKind.EXPENSE && it.origin != TransactionOrigin.SHARED }
        if (own.isEmpty()) return null
        return RoomHistoryStats(own.size, own.minOf { it.occurredOn }, own.maxOf { it.occurredOn })
    }

    // ---- TransactionRepository: sync (Tahap 9)
    override suspend fun pendingSync(roomId: RoomId): List<PendingSyncTransaction> =
        transactionRows.values
            .filter { it.roomId == roomId && it.id !in syncedAtRows }
            .map { PendingSyncTransaction(it, changedAtMillis = it.updatedAtMillis) }

    override suspend fun markSynced(ids: List<TransactionId>, atMillis: Long) {
        ids.forEach { syncedAtRows[it] = atMillis }
    }

    override suspend fun markSyncedIfUnchanged(sent: List<PendingSyncTransaction>, atMillis: Long) {
        for (version in sent) {
            if (transactionRows[version.transaction.id]?.updatedAtMillis == version.changedAtMillis) syncedAtRows[version.transaction.id] = atMillis
        }
    }

    override suspend fun pendingTombstones(roomId: RoomId): List<PendingTombstone> =
        pendingTombstoneRows.filterKeys { it.second == roomId }.map { (key, deletedAt) -> PendingTombstone(key.first, deletedAt) }

    override suspend fun clearPendingTombstones(roomId: RoomId, ids: List<TransactionId>) {
        ids.forEach { pendingTombstoneRows.remove(it to roomId) }
    }

    override suspend fun applyFromSync(id: TransactionId, transaction: MoneyTransaction?, changedAtMillis: Long, deleted: Boolean, fromRoom: RoomId?) {
        val existing = transactionRows[id]
        if (deleted) {
            // LWW (server-sync.md "Model konflik sinkronisasi"): tombstone basi, lebih lama dari edit lokal
            // yang belum terkirim, tidak boleh menghapus edit yang lebih baru itu. Tombstone ruang bersama hanya
            // berlaku untuk baris yang masih di ruang itu (lihat TransactionRepository.applyFromSync).
            val inScope = fromRoom == null || existing?.roomId == fromRoom
            if (existing == null || (inScope && changedAtMillis >= existing.updatedAtMillis)) {
                transactionRows.remove(id)
                entryRows.values.removeAll { it.incomeId == id }
                syncedAtRows.remove(id)
            }
            return
        }
        requireNotNull(transaction) { "transaction wajib diisi bila deleted = false" }
        if (existing == null || changedAtMillis >= existing.updatedAtMillis) {
            transactionRows[id] = transaction
            syncedAtRows[id] = changedAtMillis // hasil terapan tidak pending push lagi
        }
    }
}
