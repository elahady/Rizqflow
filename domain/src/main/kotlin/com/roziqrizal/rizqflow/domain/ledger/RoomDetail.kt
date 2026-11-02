package com.roziqrizal.rizqflow.domain.ledger

import com.roziqrizal.rizqflow.domain.model.AccountId
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.model.TransactionKind
import com.roziqrizal.rizqflow.domain.money.Money
import com.roziqrizal.rizqflow.domain.money.sum
import java.time.LocalDate
import java.time.YearMonth

/**
 * Satu pos (kategori) di Detail ruang. [budget] adalah pembanding pos itu: irisan jatah ruang menurut
 * bobot bila pos punya bobot, kalau tidak seluruh jatah ruang ("kosong = ikut jatah ruang",
 * docs/model-data.md). Tanpa bobot, batang tiap pos menunjukkan berapa dari jatah ruang yang ia pakai,
 * sehingga jumlah semua batang sama dengan persentase ruang.
 */
data class CategorySpend(
    val category: Category,
    val spent: Money,
    val budget: Money,
    /** Null bila belum ada jatah. Bisa lebih dari 10.000 (100%). */
    val progressBp: Int?,
)

/** Baris "Transaksi terbaru" di Detail ruang. */
data class RoomTransaction(val transaction: MoneyTransaction, val categoryName: String)

/**
 * Satu baris "terpakai per anggota" ruang bersama (peran-anggota.md D.3, H langkah 5 — murni turunan
 * transaksi, BELUM amplop). [author] null untuk baris saya sendiri; untuk anggota lain, sama dengan
 * `member_id` server ([Account.sharedAuthor]). [author] berupa string kosong menandai akun virtual yang
 * dibuat sebelum [Account.sharedAuthor] ada (kolom itu masih `null`) — digabung sebagai satu baris
 * "anggota tidak dikenal" sampai sync berikutnya menyembuhkannya (lihat KDoc [Account.sharedAuthor]).
 */
data class MemberSpend(val author: String?, val spent: Money)

/**
 * Isi Detail ruang (S11) untuk satu ruang pada satu bulan: kartu ringkasan (jatah, terpakai, status),
 * pos-pos, dan pengeluaran terbaru. [recent] paling banyak [RoomDetailLoader.RECENT_LIMIT].
 */
data class RoomDetail(
    val month: YearMonth,
    val card: RoomCard,
    val categories: List<CategorySpend>,
    val recent: List<RoomTransaction>,
    /** Satu baris per anggota yang pernah mencatat di ruang ini bulan ini; satu baris saja ("saya") bila ruang tidak dibagi. */
    val memberSpend: List<MemberSpend>,
)

/**
 * Mengelompokkan [expenses] (sudah EXPENSE+roomId, lihat [RoomDetailLoader]) per anggota yang menulisnya, murni
 * dari [accountsById] tanpa membaca `sync_record` (peran-anggota.md E.2). Satu anggota bisa punya lebih dari satu
 * akun virtual (nama akun berbeda, sync-desain.md B R2); baris digabung per [Account.sharedAuthor], bukan per akun,
 * supaya satu anggota selalu satu baris. Diurutkan: saya dulu, lalu anggota lain menurun terpakai.
 */
fun roomMemberBreakdown(expenses: List<MoneyTransaction>, accountsById: Map<AccountId, Account>): List<MemberSpend> =
    expenses
        .groupBy { tx ->
            val account = accountsById[tx.accountId]
            // sharedFrom null = akun saya sendiri; author "" = akun virtual pra-migrasi 12 (lihat KDoc Account.sharedAuthor).
            if (account?.sharedFrom == null) null else account.sharedAuthor.orEmpty()
        }
        .map { (author, list) -> MemberSpend(author, list.map { it.amount }.sum()) }
        .sortedWith(compareBy<MemberSpend> { it.author != null }.thenByDescending { it.spent.minor })

class RoomDetailLoader(
    private val rooms: RoomRepository,
    private val transactions: TransactionRepository,
    private val accounts: AccountRepository,
) {
    /** Null bila ruangnya tidak ada. Ruang terarsip tetap bisa dilihat (riwayatnya ada). */
    suspend fun load(roomId: RoomId, month: YearMonth, today: LocalDate): RoomDetail? {
        val room = rooms.find(roomId) ?: return null
        val from = month.atDay(1)
        val to = month.atEndOfMonth()
        val totals = transactions.roomTotals(from, to)
        val allocated = totals.allocated[roomId] ?: Money.zero()
        val spent = totals.spent[roomId] ?: Money.zero()
        val card = RoomCard(
            room = room,
            allocated = allocated,
            spent = spent,
            progressBp = RoomStatusRules.progressBp(allocated, spent),
            status = RoomStatusRules.statusOf(room.kind, allocated, spent, monthOver = month < YearMonth.from(today)),
        )

        val expenses = transactions.between(from, to).filter { it.kind == TransactionKind.EXPENSE && it.roomId == roomId }
        val spentBy = expenses.groupBy { it.categoryId }.mapValues { (_, list) -> list.map { it.amount }.sum() }
        val own = rooms.allCategories().filter { it.roomId == roomId }
        val weightTotal = own.filter { !it.archived }.mapNotNull { it.weight }.sum()

        val categories = own
            // Pos aktif selalu tampil; pos terarsip dan pos sistem hanya bila bulan itu memakainya.
            .filter { (!it.archived && !it.isSystem) || (spentBy[it.id]?.isPositive == true) }
            .map { category ->
                val used = spentBy[category.id] ?: Money.zero()
                val budget = if (category.weight != null && weightTotal > 0) {
                    Money.rupiah(allocated.minor * category.weight / weightTotal)
                } else {
                    allocated
                }
                CategorySpend(category, used, budget, RoomStatusRules.progressBp(budget, used))
            }
            .sortedWith(compareByDescending<CategorySpend> { it.spent.minor }.thenBy { it.category.sortOrder })

        val names = own.associate { it.id to it.name }
        val recent = expenses.take(RECENT_LIMIT).map { RoomTransaction(it, names[it.categoryId].orEmpty()) }
        val accountsById = accounts.allAccountsIncludingMembers().associateBy { it.id }
        val memberSpend = roomMemberBreakdown(expenses, accountsById)
        return RoomDetail(month, card, categories, recent, memberSpend)
    }

    companion object {
        const val RECENT_LIMIT = 5
    }
}
