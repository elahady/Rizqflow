package com.roziqrizal.rizqflow.domain.ledger

import com.roziqrizal.rizqflow.domain.model.AccountId
import com.roziqrizal.rizqflow.domain.model.CategoryId
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.model.TransactionKind
import java.time.LocalDate
import java.time.YearMonth

/**
 * Yang dipilih pengguna di S08: bulan (kalender Masehi), jenis, kata cari, dan filter akun, ruang,
 * atau kategori. [kinds] kosong berarti semua jenis. Semua filter bertindak bersama (dan).
 */
data class ListFilter(
    val month: YearMonth,
    val kinds: Set<TransactionKind> = emptySet(),
    val query: String = "",
    val accountId: AccountId? = null,
    val roomId: RoomId? = null,
    val categoryId: CategoryId? = null,
) {
    /** Ada filter selain bulan: dasar keadaan kosong "Tidak ada yang cocok" dan penanda filter aktif. */
    val isNarrowed: Boolean
        get() = kinds.isNotEmpty() || query.isNotBlank() || accountId != null || roomId != null || categoryId != null

    /** Jumlah filter dari sheet (akun, ruang, kategori) yang aktif, untuk lencana di tombol filter. */
    val sheetFilterCount: Int get() = listOfNotNull(accountId, roomId, categoryId).size
}

/**
 * Satu baris daftar beserta nama-nama yang dibutuhkan layar. Teks yang dirangkai (judul, keterangan)
 * sengaja tidak dibuat di sini supaya bisa diterjemahkan; layar menyusunnya dari kolom ini.
 * [allocatedRoomCount]: untuk pemasukan, jumlah ruang yang menerima bagian lebih dari nol.
 */
data class TransactionRow(
    val transaction: MoneyTransaction,
    val categoryName: String?,
    val room: Room?,
    val accountName: String,
    val toAccountName: String?,
    val allocatedRoomCount: Int?,
)

data class DayGroup(val date: LocalDate, val rows: List<TransactionRow>)

data class TransactionListing(val groups: List<DayGroup>) {
    val count: Int get() = groups.sumOf { it.rows.size }
    val isEmpty: Boolean get() = groups.isEmpty()
}

/**
 * Daftar transaksi S08: satu bulan, dikelompokkan per tanggal (terbaru dulu; dalam sehari, yang
 * dicatat terakhir dulu), dengan filter dan pencarian di memori. Data sebulan kecil, jadi tidak perlu
 * kueri khusus; nama akun, ruang, dan kategori (termasuk yang terarsip) diambil sekali.
 */
class TransactionLister(
    private val accounts: AccountRepository,
    private val rooms: RoomRepository,
    private val transactions: TransactionRepository,
) {
    suspend fun list(filter: ListFilter): TransactionListing {
        val month = filter.month
        val inMonth = transactions.between(month.atDay(1), month.atEndOfMonth())
        if (inMonth.isEmpty()) return TransactionListing(emptyList())

        // Termasuk akun anggota virtual: baris SHARED di daftar tetap menampilkan nama akun sumbernya ("Dompet (Ayu)").
        val accountNames = accounts.allAccountsIncludingMembers().associate { it.id to it.name }
        val roomsById = rooms.allRooms().associateBy { it.id }
        val categoriesById = rooms.allCategories().associateBy { it.id }

        val rows = inMonth
            .filter { filter.kinds.isEmpty() || it.kind in filter.kinds }
            .filter { filter.accountId == null || it.accountId == filter.accountId || it.toAccountId == filter.accountId }
            .filter { filter.roomId == null || it.roomId == filter.roomId }
            .filter { filter.categoryId == null || it.categoryId == filter.categoryId }
            .map { tx ->
                TransactionRow(
                    transaction = tx,
                    categoryName = tx.categoryId?.let { categoriesById[it]?.name },
                    room = tx.roomId?.let { roomsById[it] },
                    accountName = accountNames[tx.accountId].orEmpty(),
                    toAccountName = tx.toAccountId?.let { accountNames[it] },
                    allocatedRoomCount = if (tx.kind == TransactionKind.INCOME) {
                        transactions.entriesOf(tx.id).count { it.amount.isPositive }
                    } else {
                        null
                    },
                )
            }
            .filter { matches(it, filter.query) }

        // between() sudah terbaru dulu (tanggal, lalu waktu dicatat); groupBy menjaga urutan itu.
        return TransactionListing(rows.groupBy { it.transaction.occurredOn }.map { (date, group) -> DayGroup(date, group) })
    }

    /**
     * Kata cari cocok bila ada di catatan, sumber, kategori, ruang, atau nama akun (tanpa membedakan
     * huruf besar-kecil). Kata cari yang hanya berisi angka juga dicocokkan ke nominal ("150" cocok
     * dengan Rp 150.000 dan Rp 1.500); titik pemisah ribuan dan awalan "Rp" diabaikan.
     */
    private fun matches(row: TransactionRow, rawQuery: String): Boolean {
        val query = rawQuery.trim()
        if (query.isEmpty()) return true
        val text = query.lowercase()
        val tx = row.transaction
        val haystack = listOfNotNull(tx.note, tx.incomeSource, row.categoryName, row.room?.name, row.accountName, row.toAccountName)
        if (haystack.any { it.lowercase().contains(text) }) return true

        val digits = text.removePrefix("rp").filter { it.isDigit() }
        val onlyNumber = text.removePrefix("rp").all { it.isDigit() || it == '.' || it == ' ' || it == ' ' }
        return onlyNumber && digits.isNotEmpty() && tx.amount.minor.toString().contains(digits)
    }
}
