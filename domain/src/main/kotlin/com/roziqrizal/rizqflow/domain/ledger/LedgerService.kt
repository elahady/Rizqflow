package com.roziqrizal.rizqflow.domain.ledger

import com.roziqrizal.rizqflow.domain.allocation.AllocationEngine
import com.roziqrizal.rizqflow.domain.allocation.AllocationMode
import com.roziqrizal.rizqflow.domain.allocation.AllocationResult
import com.roziqrizal.rizqflow.domain.allocation.AllocationRule
import com.roziqrizal.rizqflow.domain.model.AccountId
import com.roziqrizal.rizqflow.domain.model.CategoryId
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.model.TransactionId
import com.roziqrizal.rizqflow.domain.model.TransactionKind
import com.roziqrizal.rizqflow.domain.model.TransactionOrigin
import com.roziqrizal.rizqflow.domain.money.Money
import java.time.LocalDate
import java.time.YearMonth

/** Alasan sebuah perintah ditolak. Layar memetakannya ke pesan; tidak ada yang berupa galat program. */
enum class LedgerError {
    AMOUNT_NOT_POSITIVE,
    ACCOUNT_NOT_FOUND,
    ACCOUNT_ARCHIVED,
    CURRENCY_MISMATCH,
    ROOM_NOT_FOUND,
    ROOM_ARCHIVED,
    CATEGORY_NOT_IN_ROOM,
    SAME_ACCOUNT,
    TRANSACTION_NOT_FOUND,
    NOT_AN_INCOME,

    /** Isian layar ubah tidak sejenis dengan transaksinya (misalnya isian pengeluaran untuk pemasukan). */
    KIND_MISMATCH,
    NOTE_TOO_LONG,
    SOURCE_TOO_LONG,
    INVALID_NAME,
    NAME_TAKEN,
    ROOM_LIMIT_REACHED,
    LAST_ACCOUNT,
    CATEGORY_NOT_FOUND,
    CATEGORY_IS_SYSTEM,
    LAST_CATEGORY,
    FAVORITE_NOT_FOUND,
    FAVORITE_LIMIT_REACHED,
    RULES_NOT_100_PERCENT,
    RULES_INVALID,
    WORKSPACE_NOT_EMPTY,

    /** Aksi butuh paket Pro (mis. menyalakan aturan alokasi lanjutan) tapi paket sekarang tidak membukanya. */
    FEATURE_LOCKED,

    /** Ruang Menunaikan tidak (lagi) punya kategori sistem Zakat mal; seharusnya tidak pernah terjadi. */
    ZAKAT_CATEGORY_MISSING,
    INVALID_WEALTH_ITEM,
    GOLD_PRICE_REQUIRED,

    /** Ruang yang dipilih tidak (lagi) punya kategori sistem Tak terlacak; seharusnya tidak pernah terjadi. */
    UNTRACKED_CATEGORY_MISSING,

    /** Jam atau menit pengingat di luar jangkauan sehari. */
    INVALID_REMINDER_TIME,

    /** Balasan notifikasi malam tidak berbentuk "catatan nominal", mis. "kopi 25000". */
    QUICK_REPLY_UNREADABLE,

    /** Nominal, modal, persentase risiko, atau tanggal DCA di luar batas yang sah (sistem per peran). */
    INVALID_ROLE_SETTINGS,

    /** Ruang sudah punya peran lain (Trader atau Investor); lepas dulu sebelum memasang yang baru. */
    ROLE_CONFLICT,

    /** Ruang belum punya peran yang dibutuhkan aksi ini (mis. mencatat DCA tanpa jadwal). */
    ROLE_NOT_SET,

    /** Profil harta zakat yang dimaksud tidak (lagi) ada. */
    PROFILE_NOT_FOUND,

    /** Profil harta terakhir yang aktif tidak boleh diarsipkan. */
    LAST_PROFILE,

    /** Aturan transaksi berulang yang dimaksud tidak (lagi) ada. */
    RECURRING_NOT_FOUND,

    /** Jadwal berulang tidak sah: tanggal awal sudah lewat, atau tanggal akhir sebelum tanggal awal. */
    INVALID_SCHEDULE,

    /** Tagihan atau cicilan yang dimaksud tidak (lagi) ada. */
    BILL_NOT_FOUND,

