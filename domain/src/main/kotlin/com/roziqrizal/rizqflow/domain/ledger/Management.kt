package com.roziqrizal.rizqflow.domain.ledger

import com.roziqrizal.rizqflow.domain.model.AccountId
import com.roziqrizal.rizqflow.domain.model.AccountKind
import com.roziqrizal.rizqflow.domain.model.CategoryId
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.money.Money

/**
 * Nama kategori usulan untuk pos lokal Indonesia yang belum dilayani aplikasi global (S13, Tahap 10,
 * Gratis; docs/research-market/research.md): arisan, THR dan Lebaran, kiriman ke orang tua, cicilan
 * atau paylater. Hanya usulan nama saat menambah kategori; pengguna tetap bisa mengetik nama lain,
 * dan usulan yang sudah dipakai di ruang itu tidak ditawarkan lagi.
 */
val LOCAL_CATEGORY_TEMPLATES = listOf("Arisan", "THR & Lebaran", "Kiriman orang tua", "Cicilan & paylater")

data class NewAccount(val name: String, val kind: AccountKind, val openingBalance: Money)

data class AccountRow(val account: Account, val balance: Money)

/** Daftar akun S13. Jumlah akun tidak dibatasi. */
data class AccountOverview(val active: List<AccountRow>, val archived: List<AccountRow>)

/** Kategori satu ruang aktif untuk S13; [categories] yang aktif, [archived] yang disembunyikan. */
data class RoomCategories(val room: Room, val categories: List<Category>, val archived: List<Category>)

/**
 * Kelola akun dan kategori (S13). Akun dan kategori tidak pernah dihapus, hanya diarsipkan,
 * karena riwayat transaksi tetap memakai namanya. Akun dan kategori tidak dibatasi. Kategori sistem `Zakat mal` dan `Tak terlacak` tidak bisa diubah atau
 * diarsipkan, dan namanya tidak boleh dipakai kategori lain.
 */
