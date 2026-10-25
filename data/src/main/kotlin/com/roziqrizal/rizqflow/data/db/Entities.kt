package com.roziqrizal.rizqflow.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.roziqrizal.rizqflow.domain.debt.DebtDirection
import com.roziqrizal.rizqflow.domain.model.AccountKind
import com.roziqrizal.rizqflow.domain.model.GoldPriceSource
import com.roziqrizal.rizqflow.domain.model.RoomKind
import com.roziqrizal.rizqflow.domain.model.TransactionKind
import com.roziqrizal.rizqflow.domain.model.TransactionOrigin
import com.roziqrizal.rizqflow.domain.model.WealthKind
import com.roziqrizal.rizqflow.domain.recurring.Frequency

/*
 * Skema Room versi 1, mengikuti docs/model-data.md. Aturan yang berlaku di semua tabel:
 * uang berupa Long satuan terkecil, tanggal berupa epochDay, waktu sistem berupa epochMillis,
 * dan pengenal berupa UUID teks. Saldo, jatah, terpakai, dan status haul tidak disimpan;
 * semuanya dihitung dari baris di sini.
 */

@Entity(tableName = "account")
data class AccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: AccountKind,
    val currency: String,
    @ColumnInfo(name = "opening_balance") val openingBalance: Long,
    val archived: Boolean,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "last_reconciled_on") val lastReconciledOn: Long?,
    /**
     * Versi 8 (Tahap 1 sync-desain.md): akun anggota virtual untuk pengeluaran pasangan di ruang bersama
     * (label + penulis); null untuk akun milik pengguna sendiri. Baris berpenanda ini wajib disaring dari
     * semua daftar dan hitungan akun nyata. Belum ada yang mengisinya sampai Tahap 2.
     */
    @ColumnInfo(name = "shared_from", defaultValue = "NULL") val sharedFrom: String? = null,
)

@Entity(tableName = "room")
data class RoomEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: RoomKind,
    @ColumnInfo(name = "icon_key") val iconKey: String,
    @ColumnInfo(name = "color_slot") val colorSlot: Int,
    /** Juga prioritas: pemutus seri pembulatan alokasi. */
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    val archived: Boolean,
    /** Hanya ruang Memberi: `zakat-haul-hijri` atau `percentage`. */
    @ColumnInfo(name = "giving_mode") val givingMode: String?,
)

@Entity(
    tableName = "allocation_rule",
    foreignKeys = [ForeignKey(RoomEntity::class, ["id"], ["room_id"], onDelete = ForeignKey.CASCADE)],
)
data class AllocationRuleEntity(
    @PrimaryKey @ColumnInfo(name = "room_id") val roomId: String,
    /** Basis point, 0 sampai 10.000. */
    @ColumnInfo(name = "share_bp") val shareBp: Int,
    /**
     * Batas atas rupiah untuk mode lanjutan (Pro, versi 2); null = tak terbatas. Independen dari
     * [shareBp] — keduanya bisa terisi sekaligus, tapi hanya salah satu yang dipakai menurut mode
     * aktif (`app_setting`, kunci `allocation_mode`).
     */
    @ColumnInfo(name = "cap_amount", defaultValue = "NULL") val capAmount: Long? = null,
)

