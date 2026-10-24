package com.roziqrizal.rizqflow.domain.ledger

import com.roziqrizal.rizqflow.domain.model.CategoryId
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.model.TransactionKind
import com.roziqrizal.rizqflow.domain.money.Money
import com.roziqrizal.rizqflow.domain.money.sum
import java.math.BigInteger
import java.time.YearMonth

/*
 * Laporan dasar (Tahap 10, Gratis): ringkasan bulanan di aplikasi dengan perbandingan bulan lalu.
 * Semua angka diturunkan dari transaksi seperti Denah, tidak ada yang disimpan. Laporan lanjutan
 * (bulanan/tahunan, PDF, multi-mata uang) tetap Pro (Tahap 7); ini hanya ringkasan di layar.
 */

/**
 * Perbandingan satu angka antara bulan ini ([current]) dan bulan lalu ([previous]).
 * [percentBp] basis poin perubahan relatif bulan lalu; null bila bulan lalu nol rupiah
 * (tidak ada dasar persentase yang bermakna, hanya kenaikan dari nol).
 */
data class MonthComparison(val current: Money, val previous: Money) {
    val delta: Money get() = current - previous

    val percentBp: Int?
        get() {
            if (!previous.isPositive) return null
            val bp = BigInteger.valueOf(delta.minor).multiply(TEN_THOUSAND).divide(BigInteger.valueOf(previous.minor))
            return bp.coerceIn(INT_MIN, INT_MAX).toInt()
        }

    private companion object {
        val TEN_THOUSAND: BigInteger = BigInteger.valueOf(10_000)
        val INT_MIN: BigInteger = BigInteger.valueOf(Int.MIN_VALUE.toLong())
        val INT_MAX: BigInteger = BigInteger.valueOf(Int.MAX_VALUE.toLong())
    }
}

/** Pengeluaran satu ruang bulan itu. */
data class RoomSpend(val room: Room, val amount: Money)

/** Pengeluaran satu kategori bulan itu, dengan ruangnya (beda dari `CategorySpend` di RoomDetail, yang untuk satu ruang bulan berjalan). */
data class ReportCategorySpend(val category: Category, val room: Room, val amount: Money)

/**
 * Laporan satu bulan (S36). [roomSpend] dan [topCategories] hanya pengeluaran bulan ini (bukan
 * bulan lalu), terbesar dulu; ruang atau kategori yang sudah diarsipkan tetap muncul karena ini
 * ringkasan yang sudah terjadi, bukan tampilan jatah aktif seperti Denah.
 */
data class MonthlyReport(
    val month: YearMonth,
    val income: MonthComparison,
    val expense: MonthComparison,
    val roomSpend: List<RoomSpend>,
    val topCategories: List<ReportCategorySpend>,
) {
    /** Pemasukan dikurangi pengeluaran, dengan perbandingan bulan lalu yang sama. */
    val net: MonthComparison get() = MonthComparison(income.current - expense.current, income.previous - expense.previous)

    val hasData: Boolean get() = income.current.isPositive || expense.current.isPositive

    companion object {
        /** Kategori terbesar yang ditampilkan; sisanya tidak dirinci (laporan dasar, bukan laporan lanjutan Pro). */
        const val MAX_CATEGORIES = 5
    }
}

class ReportLoader(
    private val rooms: RoomRepository,
    private val transactions: TransactionRepository,
) {
    suspend fun load(month: YearMonth): MonthlyReport {
        val currentTx = transactions.between(month.atDay(1), month.atEndOfMonth())
        val previousMonth = month.minusMonths(1)
        val previousTx = transactions.between(previousMonth.atDay(1), previousMonth.atEndOfMonth())

        val allRoomsById = rooms.allRooms().associateBy { it.id }
        val allCategoriesById = rooms.allCategories().associateBy { it.id }

        return MonthlyReport(
            month = month,
            income = MonthComparison(incomeOf(currentTx), incomeOf(previousTx)),
            expense = MonthComparison(expenseOf(currentTx), expenseOf(previousTx)),
            roomSpend = roomSpendOf(currentTx, allRoomsById),
            topCategories = topCategoriesOf(currentTx, allRoomsById, allCategoriesById, MonthlyReport.MAX_CATEGORIES),
        )
    }
}

// Agregasi bersama laporan bulanan dan tahunan (YearReport.kt): satu aturan, bukan dua salinan.

internal fun incomeOf(txs: List<MoneyTransaction>) = txs.filter { it.kind == TransactionKind.INCOME }.map { it.amount }.sum()

internal fun expenseOf(txs: List<MoneyTransaction>) = txs.filter { it.kind == TransactionKind.EXPENSE }.map { it.amount }.sum()

/** Pengeluaran per ruang dari [txs] (jenis lain diabaikan), terbesar dulu; ruang terarsip tetap terhitung. */
internal fun roomSpendOf(txs: List<MoneyTransaction>, roomsById: Map<RoomId, Room>): List<RoomSpend> =
    txs.filter { it.kind == TransactionKind.EXPENSE }
        .groupBy { it.roomId }
        .mapNotNull { (roomId, group) -> roomId?.let(roomsById::get)?.let { room -> RoomSpend(room, group.map { it.amount }.sum()) } }
        .sortedByDescending { it.amount }

/** [limit] kategori pengeluaran terbesar dari [txs], dengan ruangnya. */
internal fun topCategoriesOf(
    txs: List<MoneyTransaction>,
    roomsById: Map<RoomId, Room>,
    categoriesById: Map<CategoryId, Category>,
    limit: Int,
): List<ReportCategorySpend> =
    txs.filter { it.kind == TransactionKind.EXPENSE }
        .groupBy { it.categoryId }
        .mapNotNull { (categoryId, group) ->
            val category = categoryId?.let(categoriesById::get) ?: return@mapNotNull null
            val room = roomsById[category.roomId] ?: return@mapNotNull null
            ReportCategorySpend(category, room, group.map { it.amount }.sum())
        }
        .sortedByDescending { it.amount }
        .take(limit)
