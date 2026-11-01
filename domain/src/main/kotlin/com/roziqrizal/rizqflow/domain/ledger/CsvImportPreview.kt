package com.roziqrizal.rizqflow.domain.ledger

import com.roziqrizal.rizqflow.domain.model.AccountId
import com.roziqrizal.rizqflow.domain.model.CategoryId
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.model.TransactionOrigin

/** Satu baris (atau pasangan baris, untuk transfer) yang siap atau tidak siap diimpor. */
sealed interface ImportCandidate {
    val lines: List<Int>
}

data class ImportIncome(override val lines: List<Int>, val income: NewIncome) : ImportCandidate

data class ImportExpense(override val lines: List<Int>, val expense: NewExpense) : ImportCandidate

data class ImportTransfer(override val lines: List<Int>, val transfer: NewTransfer) : ImportCandidate

/** Tidak diimpor; [reason] dijelaskan ke pengguna di pratinjau supaya bisa diperbaiki dan diimpor ulang. */
data class ImportSkipped(override val lines: List<Int>, val reason: String) : ImportCandidate

data class ImportPreview(val candidates: List<ImportCandidate>) {
    val ready: List<ImportCandidate> get() = candidates.filterNot { it is ImportSkipped }
    val skipped: List<ImportSkipped> get() = candidates.filterIsInstance<ImportSkipped>()
}

/**
 * Menyiapkan pratinjau impor (S20-ekspor/impor, Tahap 6): mencocokkan nama akun dan kategori di
 * CSV ke data aplikasi yang sudah ada (persis sama, tanpa membedakan huruf besar/kecil; tidak
 * pernah menebak-nebak), dan menyatukan pasangan pemasukan/pengeluaran yang tanggal, nominal, dan
 * akunnya cocok jadi satu Transfer. Akun atau kategori yang tidak ditemukan dilewati dengan alasan
 * jelas, bukan dipaksakan ke pilihan yang salah.
 */
class CsvImportPreviewBuilder(accounts: List<Account>, categories: List<Category>) {
    // Akun anggota virtual (sharedFrom terisi) tidak pernah menjadi tujuan impor, walau namanya cocok.
    private val accountByName = accounts.filterNot { it.archived || it.sharedFrom != null }.associateBy { it.name.lowercase() }
    private val categoryByName = categories.filterNot { it.archived }.associateBy { it.name.lowercase() }

    private data class Resolved(val row: ImportRow, val accountId: AccountId, val roomId: RoomId?, val categoryId: CategoryId?)

    fun build(rows: List<ImportRow>): ImportPreview {
        val resolved = mutableListOf<Resolved>()
        val candidates = mutableListOf<ImportCandidate>()

        for (row in rows) {
            if (row.parseError != null) {
                candidates += ImportSkipped(listOf(row.lineNumber), row.parseError)
                continue
            }
            val account = accountByName[row.accountName.lowercase()]
            if (account == null) {
                candidates += ImportSkipped(listOf(row.lineNumber), "Akun tidak ditemukan: ${row.accountName}")
                continue
            }
            if (row.kind == ImportKind.EXPENSE) {
                val category = row.categoryName?.lowercase()?.let(categoryByName::get)
                if (category == null) {
                    candidates += ImportSkipped(listOf(row.lineNumber), "Kategori tidak ditemukan: ${row.categoryName ?: "(kosong)"}")
                    continue
                }
                resolved += Resolved(row, account.id, category.roomId, category.id)
            } else {
                resolved += Resolved(row, account.id, null, null)
            }
        }

        val used = BooleanArray(resolved.size)
        for (i in resolved.indices) {
            if (used[i] || resolved[i].row.kind != ImportKind.INCOME) continue
            val income = resolved[i]
            for (j in resolved.indices) {
                if (used[j] || resolved[j].row.kind != ImportKind.EXPENSE) continue
                val expense = resolved[j]
                if (income.row.date == expense.row.date && income.row.amount == expense.row.amount && income.accountId != expense.accountId) {
                    used[i] = true
                    used[j] = true
                    candidates += ImportTransfer(
                        listOf(minOf(income.row.lineNumber, expense.row.lineNumber), maxOf(income.row.lineNumber, expense.row.lineNumber)),
                        NewTransfer(income.row.amount!!, expense.accountId, income.accountId, income.row.date!!, income.row.note ?: expense.row.note),
                    )
                    break
                }
            }
        }

        for (i in resolved.indices) {
            if (used[i]) continue
            val r = resolved[i]
            candidates += when (r.row.kind) {
                ImportKind.INCOME -> ImportIncome(listOf(r.row.lineNumber), NewIncome(r.row.amount!!, r.accountId, null, r.row.date!!, r.row.note, TransactionOrigin.IMPORT))
                ImportKind.EXPENSE -> ImportExpense(
                    listOf(r.row.lineNumber),
                    NewExpense(r.row.amount!!, r.accountId, r.roomId!!, r.categoryId!!, r.row.date!!, r.row.note, TransactionOrigin.IMPORT),
                )

                null -> ImportSkipped(listOf(r.row.lineNumber), "Tipe tidak dikenal")
            }
        }

        return ImportPreview(candidates.sortedBy { it.lines.min() })
    }
}

/** Berapa transaksi berhasil, dilewati, dan gagal (dengan alasan) setelah [CsvImportService.commit]. */
data class ImportResult(val imported: Int, val skipped: Int, val failures: List<String>)

class CsvImportService(private val ledger: LedgerService) {
    suspend fun commit(preview: ImportPreview): ImportResult {
        var imported = 0
        val failures = mutableListOf<String>()
        for (candidate in preview.ready) {
            val result = when (candidate) {
                is ImportIncome -> ledger.recordIncome(candidate.income)
                is ImportExpense -> ledger.recordExpense(candidate.expense)
                is ImportTransfer -> ledger.recordTransfer(candidate.transfer)
                is ImportSkipped -> null
            }
            when (result) {
                is LedgerResult.Success -> imported++
                is LedgerResult.Failure -> failures += "Baris ${candidate.lines.joinToString()}: ${result.error}"
                null -> Unit
            }
        }
        return ImportResult(imported, preview.skipped.size, failures)
    }
}
