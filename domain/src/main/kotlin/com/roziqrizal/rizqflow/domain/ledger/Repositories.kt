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

    /** Akun milik pengguna yang belum diarsipkan, berurutan. Tidak pernah memuat akun anggota virtual. */
    suspend fun activeAccounts(): List<Account>

    /**
     * Semua akun milik pengguna termasuk yang terarsip: riwayat transaksi tetap menampilkan nama akunnya.
     * Tidak pernah memuat akun anggota virtual ([Account.sharedFrom] terisi): daftar ini dipakai untuk kelola akun,
     * pencocokan impor CSV, dan ekspor, yang semuanya hanya urusan akun pengguna sendiri.
     */
    suspend fun allAccounts(): List<Account>

    /**
     * [allAccounts] ditambah akun anggota virtual. Hanya untuk mencari nama akun sumber sebuah transaksi (baris `SHARED`
     * di daftar transaksi menampilkan "Dompet (Ayu)"); jangan dipakai untuk daftar yang dilihat atau dipilih pengguna.
     */
    suspend fun allAccountsIncludingMembers(): List<Account>

    suspend fun save(account: Account)

    /**
     * Menghapus akun milik pengguna beserta tombstone sinkronnya (atomik). Perilaku paling aman, USULAN Claude (belum
     * dikonfirmasi pemilik): ditolak dengan [LedgerError.ACCOUNT_IN_USE] bila akun masih dirujuk transaksi, transaksi
     * berulang, tagihan, utang, atau rencana DCA, dan dengan [LedgerError.LAST_ACCOUNT] bila ini satu-satunya akun aktif.
     * Favorit Catat kilat yang memakai akun ini ikut terhapus (kunci asing CASCADE) dan masing-masing mendapat tombstone.
     * Akun anggota virtual tidak bisa dihapus lewat sini ([LedgerError.ACCOUNT_NOT_FOUND]). Tidak ada UI yang memakainya;
     * Kelola akun tetap hanya mengarsipkan.
     */
    suspend fun delete(id: AccountId): LedgerResult<Unit>

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

    /**
     * Menghapus satu kategori beserta tombstone sinkronnya (atomik). Perilaku paling aman, USULAN Claude (belum
     * dikonfirmasi pemilik): ditolak dengan [LedgerError.CATEGORY_IN_USE] bila masih dirujuk transaksi, transaksi
     * berulang, tagihan, atau rencana DCA; [LedgerError.CATEGORY_IS_SYSTEM] untuk kategori sistem; dan
     * [LedgerError.LAST_CATEGORY] bila ini kategori biasa aktif terakhir di ruangnya. Favorit yang memakainya ikut
     * terhapus (CASCADE) dan masing-masing mendapat tombstone. Tidak ada UI yang memakainya.
     */
    suspend fun deleteCategory(id: CategoryId): LedgerResult<Unit>

    /**
     * Menghapus satu ruang beserta isinya (kategori, aturan alokasi, rencana DCA, profil trader, favorit) dan tombstone
     * sinkron untuk tiap rekaman yang ikut hilang (atomik; cascade ruang ke kategori tidak lewat [saveCategory], jadi
     * dicatat di sini). Perilaku paling aman, USULAN Claude (belum dikonfirmasi pemilik): ditolak dengan
     * [LedgerError.ROOM_NOT_ARCHIVED] bila ruang belum diarsipkan, [LedgerError.ROOM_SHARED] bila sedang dibagi
     * (ruang bersama aktif), dan [LedgerError.ROOM_IN_USE] bila masih punya riwayat (transaksi, potret alokasi,
     * transaksi berulang, tagihan) atau ada kategorinya yang masih dirujuk. Tidak ada UI yang memakainya.
     */
    suspend fun deleteRoom(id: RoomId): LedgerResult<Unit>

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

/**
 * Jumlah dan rentang tanggal pengeluaran buatan sendiri (`origin <> 'SHARED'`) di sebuah ruang — dasar dialog
 * konfirmasi "riwayat ruang ikut dibagikan?" saat ruang mulai dibagi (keputusan pemilik #4, sync-desain.md bagian J).
 * Lihat [TransactionRepository.historyStats].
 */