    /** Tagihan sudah lunas (sekali bayar sudah dibayar, atau seluruh cicilan sudah terbayar); tidak ada yang bisa dibayar lagi. */
    BILL_FINISHED,

    /** Jumlah cicilan tidak sah: kurang dari 1, dipasang pada tagihan sekali bayar, atau lebih kecil dari yang sudah dibayar. */
    INVALID_INSTALLMENTS,

    /** Utang atau piutang yang dimaksud tidak (lagi) ada. */
    DEBT_NOT_FOUND,

    /** Pelunasan melebihi sisa utang atau piutang (atau yang sudah lunas dibayar lagi). */
    DEBT_OVERPAID,

    /** Jatuh tempo utang atau piutang sebelum tanggal pinjam. */
    INVALID_DUE_DATE,

    /** Nama pihak (teman, keluarga, atau lainnya) kosong atau terlalu panjang. */
    INVALID_PARTY,
}

sealed interface LedgerResult<out T> {
    data class Success<out T>(val value: T) : LedgerResult<T>

    data class Failure(val error: LedgerError) : LedgerResult<Nothing>
}

internal fun failure(error: LedgerError): LedgerResult.Failure = LedgerResult.Failure(error)

data class NewIncome(
    val amount: Money,
    val accountId: AccountId,
    val source: String?,
    val occurredOn: LocalDate,
    val note: String? = null,
    val origin: TransactionOrigin = TransactionOrigin.MANUAL,
    /** "Ubah sekali ini" (S07): pembagian khusus untuk pemasukan ini; null memakai aturan alokasi sekarang. Aturan tidak berubah. */
    val overrideRules: List<AllocationRule>? = null,
    /** Pengenal tetap untuk transaksi ini (transaksi berulang memakai satu pengenal per kemunculan); null = dibuatkan baru. */
    val id: String? = null,
)

data class NewExpense(
    val amount: Money,
    val accountId: AccountId,
    val roomId: RoomId,
    val categoryId: CategoryId,
    val occurredOn: LocalDate,
    val note: String? = null,
    val origin: TransactionOrigin = TransactionOrigin.MANUAL,
    /** Lihat [NewIncome.id]. */
    val id: String? = null,
)

/**
 * Perpindahan uang utang-piutang: [kind] adalah [TransactionKind.LOAN_OUT] (uang keluar dari akun,
 * mis. meminjamkan atau membayar utang) atau [TransactionKind.LOAN_IN] (uang masuk ke akun).
 */
data class NewLoanMovement(
    val kind: TransactionKind,
    val amount: Money,
    val accountId: AccountId,
    val occurredOn: LocalDate,
    val note: String? = null,
    /** Lihat [NewIncome.id]. */
    val id: String? = null,
)

data class NewTransfer(
    val amount: Money,
    val from: AccountId,
    val to: AccountId,
    val occurredOn: LocalDate,
    val note: String? = null,
    val origin: TransactionOrigin = TransactionOrigin.MANUAL,
    /** Lihat [NewIncome.id]. */
    val id: String? = null,
)

/** Keadaan satu transaksi beserta potret alokasinya: bahan Urungkan setelah ubah atau hapus. */
data class TransactionSnapshot(val transaction: MoneyTransaction, val entries: List<AllocationEntry>)

/** Jatah ruang bulan itu terlampaui bila pengeluaran disimpan: [spentAfter] sudah termasuk pengeluaran yang sedang dicatat. */
data class BudgetWarning(val room: Room, val allocated: Money, val spentAfter: Money)

/** Pemasukan yang tersimpan beserta hasil alokasinya, termasuk bagian yang belum dialirkan. */
data class IncomeReceipt(val transaction: MoneyTransaction, val allocation: AllocationResult)

/**
 * Mencatat, mengubah, dan menghapus transaksi (S06 sampai S09).
 *
 * Pemasukan dialirkan sesuai aturan alokasi saat itu dan **potretnya disimpan** (keputusan
 * 2026-09-21): mengubah aturan sesudahnya tidak mengubah riwayat, dan mengubah nominal
 * menghitung ulang dengan persentase potret itu. Tanpa aturan (mulai kosong), pemasukan tetap
 * satu transaksi dan seluruhnya menjadi "belum dialirkan".
 */
