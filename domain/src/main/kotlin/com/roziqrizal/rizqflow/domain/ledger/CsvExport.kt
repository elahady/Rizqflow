package com.roziqrizal.rizqflow.domain.ledger

import com.roziqrizal.rizqflow.domain.model.TransactionKind
import com.roziqrizal.rizqflow.domain.model.TransactionOrigin

/**
 * Menulis seluruh transaksi ke CSV mentah: nama akun/ruang/kategori tertulis apa adanya (bukan
 * pengenal), supaya berkasnya bisa dibaca sendiri di luar aplikasi. Nominal ditulis dalam rupiah
 * bulat (satuan terkecil = rupiah, tidak pernah pecahan).
 *
 * Ekspor ini PRIBADI: baris `origin = SHARED` (pengeluaran anggota lain di ruang bersama, akunnya akun anggota virtual)
 * dilewati. Bila ikut, berkasnya yang diimpor ke perangkat lain akan menggandakan catatan orang lain sebagai catatan
 * pribadi. Penelepon yang menampilkan jumlah baris sebaiknya menyaring dengan [isExportable] lebih dulu.
 */
object CsvExporter {
    val HEADER = listOf("Tanggal", "Jenis", "Akun", "Akun Tujuan", "Ruang", "Kategori", "Nominal", "Catatan", "Sumber")

    /** Benar bila [transaction] ikut ekspor pribadi (bukan pengeluaran anggota lain di ruang bersama). */
    fun isExportable(transaction: MoneyTransaction): Boolean = transaction.origin != TransactionOrigin.SHARED

    fun export(transactions: List<MoneyTransaction>, accounts: List<Account>, rooms: List<Room>, categories: List<Category>): String {
        val accountName = accounts.filter { it.sharedFrom == null }.associate { it.id to it.name }
        val roomName = rooms.associate { it.id to it.name }
        val categoryName = categories.associate { it.id to it.name }

        val lines = transactions.filter(::isExportable).sortedBy { it.occurredOn }.map { tx ->
            listOf(
                tx.occurredOn.toString(),
                jenisOf(tx.kind),
                accountName[tx.accountId].orEmpty(),
                tx.toAccountId?.let { accountName[it] }.orEmpty(),
                tx.roomId?.let { roomName[it] }.orEmpty(),
                tx.categoryId?.let { categoryName[it] }.orEmpty(),
                tx.amount.minor.toString(),
                tx.note.orEmpty(),
                tx.incomeSource.orEmpty(),
            )
        }
        return (listOf(HEADER) + lines).joinToString("\r\n") { row -> row.joinToString(",", transform = ::escape) }
    }

    private fun jenisOf(kind: TransactionKind) = when (kind) {
        TransactionKind.INCOME -> "Pemasukan"
        TransactionKind.EXPENSE -> "Pengeluaran"
        TransactionKind.TRANSFER -> "Transfer"
        TransactionKind.LOAN_OUT -> "Pinjaman keluar"
        TransactionKind.LOAN_IN -> "Pinjaman masuk"
    }

    internal fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value
}