data class RoomHistoryStats(val count: Int, val oldest: LocalDate, val newest: LocalDate)

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

    /**
     * Menimpa pengeluaran atau transfer yang sudah ada (untuk pemasukan pakai [replaceIncome]).
     *
     * Bila transaksi lama adalah pengeluaran buatan sendiri di sebuah ruang bersama aktif dan versi baru tidak lagi
     * pengeluaran di ruang itu (ruangnya berubah, ke ruang non-bersama maupun ruang bersama lain, atau jenisnya
     * berubah), lokal mencatat satu baris di [pendingTombstones] untuk ruang LAMA dalam transaksi database yang sama,
     * supaya anggota ruang itu ikut menghapusnya (sync-desain.md bagian A). Pengeluaran yang jadi ada di ruang
     * bersama lain tetap terkirim sebagai versi hidup lewat [pendingSync] ruang barunya.
     */
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

    /**
     * Jumlah dan rentang tanggal pengeluaran buatan sendiri (`origin <> SHARED`) di [roomId] — dasar dialog konfirmasi
     * saat ruang mulai dibagi (keputusan pemilik #4: "ikut dibagikan, dengan konfirmasi eksplisit jumlah transaksi dan
     * rentang tanggal"). Null bila ruang belum punya pengeluaran buatan sendiri (ruang kosong: aktifkan tanpa dialog).
     */
    suspend fun historyStats(roomId: RoomId): RoomHistoryStats?

    /** Pengeluaran di [roomId] yang belum terkirim ke sync (Tahap 9, kolom `synced_at` null). */
    suspend fun pendingSync(roomId: RoomId): List<PendingSyncTransaction>

    /** Tandai transaksi-transaksi ini sudah terkirim ke sync, tanpa syarat. Mesin sync memakai [markSyncedIfUnchanged]. */
    suspend fun markSynced(ids: List<TransactionId>, atMillis: Long)

    /**
     * Tandai versi-versi yang baru saja terkirim sudah terkirim, HANYA bila barisnya masih versi yang sama
     * ([PendingSyncTransaction.changedAtMillis] == `updated_at` sekarang). Suntingan atau pemindahan ruang yang masuk
     * selama push berjalan mengosongkan penanda dan mengubah `updated_at`; tanpa syarat ini [markSynced] menimpa
     * penanda itu dan versi baru tidak pernah terkirim.
     */
    suspend fun markSyncedIfUnchanged(sent: List<PendingSyncTransaction>, atMillis: Long)

    /**
     * Transaksi di [roomId] yang dihapus lokal atau dipindah keluar dari ruang itu, tetapi tombstone-nya belum
     * terkirim ke sync (Tahap 9, roadmap.md "Sinkron penghapusan transaksi di ruang yang dibagi"). [delete] mencatat
     * baris ini tepat sebelum hapus fisik, [update] saat pengeluaran ruang bersama dipindah keluar; keduanya per
     * (transaksi, ruang), jadi satu transaksi bisa menunggu tombstone di beberapa ruang. [clearPendingTombstones]
     * membersihkannya setelah terkirim. Tombstone gugur sendiri bila transaksinya kembali ke ruang itu ([save], [update]).
     */
    suspend fun pendingTombstones(roomId: RoomId): List<PendingTombstone>

    /** Tandai tombstone-tombstone ini di [roomId] sudah terkirim ke sync (dibuang); tombstone transaksi yang sama di ruang lain tidak tersentuh. */
    suspend fun clearPendingTombstones(roomId: RoomId, ids: List<TransactionId>)

    /**
     * Terapkan satu baris hasil pull dari anggota lain. [transaction] data lengkap untuk baris
     * baru/diubah — insert, atau timpa yang sudah ada bila [changedAtMillis] lebih baru dari
     * [MoneyTransaction.updatedAtMillis] lokal (last-write-wins, server-sync.md "Model konflik
     * sinkronisasi"). Null bila [deleted] true: tombstone dari anggota lain, diterapkan dengan
     * aturan LWW yang sama (tombstone basi tidak boleh menghapus edit lokal yang lebih baru) —
     * baris lokal (bila ada dan tidak lebih baru) dihapus seperti [delete] biasa. Hasil terapan ini
     * tidak pernah ditandai pending push lagi (datang dari luar, bukan ubahan lokal).
     *
     * [fromRoom] (hanya bermakna untuk tombstone): ruang bersama tempat tombstone itu terbit. Tombstone adalah
     * pernyataan "transaksi ini sudah tidak ada DI RUANG ITU", bukan "hapus di mana pun": pengeluaran yang dipindah
     * penulisnya dari ruang A ke ruang B menerbitkan tombstone di A, sementara versi hidupnya di B bisa tiba lebih
     * dulu dan sudah menggeser baris lokal ke B. Karena itu baris lokal hanya dihapus bila masih berada di [fromRoom];
     * null berarti tanpa syarat ruang (perilaku lama, dipakai baris format lama).
     */
    suspend fun applyFromSync(
        id: TransactionId,
        transaction: MoneyTransaction?,
        changedAtMillis: Long,
        deleted: Boolean,
        fromRoom: RoomId? = null,
    )
}

/**
 * Satu rekaman amplop (peran-anggota.md D.2): berlaku terus sampai diubah (`from_month`); `amount = 0` berarti
 * amplop dihapus mulai bulan itu — lihat [EnvelopeRules][com.roziqrizal.rizqflow.domain.ledger.EnvelopeRules].
 * Tidak membawa [RoomId] sendiri: semua metode [EnvelopeRepository] sudah dipilah per ruang, sama seperti repo
 * lain di berkas ini.
 */
data class EnvelopeEntry(val memberId: String, val fromMonth: Int, val amount: Long)

/**
 * Penyimpanan proyeksi lokal amplop (tabel `shared_envelope`, peran-anggota.md E.2) — nilai saja. Versi dan
 * `author` untuk sinkron tetap di `sync_record` lewat `SharedRecordStore`
 * ([com.roziqrizal.rizqflow.domain.sync.EnvelopeSyncHandler] yang menggabungkan keduanya), bukan di sini —
 * sama pembagian tugas dengan [TransactionRepository] (data) dan `SyncStore` (status kotor/LWW) untuk cakupan
 * pribadi.
 */
interface EnvelopeRepository {
    suspend fun find(roomId: RoomId, memberId: String, fromMonth: Int): EnvelopeEntry?

    /** Semua rekaman amplop ruang ini, semua anggota, semua bulan (dasar `EnvelopeRules.summarize`). */
    suspend fun entries(roomId: RoomId): List<EnvelopeEntry>

    /** Menulis satu rekaman (baru, atau mengganti nilai `from_month` yang sama). TIDAK menandai kotor untuk
     * sinkron; pemanggil ([com.roziqrizal.rizqflow.domain.sync.EnvelopeService]) yang menandainya.
     */
    suspend fun upsert(roomId: RoomId, entry: EnvelopeEntry)
}

/** Pengaturan generik kunci/nilai dan hari yang ditandai "Tidak ada". Dasar pengingat malam (S26). */
interface SettingsRepository {
    suspend fun get(key: String): String?

    suspend fun put(key: String, value: String)

    suspend fun isDayChecked(day: LocalDate): Boolean

    suspend fun markDayChecked(day: LocalDate)
}