class ManagementService(
    private val accounts: AccountRepository,
    private val rooms: RoomRepository,
    private val newId: () -> String,
) {
    // ------------------------------------------------------------------ akun

    suspend fun accountOverview(): AccountOverview {
        val all = accounts.allAccounts()
        suspend fun row(account: Account) = AccountRow(account, accounts.balance(account.id))
        return AccountOverview(
            active = all.filter { !it.archived }.map { row(it) },
            archived = all.filter { it.archived }.map { row(it) },
        )
    }

    suspend fun addAccount(command: NewAccount): LedgerResult<AccountId> {
        val name = command.name.trim()
        if (name.isEmpty() || name.length > Account.NAME_MAX) return failure(LedgerError.INVALID_NAME)
        if (command.openingBalance.isNegative) return failure(LedgerError.AMOUNT_NOT_POSITIVE)
        val all = accounts.allAccounts()
        if (all.any { it.name.equals(name, ignoreCase = true) }) return failure(LedgerError.NAME_TAKEN)

        val account = Account(
            id = AccountId(newId()),
            name = name,
            kind = command.kind,
            openingBalance = command.openingBalance,
            sortOrder = (all.maxOfOrNull { it.sortOrder } ?: -1) + 1,
        )
        accounts.save(account)
        return LedgerResult.Success(account.id)
    }

    suspend fun updateAccount(id: AccountId, newName: String, kind: AccountKind): LedgerResult<Unit> {
        val account = accounts.find(id) ?: return failure(LedgerError.ACCOUNT_NOT_FOUND)
        val name = newName.trim()
        if (name.isEmpty() || name.length > Account.NAME_MAX) return failure(LedgerError.INVALID_NAME)
        if (accounts.allAccounts().any { it.id != id && it.name.equals(name, ignoreCase = true) }) return failure(LedgerError.NAME_TAKEN)
        accounts.save(account.copy(name = name, kind = kind))
        return LedgerResult.Success(Unit)
    }

    /** Akun terakhir yang masih aktif tidak bisa diarsipkan: Catat butuh setidaknya satu akun. */
    suspend fun archiveAccount(id: AccountId): LedgerResult<Unit> {
        val account = accounts.find(id) ?: return failure(LedgerError.ACCOUNT_NOT_FOUND)
        if (account.archived) return LedgerResult.Success(Unit)
        if (accounts.activeAccounts().size <= 1) return failure(LedgerError.LAST_ACCOUNT)
        accounts.save(account.copy(archived = true))
        return LedgerResult.Success(Unit)
    }

    suspend fun restoreAccount(id: AccountId): LedgerResult<Unit> {
        val account = accounts.find(id) ?: return failure(LedgerError.ACCOUNT_NOT_FOUND)
        if (!account.archived) return LedgerResult.Success(Unit)
        accounts.save(account.copy(archived = false))
        return LedgerResult.Success(Unit)
    }

    // ------------------------------------------------------------------ kategori

    /** Kategori per ruang aktif, berurutan menurut ruang; kategori sistem tampil paling akhir di tiap ruang. */
    suspend fun categoryOverview(): List<RoomCategories> {
        val byRoom = rooms.allCategories().groupBy { it.roomId }
        return rooms.activeRooms().map { room ->
            val own = byRoom[room.id].orEmpty().sortedWith(compareBy<Category> { it.isSystem }.thenBy { it.sortOrder })
            RoomCategories(room, own.filter { !it.archived }, own.filter { it.archived })
        }
    }

    suspend fun addCategory(roomId: RoomId, name: String): LedgerResult<CategoryId> {
        val room = rooms.find(roomId) ?: return failure(LedgerError.ROOM_NOT_FOUND)
        if (room.archived) return failure(LedgerError.ROOM_ARCHIVED)
        val clean = name.trim()
        if (clean.isEmpty() || clean.length > Category.NAME_MAX) return failure(LedgerError.INVALID_NAME)
        val own = rooms.allCategories().filter { it.roomId == roomId }
        if (isReserved(clean) || own.any { it.name.equals(clean, ignoreCase = true) }) return failure(LedgerError.NAME_TAKEN)

        val category = Category(CategoryId(newId()), roomId, clean, sortOrder = (own.maxOfOrNull { it.sortOrder } ?: -1) + 1)
        rooms.saveCategory(category)
        return LedgerResult.Success(category.id)
    }

    suspend fun renameCategory(id: CategoryId, name: String): LedgerResult<Unit> {
        val category = rooms.findCategory(id) ?: return failure(LedgerError.CATEGORY_NOT_FOUND)
        if (category.isSystem) return failure(LedgerError.CATEGORY_IS_SYSTEM)
        val clean = name.trim()
        if (clean.isEmpty() || clean.length > Category.NAME_MAX) return failure(LedgerError.INVALID_NAME)
        val own = rooms.allCategories().filter { it.roomId == category.roomId && it.id != id }
        if (isReserved(clean) || own.any { it.name.equals(clean, ignoreCase = true) }) return failure(LedgerError.NAME_TAKEN)
        rooms.saveCategory(category.copy(name = clean))
        return LedgerResult.Success(Unit)
    }

    /** Sebuah ruang harus tetap punya setidaknya satu kategori biasa yang aktif supaya pengeluaran bisa dicatat. */
    suspend fun archiveCategory(id: CategoryId): LedgerResult<Unit> {
        val category = rooms.findCategory(id) ?: return failure(LedgerError.CATEGORY_NOT_FOUND)
        if (category.isSystem) return failure(LedgerError.CATEGORY_IS_SYSTEM)
        if (category.archived) return LedgerResult.Success(Unit)
        val others = rooms.allCategories().count { it.roomId == category.roomId && it.id != id && !it.isSystem && !it.archived }
        if (others == 0) return failure(LedgerError.LAST_CATEGORY)
        rooms.saveCategory(category.copy(archived = true))
        return LedgerResult.Success(Unit)
    }

    suspend fun restoreCategory(id: CategoryId): LedgerResult<Unit> {
        val category = rooms.findCategory(id) ?: return failure(LedgerError.CATEGORY_NOT_FOUND)
        if (!category.archived) return LedgerResult.Success(Unit)
        rooms.saveCategory(category.copy(archived = false))
        return LedgerResult.Success(Unit)
    }

    private fun isReserved(name: String): Boolean =
        name.equals(RoomTemplates.ZAKAT, ignoreCase = true) || name.equals(RoomTemplates.UNTRACKED, ignoreCase = true)
}
