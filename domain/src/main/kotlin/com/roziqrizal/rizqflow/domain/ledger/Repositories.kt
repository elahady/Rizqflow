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

/*
 * Antarmuka penyimpanan. Implementasinya di :data (Room, satu database per akun). Semua fungsi
 * yang mengubah lebih dari satu baris harus atomik: gagal di tengah tidak boleh meninggalkan
 * sebagian data.
 */

/** Isi awal ruang kerja satu akun: hasil onboarding. */
data class WorkspaceSnapshot(
    val rooms: List<Room>,
    val categories: List<Category>,
    val rules: List<AllocationRule>,
    val accounts: List<Account>,
)

interface WorkspaceRepository {
    /** Benar bila belum ada ruang maupun akun (akun baru yang belum onboarding). */
    suspend fun isEmpty(): Boolean

    /** Menyimpan seluruh isi awal sekaligus (atomik). */
    suspend fun initialize(snapshot: WorkspaceSnapshot)
}

interface AccountRepository {
    suspend fun find(id: AccountId): Account?

    /** Akun yang belum diarsipkan, berurutan. */
    suspend fun activeAccounts(): List<Account>

    /** Semua akun termasuk yang terarsip: riwayat transaksi tetap menampilkan nama akunnya. */
    suspend fun allAccounts(): List<Account>

    suspend fun save(account: Account)

    /** Saldo menurut catatan: saldo awal + pemasukan - pengeluaran +/- transfer. */
    suspend fun balance(id: AccountId): Money

    /**
     * Mencari atau membuat akun anggota virtual (Tahap 2 sync-desain.md bagian B/E, keputusan
     * pemilik #0): dipakai [com.roziqrizal.rizqflow.domain.sync.SyncCoordinator] saat menerapkan
     * pengeluaran anggota lain di ruang bersama, supaya baris itu punya akun FK yang sah TANPA
     * pernah menyentuh atau mencocokkan akun nyata milik penerima (itu bug Tahap 9 lama). Id
     * deterministik dari (roomId, author, accountName) — panggilan ulang dengan masukan yang sama
     * mengembalikan akun yang sama, idempoten. TIDAK pernah ditandai kotor untuk sync pribadi
     * (bukan data milik pengguna ini) dan TIDAK pernah masuk [activeAccounts].
     */
    suspend fun findOrCreateVirtualMember(roomId: RoomId, author: String, accountName: String): Account
}

interface RoomRepository {
    /** Ruang yang belum diarsipkan, berurutan menurut prioritas. */
    suspend fun activeRooms(): List<Room>

    /** Semua ruang termasuk yang terarsip (untuk riwayat). */
    suspend fun allRooms(): List<Room>

    /** Semua kategori termasuk yang terarsip (untuk riwayat). */
    suspend fun allCategories(): List<Category>

    suspend fun find(id: RoomId): Room?

    /** Aturan alokasi ruang aktif, berurutan menurut prioritas ruang. Ruang tanpa aturan dianggap 0%. */
    suspend fun rules(): List<AllocationRule>

    suspend fun categories(roomId: RoomId): List<Category>

    suspend fun findCategory(id: CategoryId): Category?

    /** Menyimpan satu kategori (baru, diganti nama, atau diarsipkan). */
    suspend fun saveCategory(category: Category)

    /** Menambah ruang beserta kategori awalnya (atomik). */
    suspend fun addRoom(room: Room, categories: List<Category>)

    /** Menambah beberapa ruang beserta kategori dan aturannya sekaligus (atomik), misalnya pola Tiga hak. */
    suspend fun addRooms(rooms: List<Room>, categories: List<Category>, rules: List<AllocationRule>)

    /**
     * Menyimpan perubahan ruang (nama, urutan, arsip) sekaligus (atomik). Bila [rules] diberikan, aturan
     * alokasi ruang aktif diganti di transaksi yang sama.
     */
    suspend fun saveRooms(rooms: List<Room>, rules: List<AllocationRule>? = null)

    /** Mengganti seluruh aturan alokasi (atomik). */
    suspend fun replaceRules(rules: List<AllocationRule>)

    /** Mode aturan alokasi sekarang: PERCENTAGE (dasar, gratis) atau WATERFALL (lanjutan, Pro). */
    suspend fun allocationMode(): AllocationMode

    /** Mengganti mode. Tidak memeriksa entitlement; pemanggil ([RuleService]) yang menjaganya. */
    suspend fun setAllocationMode(mode: AllocationMode)

    /** Batas atas per ruang aktif untuk mode WATERFALL, berurutan menurut prioritas ruang. */
    suspend fun caps(): List<AllocationCap>

    /** Mengganti seluruh batas atas (atomik). Persentase ([rules]) tidak ikut berubah. */
    suspend fun replaceCaps(caps: List<AllocationCap>)
}

/** Jatah dan terpakai per ruang untuk satu rentang hari. Ruang tanpa baris dianggap nol. */
data class RoomTotals(val allocated: Map<RoomId, Money>, val spent: Map<RoomId, Money>)

/** Satu transaksi menunggu dikirim ke sync (Tahap 9) — lihat [TransactionRepository.pendingSync]. */
data class PendingSyncTransaction(
    val transaction: MoneyTransaction,
    val changedAtMillis: Long,
)