@Entity(
    tableName = "category",
    foreignKeys = [ForeignKey(RoomEntity::class, ["id"], ["room_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("room_id")],
)
data class CategoryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "room_id") val roomId: String,
    val name: String,
    /** Bobot relatif untuk membagi jatah ruang ke pos; null = ikut jatah ruang. */
    val weight: Int?,
    @ColumnInfo(name = "is_system") val isSystem: Boolean,
    val archived: Boolean,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
)

/** Nama tabel `money_transaction` karena `TRANSACTION` adalah kata kunci SQL. */
@Entity(
    tableName = "money_transaction",
    foreignKeys = [
        ForeignKey(AccountEntity::class, ["id"], ["account_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(AccountEntity::class, ["id"], ["to_account_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(RoomEntity::class, ["id"], ["room_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(CategoryEntity::class, ["id"], ["category_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index("occurred_on"),
        Index("account_id", "occurred_on"),
        Index("to_account_id"),
        Index("room_id", "occurred_on"),
        Index("category_id"),
    ],
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    val kind: TransactionKind,
    /** Selalu positif; [kind] menentukan arah. */
    val amount: Long,
    val currency: String,
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "to_account_id") val toAccountId: String?,
    @ColumnInfo(name = "room_id") val roomId: String?,
    @ColumnInfo(name = "category_id") val categoryId: String?,
    @ColumnInfo(name = "income_source") val incomeSource: String?,
    @ColumnInfo(name = "occurred_on") val occurredOn: Long,
    val note: String?,
    val origin: TransactionOrigin,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** Null = belum terkirim ke sync (Tahap 9). Setiap tulis ulang lewat [toEntity] mengembalikannya ke null. */
    @ColumnInfo(name = "synced_at") val syncedAt: Long? = null,
)

/** Potret alokasi satu pemasukan; yang belum dialirkan = nominal pemasukan dikurangi jumlah entrinya. */
@Entity(
    tableName = "allocation_entry",
    foreignKeys = [
        ForeignKey(TransactionEntity::class, ["id"], ["income_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(RoomEntity::class, ["id"], ["room_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("income_id"), Index("room_id")],
)
data class AllocationEntryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "income_id") val incomeId: String,
    @ColumnInfo(name = "room_id") val roomId: String,
    @ColumnInfo(name = "share_bp") val shareBp: Int,
    val amount: Long,
    @ColumnInfo(name = "allocated_at") val allocatedAt: Long,
)

@Entity(
    tableName = "quick_favorite",
    foreignKeys = [
        ForeignKey(RoomEntity::class, ["id"], ["room_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(CategoryEntity::class, ["id"], ["category_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(AccountEntity::class, ["id"], ["account_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("room_id"), Index("category_id"), Index("account_id")],
)
data class QuickFavoriteEntity(
    @PrimaryKey val id: String,
    val name: String,
    val amount: Long,
    @ColumnInfo(name = "room_id") val roomId: String,
    @ColumnInfo(name = "category_id") val categoryId: String,
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "use_count") val useCount: Int,
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long?,
)

/** Hari yang ditandai "Tidak ada" (sudah dicek tanpa pengeluaran). */
@Entity(tableName = "day_check")
data class DayCheckEntity(@PrimaryKey val day: Long)

@Entity(tableName = "app_setting")
data class AppSettingEntity(@PrimaryKey val key: String, val value: String)

@Entity(tableName = "zakat_profile")
data class ZakatProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "haul_break_policy") val haulBreakPolicy: String,
    val archived: Boolean,
)

@Entity(
    tableName = "wealth_item",
    foreignKeys = [ForeignKey(ZakatProfileEntity::class, ["id"], ["profile_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("profile_id")],
)
data class WealthItemEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    val kind: WealthKind,
    val label: String,
    val value: Long,
    @ColumnInfo(name = "gold_milligrams") val goldMilligrams: Long?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** Riwayat harga emas per gram, supaya nisab pada tanggal lama bisa direkonstruksi. */
@Entity(tableName = "gold_price")
data class GoldPriceEntity(
    @PrimaryKey val day: Long,
    @ColumnInfo(name = "per_gram") val perGram: Long,
    val source: GoldPriceSource,
)

/** Setara `HaulEvent.WealthChecked` di domain. */
@Entity(
    tableName = "wealth_check",
    foreignKeys = [ForeignKey(ZakatProfileEntity::class, ["id"], ["profile_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("profile_id", "day")],
)
data class WealthCheckEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    val day: Long,
    @ColumnInfo(name = "net_wealth") val netWealth: Long,
    val nisab: Long,
)

/** Setara `HaulEvent.ZakatPaid` di domain. */
@Entity(
    tableName = "zakat_payment",
    foreignKeys = [
        ForeignKey(ZakatProfileEntity::class, ["id"], ["profile_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TransactionEntity::class, ["id"], ["transaction_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("profile_id"), Index("transaction_id")],
)
data class ZakatPaymentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    val day: Long,
    @ColumnInfo(name = "transaction_id") val transactionId: String,
)

/**
 * Peran Trader (versi 3, Pro): modal dan batas risiko per trade untuk satu ruang. Ada barisnya
 * berarti modul terpasang; ruang punya paling banyak satu peran (dijaga `RoleService`).
 */
@Entity(
    tableName = "trader_profile",
    foreignKeys = [ForeignKey(RoomEntity::class, ["id"], ["room_id"], onDelete = ForeignKey.CASCADE)],
)
data class TraderProfileEntity(
    @PrimaryKey @ColumnInfo(name = "room_id") val roomId: String,
    val capital: Long,
    /** Basis point modal yang boleh hilang per trade. */
    @ColumnInfo(name = "risk_bp") val riskBp: Int,
)

/** Peran Investor (versi 3, Pro): jadwal DCA bulanan untuk satu ruang. */
@Entity(
    tableName = "dca_plan",
    foreignKeys = [
        ForeignKey(RoomEntity::class, ["id"], ["room_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(AccountEntity::class, ["id"], ["account_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(CategoryEntity::class, ["id"], ["category_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("account_id"), Index("category_id")],
)
data class DcaPlanEntity(
    @PrimaryKey @ColumnInfo(name = "room_id") val roomId: String,
    val amount: Long,
    @ColumnInfo(name = "day_of_month") val dayOfMonth: Int,
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "category_id") val categoryId: String,
)

/**
 * Aturan transaksi berulang (versi 4, Tahap 10). Uang berupa Long satuan terkecil, tanggal berupa
 * epochDay. [nextDue] adalah kemunculan berikutnya yang belum dicatat; [startDate] adalah jangkar
 * penghitungan jadwal.
 */
@Entity(
    tableName = "recurring_rule",
    foreignKeys = [
        ForeignKey(AccountEntity::class, ["id"], ["account_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(AccountEntity::class, ["id"], ["to_account_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(RoomEntity::class, ["id"], ["room_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(CategoryEntity::class, ["id"], ["category_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("account_id"), Index("to_account_id"), Index("room_id"), Index("category_id"), Index("next_due")],
)
data class RecurringRuleEntity(
    @PrimaryKey val id: String,
    val kind: TransactionKind,
    val amount: Long,
    val currency: String,
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "to_account_id") val toAccountId: String?,
    @ColumnInfo(name = "room_id") val roomId: String?,
    @ColumnInfo(name = "category_id") val categoryId: String?,
    @ColumnInfo(name = "income_source") val incomeSource: String?,
    val note: String?,
    val frequency: Frequency,
    @ColumnInfo(name = "start_date") val startDate: Long,
    @ColumnInfo(name = "next_due") val nextDue: Long,
    @ColumnInfo(name = "end_date") val endDate: Long?,
    val active: Boolean,
)

/**
 * Tagihan dan cicilan (versi 5, Tahap 10). Uang berupa Long satuan terkecil, tanggal berupa epochDay.
 * [frequency] null berarti sekali bayar; [totalInstallments] null berarti tanpa batas cicilan.
 * [nextDue] adalah jatuh tempo yang belum dibayar; [startDate] adalah jangkar penghitungan jadwal.
 */
@Entity(
    tableName = "bill",
    foreignKeys = [
        ForeignKey(AccountEntity::class, ["id"], ["account_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(RoomEntity::class, ["id"], ["room_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(CategoryEntity::class, ["id"], ["category_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("account_id"), Index("room_id"), Index("category_id"), Index("next_due")],
)
data class BillEntity(
    @PrimaryKey val id: String,
    val name: String,
    val amount: Long,
    val currency: String,
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "room_id") val roomId: String,
    @ColumnInfo(name = "category_id") val categoryId: String,
    val note: String?,
    val frequency: Frequency?,
    @ColumnInfo(name = "start_date") val startDate: Long,
    @ColumnInfo(name = "next_due") val nextDue: Long,
    @ColumnInfo(name = "total_installments") val totalInstallments: Int?,
    @ColumnInfo(name = "paid_count") val paidCount: Int,
    val active: Boolean,
)

/**
 * Utang atau piutang (versi 6, Tahap 10). Uang berupa Long satuan terkecil, tanggal berupa epochDay.
 * [accountId] dan [initialTransactionId] kosong untuk pinjaman lama yang uangnya bergerak sebelum dicatat;
 * transaksi awalnya `ON DELETE SET NULL` supaya menghapusnya di tempat lain tidak menghalangi apa pun.
 */
@Entity(
    tableName = "debt",
    foreignKeys = [
        ForeignKey(AccountEntity::class, ["id"], ["account_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(TransactionEntity::class, ["id"], ["initial_transaction_id"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("account_id"), Index("initial_transaction_id"), Index("due_date")],
)
data class DebtEntity(
    @PrimaryKey val id: String,
    val direction: DebtDirection,
    val party: String,
    val principal: Long,
    val currency: String,
    @ColumnInfo(name = "account_id") val accountId: String?,
    @ColumnInfo(name = "initial_transaction_id") val initialTransactionId: String?,
    @ColumnInfo(name = "start_date") val startDate: Long,
    @ColumnInfo(name = "due_date") val dueDate: Long?,
    val note: String?,
    val collectible: Boolean,
)

/** Satu pelunasan; ikut terhapus bersama utangnya. [transactionId] kosong bila tidak dicatat ke saldo akun. */
@Entity(
    tableName = "debt_payment",
    foreignKeys = [
        ForeignKey(DebtEntity::class, ["id"], ["debt_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TransactionEntity::class, ["id"], ["transaction_id"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("debt_id"), Index("transaction_id")],
)
data class DebtPaymentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "debt_id") val debtId: String,
    val amount: Long,
    @ColumnInfo(name = "paid_on") val paidOn: Long,
    @ColumnInfo(name = "transaction_id") val transactionId: String?,
)

/*
 * Penyimpanan sinkron (versi 8, Tahap 1 sync-desain.md bagian E, USULAN Claude). Semua tabel di sini
 * adalah keadaan sinkron lokal, bukan data keuangan; tidak ikut disinkronkan.
 */

/**
 * Satu versi per rekaman per cakupan: stempel (changed_at), tombstone (deleted), dan penanda kotor.
 * [deviceId] menyimpan perangkat penulis versi ini agar aturan urutan total (changed_at, tombstone,
 * deviceId) bisa memutus seri; null = perangkat ini sendiri. (Kolom ini tambahan dari daftar di
 * sync-desain.md bagian E, yang belum menyebutnya padahal aturan bagian D membutuhkannya.)
 */
@Entity(
    tableName = "sync_record",
    primaryKeys = ["scope", "record_type", "record_id"],
    indices = [Index("scope", "dirty")],
)
data class SyncRecordEntity(
    val scope: String,
    @ColumnInfo(name = "record_type") val recordType: String,
    @ColumnInfo(name = "record_id") val recordId: String,
    @ColumnInfo(name = "changed_at") val changedAt: Long,
    val deleted: Boolean,
    val dirty: Boolean,
    @ColumnInfo(name = "pushed_changed_at") val pushedChangedAt: Long?,
    val author: String?,
    @ColumnInfo(name = "device_id") val deviceId: String?,
)

/** Posisi pull terakhir per cakupan (menggantikan SharedPreferences, ikut berkas DB akun yang sama). */
@Entity(tableName = "sync_cursor")
data class SyncCursorEntity(
    @PrimaryKey val scope: String,
    @ColumnInfo(name = "after_id") val afterId: Long,
)

/** Masalah sinkron yang dicatat; [kind] disimpan sebagai teks (nama `SyncIssueKind`). */
@Entity(tableName = "sync_issue")
data class SyncIssueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scope: String,
    @ColumnInfo(name = "record_type") val recordType: String,
    @ColumnInfo(name = "record_id") val recordId: String,
    @ColumnInfo(name = "server_id") val serverId: Long?,
    val kind: String,
    val detail: String?,
    @ColumnInfo(name = "raw_row") val rawRow: String?,
    val attempts: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** Keanggotaan ruang bersama di perangkat ini (tanpa rahasia). Ikut terhapus bila ruangnya dihapus. */
@Entity(
    tableName = "shared_room",
    foreignKeys = [ForeignKey(RoomEntity::class, ["id"], ["room_id"], onDelete = ForeignKey.CASCADE)],
)
data class SharedRoomEntity(
    @PrimaryKey @ColumnInfo(name = "room_id") val roomId: String,
    val role: String,
    val status: String,
    @ColumnInfo(name = "joined_at") val joinedAt: Long,
)