class LedgerService(
    private val accounts: AccountRepository,
    private val rooms: RoomRepository,
    private val transactions: TransactionRepository,
    private val newId: () -> String,
    private val nowMillis: () -> Long,
) {

    /** Pratinjau S07: bagaimana [amount] akan dialirkan dengan aturan sekarang (atau [rules] bila diberikan). Tidak menyimpan apa pun. */
    suspend fun previewIncome(amount: Money, rules: List<AllocationRule>? = null): AllocationResult =
        if (rules != null) AllocationEngine.allocate(amount, rules) else currentAllocation(amount).second

    suspend fun recordIncome(command: NewIncome): LedgerResult<IncomeReceipt> {
        if (!command.amount.isPositive) return failure(LedgerError.AMOUNT_NOT_POSITIVE)
        val note = cleanNote(command.note) ?: return failure(LedgerError.NOTE_TOO_LONG)
        val source = cleanSource(command.source) ?: return failure(LedgerError.SOURCE_TOO_LONG)
        checkAccount(command.accountId, command.amount)?.let { return failure(it) }

        val (rules, allocation) = if (command.overrideRules != null) {
            if (!overrideIsValid(command.overrideRules)) return failure(LedgerError.RULES_INVALID)
            command.overrideRules to AllocationEngine.allocate(command.amount, command.overrideRules)
        } else {
            currentAllocation(command.amount)
        }
        val now = nowMillis()
        val transaction = MoneyTransaction(
            id = TransactionId(command.id ?: newId()),
            kind = TransactionKind.INCOME,
            amount = command.amount,
            accountId = command.accountId,
            incomeSource = source.value,
            occurredOn = command.occurredOn,
            note = note.value,
            origin = command.origin,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        transactions.saveIncome(transaction, snapshotEntries(transaction.id, rules, allocation, now))
        return LedgerResult.Success(IncomeReceipt(transaction, allocation))
    }

    suspend fun recordExpense(command: NewExpense): LedgerResult<MoneyTransaction> {
        if (!command.amount.isPositive) return failure(LedgerError.AMOUNT_NOT_POSITIVE)
        val note = cleanNote(command.note) ?: return failure(LedgerError.NOTE_TOO_LONG)
        checkAccount(command.accountId, command.amount)?.let { return failure(it) }

        val room = rooms.find(command.roomId) ?: return failure(LedgerError.ROOM_NOT_FOUND)
        if (room.archived) return failure(LedgerError.ROOM_ARCHIVED)
        val category = rooms.findCategory(command.categoryId)
        if (category == null || category.roomId != room.id || category.archived) return failure(LedgerError.CATEGORY_NOT_IN_ROOM)

        val now = nowMillis()
        val transaction = MoneyTransaction(
            id = TransactionId(command.id ?: newId()),
            kind = TransactionKind.EXPENSE,
            amount = command.amount,
            accountId = command.accountId,
            roomId = room.id,
            categoryId = category.id,
            occurredOn = command.occurredOn,
            note = note.value,
            origin = command.origin,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        transactions.save(transaction)
        return LedgerResult.Success(transaction)
    }

    suspend fun recordTransfer(command: NewTransfer): LedgerResult<MoneyTransaction> {
        if (!command.amount.isPositive) return failure(LedgerError.AMOUNT_NOT_POSITIVE)
        if (command.from == command.to) return failure(LedgerError.SAME_ACCOUNT)
        val note = cleanNote(command.note) ?: return failure(LedgerError.NOTE_TOO_LONG)
        checkAccount(command.from, command.amount)?.let { return failure(it) }
        checkAccount(command.to, command.amount)?.let { return failure(it) }

        val now = nowMillis()
        val transaction = MoneyTransaction(
            id = TransactionId(command.id ?: newId()),
            kind = TransactionKind.TRANSFER,
            amount = command.amount,
            accountId = command.from,
            toAccountId = command.to,
            occurredOn = command.occurredOn,
            note = note.value,
            origin = command.origin,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        transactions.save(transaction)
        return LedgerResult.Success(transaction)
    }

    /**
     * Mencatat perpindahan uang utang-piutang: hanya menggeser saldo akun, tanpa ruang, kategori,
     * atau alokasi. Dipanggil `DebtService`; layar Catat tidak menawarkannya.
     */
    suspend fun recordLoanMovement(command: NewLoanMovement): LedgerResult<MoneyTransaction> {
        if (command.kind != TransactionKind.LOAN_OUT && command.kind != TransactionKind.LOAN_IN) return failure(LedgerError.KIND_MISMATCH)
        if (!command.amount.isPositive) return failure(LedgerError.AMOUNT_NOT_POSITIVE)
        val note = cleanNote(command.note) ?: return failure(LedgerError.NOTE_TOO_LONG)
        checkAccount(command.accountId, command.amount)?.let { return failure(it) }

        val now = nowMillis()
        val transaction = MoneyTransaction(
            id = TransactionId(command.id ?: newId()),
            kind = command.kind,
            amount = command.amount,
            accountId = command.accountId,
            occurredOn = command.occurredOn,
            note = note.value,
            origin = TransactionOrigin.LOAN,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        transactions.save(transaction)
        return LedgerResult.Success(transaction)
    }

    /**
     * Mengubah nominal pemasukan (S09). Alokasi dihitung ulang dengan persentase potret lama;
     * ruang dan persentasenya tidak berubah, dan aturan alokasi saat ini tidak dipakai.
     */
    suspend fun editIncomeAmount(id: TransactionId, newAmount: Money): LedgerResult<IncomeReceipt> {
        val current = transactions.find(id) ?: return failure(LedgerError.TRANSACTION_NOT_FOUND)
        if (current.kind != TransactionKind.INCOME) return failure(LedgerError.NOT_AN_INCOME)
        if (!newAmount.isPositive) return failure(LedgerError.AMOUNT_NOT_POSITIVE)
        if (newAmount.currency != current.amount.currency) return failure(LedgerError.CURRENCY_MISMATCH)

        val old = transactions.entriesOf(id)
        val rules = old.map { AllocationRule(it.roomId, it.share) }
        val allocation = AllocationEngine.allocate(newAmount, rules)
        val entries = old.zip(allocation.shares) { entry, share -> entry.copy(amount = share.amount) }
        val updated = current.copy(amount = newAmount, updatedAtMillis = nowMillis())
        transactions.replaceIncome(updated, entries)
        return LedgerResult.Success(IncomeReceipt(updated, allocation))
    }

    /** Keadaan sekarang sebuah transaksi (dengan potret alokasinya bila pemasukan); dipegang layar sebelum menghapus supaya bisa diurungkan. */
    suspend fun snapshotOf(id: TransactionId): TransactionSnapshot? {
        val tx = transactions.find(id) ?: return null
        return TransactionSnapshot(tx, if (tx.kind == TransactionKind.INCOME) transactions.entriesOf(id) else emptyList())
    }

    /**
     * Mengubah transaksi dari isian layar Detail (S09). Jenis transaksi tidak bisa diganti. Pemasukan:
     * nominal baru dihitung ulang dengan persentase potret lama (ruang dan persentasenya tidak berubah);
     * tanpa perubahan nominal, potret dibiarkan. Akun, ruang, atau kategori yang sudah terarsip boleh
     * tetap dipakai selama tidak diganti. Hasilnya adalah keadaan **sebelum** diubah, untuk Urungkan.
     */
    suspend fun updateTransaction(id: TransactionId, draft: CatatDraft): LedgerResult<TransactionSnapshot> {
        val current = transactions.find(id) ?: return failure(LedgerError.TRANSACTION_NOT_FOUND)
        val kind = when (draft.mode) {
            CatatMode.INCOME -> TransactionKind.INCOME
            CatatMode.EXPENSE -> TransactionKind.EXPENSE
            CatatMode.TRANSFER -> TransactionKind.TRANSFER
        }
        if (current.kind != kind) return failure(LedgerError.KIND_MISMATCH)
        val amount = draft.amount
        if (!amount.isPositive) return failure(LedgerError.AMOUNT_NOT_POSITIVE)
        if (amount.currency != current.amount.currency) return failure(LedgerError.CURRENCY_MISMATCH)
        val note = cleanNote(draft.note) ?: return failure(LedgerError.NOTE_TOO_LONG)
        val before = snapshotOf(id) ?: return failure(LedgerError.TRANSACTION_NOT_FOUND)
        val now = nowMillis()

        when (kind) {
            TransactionKind.INCOME -> {
                val source = cleanSource(draft.source) ?: return failure(LedgerError.SOURCE_TOO_LONG)
                val accountId = draft.accountId ?: return failure(LedgerError.ACCOUNT_NOT_FOUND)
                checkAccount(accountId, amount, unchanged = current.accountId)?.let { return failure(it) }
                val entries = if (amount == current.amount) {
                    before.entries
                } else {
                    val allocation = AllocationEngine.allocate(amount, before.entries.map { AllocationRule(it.roomId, it.share) })
                    before.entries.zip(allocation.shares) { entry, share -> entry.copy(amount = share.amount) }
                }
                val updated = current.copy(
                    amount = amount, accountId = accountId, incomeSource = source.value,
                    occurredOn = draft.date, note = note.value, updatedAtMillis = now,
                )
                transactions.replaceIncome(updated, entries)
            }

            TransactionKind.EXPENSE -> {
                val accountId = draft.accountId ?: return failure(LedgerError.ACCOUNT_NOT_FOUND)
                checkAccount(accountId, amount, unchanged = current.accountId)?.let { return failure(it) }
                val room = rooms.find(draft.roomId ?: return failure(LedgerError.ROOM_NOT_FOUND)) ?: return failure(LedgerError.ROOM_NOT_FOUND)
                if (room.archived && room.id != current.roomId) return failure(LedgerError.ROOM_ARCHIVED)
                val category = rooms.findCategory(draft.categoryId ?: return failure(LedgerError.CATEGORY_NOT_IN_ROOM))
                if (category == null || category.roomId != room.id || (category.archived && category.id != current.categoryId)) {
                    return failure(LedgerError.CATEGORY_NOT_IN_ROOM)
                }
                transactions.update(
                    current.copy(
                        amount = amount, accountId = accountId, roomId = room.id, categoryId = category.id,
                        occurredOn = draft.date, note = note.value, updatedAtMillis = now,
                    ),
                )
            }

            TransactionKind.TRANSFER -> {
                val from = draft.accountId ?: return failure(LedgerError.ACCOUNT_NOT_FOUND)
                val to = draft.toAccountId ?: return failure(LedgerError.ACCOUNT_NOT_FOUND)
                if (from == to) return failure(LedgerError.SAME_ACCOUNT)
                checkAccount(from, amount, unchanged = current.accountId)?.let { return failure(it) }
                checkAccount(to, amount, unchanged = current.toAccountId)?.let { return failure(it) }
                transactions.update(
                    current.copy(amount = amount, accountId = from, toAccountId = to, occurredOn = draft.date, note = note.value, updatedAtMillis = now),
                )
            }

            // Transaksi pinjaman dikelola di Utang-piutang; nominalnya terikat pada sisa utang, jadi tidak diubah dari sini.
            TransactionKind.LOAN_OUT, TransactionKind.LOAN_IN -> return failure(LedgerError.KIND_MISMATCH)
        }
        return LedgerResult.Success(before)
    }

    /** Mengembalikan transaksi ke keadaan [snapshot]: menghidupkan lagi yang dihapus, atau membatalkan perubahan. Dasar tombol Urungkan. */
    suspend fun restore(snapshot: TransactionSnapshot) {
        val tx = snapshot.transaction
        val exists = transactions.find(tx.id) != null
        when {
            tx.kind == TransactionKind.INCOME ->
                if (exists) transactions.replaceIncome(tx, snapshot.entries) else transactions.saveIncome(tx, snapshot.entries)

            exists -> transactions.update(tx)
            else -> transactions.save(tx)
        }
    }

    suspend fun delete(id: TransactionId): LedgerResult<Unit> {
        transactions.find(id) ?: return failure(LedgerError.TRANSACTION_NOT_FOUND)
        transactions.delete(id)
        return LedgerResult.Success(Unit)
    }

    /** Pembagian sekali ini: ruang aktif, tanpa ganda, dan tidak melebihi 100% (sisanya menjadi belum dialirkan). */
    private suspend fun overrideIsValid(rules: List<AllocationRule>): Boolean {
        if (rules.map { it.roomId }.toSet().size != rules.size) return false
        if (rules.sumOf { it.share.value } > com.roziqrizal.rizqflow.domain.allocation.BasisPoints.FULL) return false
        return rules.all { rooms.find(it.roomId)?.archived == false }
    }

    /**
     * Banner lembut S06 (F3): pengeluaran ini membuat terpakai bulan itu melewati jatah ruang. Hanya
     * bila jatah bulan itu ada (di atas nol); bulan tanpa pemasukan tidak memunculkan banner terus-menerus.
     * Tidak pernah menghalangi menyimpan. Bulan mengikuti kalender Masehi.
     */
    suspend fun budgetWarning(roomId: RoomId, amount: Money, date: LocalDate, excluding: TransactionId? = null): BudgetWarning? {
        val room = rooms.find(roomId) ?: return null
        val month = YearMonth.from(date)
        val totals = transactions.roomTotals(month.atDay(1), month.atEndOfMonth())
        val allocated = totals.allocated[roomId] ?: Money.zero(amount.currency)
        var spent = totals.spent[roomId] ?: Money.zero(amount.currency)
        // Saat mengubah pengeluaran, nilai lamanya sudah ada di terpakai bulan itu dan tidak boleh terhitung dua kali.
        val own = excluding?.let { transactions.find(it) }
        if (own != null && own.kind == TransactionKind.EXPENSE && own.roomId == roomId && YearMonth.from(own.occurredOn) == month) spent -= own.amount
        val spentAfter = spent + amount
        return if (allocated.isPositive && spentAfter > allocated) BudgetWarning(room, allocated, spentAfter) else null
    }

    /** [unchanged]: akun yang sudah dipakai transaksi ini; boleh tetap dipakai walau sudah terarsip. */
    private suspend fun checkAccount(id: AccountId, amount: Money, unchanged: AccountId? = null): LedgerError? {
        val account = accounts.find(id) ?: return LedgerError.ACCOUNT_NOT_FOUND
        if (account.archived && id != unchanged) return LedgerError.ACCOUNT_ARCHIVED
        if (account.currency != amount.currency) return LedgerError.CURRENCY_MISMATCH
        return null
    }

    /**
     * Aturan dan hasil alokasi sekarang, mengikuti mode yang aktif ([AllocationMode]). Mode WATERFALL
     * dihitung dulu dengan [AllocationEngine.allocateWaterfall], lalu diterjemahkan menjadi persentase
     * ([AllocationEngine.impliedShares]) supaya potretnya ([AllocationEntry]) tetap berbentuk
     * persentase seperti biasa dan bisa dihitung ulang lewat mekanisme yang sudah ada saat nominal
     * diubah (S09) — tidak perlu kolom snapshot baru untuk mode lanjutan.
     */
    private suspend fun currentAllocation(amount: Money): Pair<List<AllocationRule>, AllocationResult> =
        when (rooms.allocationMode()) {
            AllocationMode.WATERFALL -> {
                val allocation = AllocationEngine.allocateWaterfall(amount, rooms.caps())
                AllocationEngine.impliedShares(amount, allocation.shares) to allocation
            }

            AllocationMode.PERCENTAGE -> {
                val rules = rooms.rules()
                rules to AllocationEngine.allocate(amount, rules)
            }
        }

    /**
     * Satu baris potret per ruang yang punya bagian (persentase lebih dari nol), termasuk yang
     * jumlahnya 0 karena nominalnya kecil: persentasenya harus ikut tersimpan supaya bisa dihitung
     * ulang saat nominal diubah menjadi lebih besar.
     */
    private fun snapshotEntries(
        incomeId: TransactionId,
        rules: List<AllocationRule>,
        allocation: AllocationResult,
        now: Long,
    ): List<AllocationEntry> = rules.zip(allocation.shares)
        .filter { (rule, _) -> rule.share.value > 0 }
        .map { (rule, share) -> AllocationEntry(newId(), incomeId, rule.roomId, rule.share, share.amount, now) }

    private class Cleaned(val value: String?)

    /** Catatan dirapikan; `null` bila terlalu panjang. Kosong menjadi nilai kosong yang sah. */
    private fun cleanNote(raw: String?): Cleaned? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() }
        return if (text != null && text.length > NOTE_MAX) null else Cleaned(text)
    }

    private fun cleanSource(raw: String?): Cleaned? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() }
        return if (text != null && text.length > SOURCE_MAX) null else Cleaned(text)
    }

    companion object {
        const val NOTE_MAX = 200
        const val SOURCE_MAX = 40
    }
}