/**
 * Satu transaksi yang dihapus di ruang bersama, menunggu tombstone terkirim ke sync (Tahap 9,
 * roadmap.md "Sinkron penghapusan transaksi di ruang yang dibagi") — lihat
 * [TransactionRepository.pendingTombstones]. Dicatat terpisah dari tabel transaksi karena baris
 * aslinya sudah dihapus fisik secara lokal (append-only hanya berlaku di server, bukan di
 * perangkat); tombstone inilah satu-satunya jejak yang tersisa untuk diteruskan.
 */
data class PendingTombstone(
    val transactionId: TransactionId,
    val deletedAtMillis: Long,
)

interface TransactionRepository {
    suspend fun find(id: TransactionId): MoneyTransaction?

    /** Alokasi satu pemasukan, berurutan menurut prioritas ruang. */
    suspend fun entriesOf(incomeId: TransactionId): List<AllocationEntry>

    /** Menyimpan pemasukan beserta potret alokasinya (atomik). */
    suspend fun saveIncome(transaction: MoneyTransaction, entries: List<AllocationEntry>)

    /** Menyimpan pengeluaran atau transfer. */
    suspend fun save(transaction: MoneyTransaction)

    /** Menimpa pengeluaran atau transfer yang sudah ada (untuk pemasukan pakai [replaceIncome]). */
    suspend fun update(transaction: MoneyTransaction)

    /** Mengganti pemasukan dan seluruh potret alokasinya (atomik). */
    suspend fun replaceIncome(transaction: MoneyTransaction, entries: List<AllocationEntry>)

    /**
     * Hapus fisik; potret alokasi ikut terhapus. Bila transaksinya EXPENSE dan punya [RoomId]
     * (ruang mana pun — SyncEngine yang memutuskan ruang mana yang benar-benar disinkron), lokal
     * ikut mencatat satu baris di [pendingTombstones] sebelum hapus, supaya anggota ruang bersama
     * lain ikut menghapusnya saat sync berikutnya (Tahap 9).
     */
    suspend fun delete(id: TransactionId)

    /** Transaksi antara dua hari (inklusif), terbaru dulu. */
    suspend fun between(from: LocalDate, to: LocalDate): List<MoneyTransaction>

    /** Transaksi yang terakhir dicatat ([kind] null = jenis apa pun); dasar bawaan akun dan ruang di Catat. */
    suspend fun latest(kind: TransactionKind?): MoneyTransaction?

    /** Ruang dari pengeluaran terakhir di akun ini; null bila akun itu belum pernah punya pengeluaran. Dasar Koreksi saldo (S25). */
    suspend fun latestExpenseRoom(accountId: AccountId): RoomId?

    /** Jatah (alokasi dari pemasukan) dan terpakai (pengeluaran) per ruang antara dua hari (inklusif). */
    suspend fun roomTotals(from: LocalDate, to: LocalDate): RoomTotals

    /** Pengeluaran di [roomId] yang belum terkirim ke sync (Tahap 9, kolom `synced_at` null). */
    suspend fun pendingSync(roomId: RoomId): List<PendingSyncTransaction>

    /** Tandai transaksi-transaksi ini sudah terkirim ke sync. */
    suspend fun markSynced(ids: List<TransactionId>, atMillis: Long)

    /**
     * Transaksi di [roomId] yang dihapus lokal tapi tombstone-nya belum terkirim ke sync (Tahap 9,
     * roadmap.md "Sinkron penghapusan transaksi di ruang yang dibagi"). [delete] mencatat baris ini
     * tepat sebelum hapus fisik; [clearPendingTombstones] membersihkannya setelah terkirim.
     */
    suspend fun pendingTombstones(roomId: RoomId): List<PendingTombstone>

    /** Tandai tombstone-tombstone ini sudah terkirim ke sync (dibuang, tidak perlu disimpan lagi). */
    suspend fun clearPendingTombstones(ids: List<TransactionId>)

    /**
     * Terapkan satu baris hasil pull dari anggota lain. [transaction] data lengkap untuk baris
     * baru/diubah — insert, atau timpa yang sudah ada bila [changedAtMillis] lebih baru dari
     * [MoneyTransaction.updatedAtMillis] lokal (last-write-wins, server-sync.md "Model konflik
     * sinkronisasi"). Null bila [deleted] true: tombstone dari anggota lain, diterapkan dengan
     * aturan LWW yang sama (tombstone basi tidak boleh menghapus edit lokal yang lebih baru) —
     * baris lokal (bila ada dan tidak lebih baru) dihapus seperti [delete] biasa. Hasil terapan ini
     * tidak pernah ditandai pending push lagi (datang dari luar, bukan ubahan lokal).
     */
    suspend fun applyFromSync(id: TransactionId, transaction: MoneyTransaction?, changedAtMillis: Long, deleted: Boolean)
}

/** Pengaturan generik kunci/nilai dan hari yang ditandai "Tidak ada". Dasar pengingat malam (S26). */
interface SettingsRepository {
    suspend fun get(key: String): String?

    suspend fun put(key: String, value: String)

    suspend fun isDayChecked(day: LocalDate): Boolean

    suspend fun markDayChecked(day: LocalDate)
}
