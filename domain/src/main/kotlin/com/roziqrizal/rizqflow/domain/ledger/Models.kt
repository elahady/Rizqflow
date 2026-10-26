package com.roziqrizal.rizqflow.domain.ledger

import com.roziqrizal.rizqflow.domain.allocation.BasisPoints
import com.roziqrizal.rizqflow.domain.model.AccountId
import com.roziqrizal.rizqflow.domain.model.AccountKind
import com.roziqrizal.rizqflow.domain.model.CategoryId
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.model.RoomKind
import com.roziqrizal.rizqflow.domain.model.TransactionId
import com.roziqrizal.rizqflow.domain.model.TransactionKind
import com.roziqrizal.rizqflow.domain.model.TransactionOrigin
import com.roziqrizal.rizqflow.domain.money.Currency
import com.roziqrizal.rizqflow.domain.money.Money
import java.time.LocalDate

/*
 * Model buku kas untuk lapisan aplikasi. Bentuknya mengikuti skema di docs/model-data.md, tetapi
 * bebas dari Room: :domain tidak tahu database. Saldo, jatah, terpakai, dan status tidak disimpan
 * di sini; semuanya dihitung dari transaksi.
 */

/** Tempat uang berada: dompet, rekening, atau dompet digital. */
data class Account(
    val id: AccountId,
    val name: String,
    val kind: AccountKind,
    /** Saldo yang sudah ada saat akun dibuat. Bukan rezeki: tidak dialirkan ke ruang. */
    val openingBalance: Money,
    val archived: Boolean = false,
    val sortOrder: Int = 0,
    /** Terakhir kali saldo dicocokkan lewat Koreksi saldo (S25); null bila belum pernah. */
    val lastReconciledOn: LocalDate? = null,
    /**
     * Terisi hanya untuk **akun anggota virtual** (Tahap 2 sync-desain.md bagian E): akun pengganti
     * yang mewakili nama akun sumber transaksi anggota lain di ruang bersama ini, bukan akun milik
     * pengguna sendiri. Tidak pernah punya saldo nyata dan wajib disaring dari daftar akun aktif,
     * pemilih akun, saldo, dan ekspor pribadi — lihat [AccountRepository.activeAccounts].
     */
    val sharedFrom: RoomId? = null,
) {
    init {
        require(name.isNotBlank() && name.length <= NAME_MAX) { "Nama akun 1 sampai $NAME_MAX karakter" }
        require(!openingBalance.isNegative) { "Saldo awal tidak boleh negatif" }
    }

    val currency: Currency get() = openingBalance.currency

    companion object {
        const val NAME_MAX = 30
    }
}

/** Ruang: wadah hak tempat rezeki dialirkan (Memberi, Diri, Keluarga, atau ruang peran lain). */
data class Room(
    val id: RoomId,
    val name: String,
    val kind: RoomKind,
    val iconKey: String,
    val colorSlot: Int,
    /** Juga prioritas: ruang lebih awal didahulukan saat sisa pembulatan dibagikan. */
    val sortOrder: Int,
    val archived: Boolean = false,
    /** Hanya ruang Memberi: `zakat-haul-hijri` atau `percentage`. */
    val givingMode: String? = null,
) {
    init {
        require(name.isNotBlank() && name.length <= NAME_MAX) { "Nama ruang 1 sampai $NAME_MAX karakter" }
        require(colorSlot >= 1) { "Slot warna dimulai dari 1" }
    }

    companion object {
        const val NAME_MAX = 24
    }
}

/** Pos pengeluaran di dalam sebuah ruang. [isSystem]: `Zakat mal` dan `Tak terlacak`, tidak bisa dihapus. */
data class Category(
    val id: CategoryId,
    val roomId: RoomId,
    val name: String,
    val weight: Int? = null,
    val isSystem: Boolean = false,
    val archived: Boolean = false,
    val sortOrder: Int = 0,
) {
    init {
        require(name.isNotBlank() && name.length <= NAME_MAX) { "Nama kategori 1 sampai $NAME_MAX karakter" }
        require(weight == null || weight > 0) { "Bobot kategori harus positif" }
    }

    companion object {
        const val NAME_MAX = 30
    }
}

/**
 * Satu transaksi. Jumlah selalu positif; [kind] menentukan arah uang.
 *
 * - Pemasukan: satu akun, tanpa ruang dan kategori (dialirkan lewat [AllocationEntry]).
 * - Pengeluaran: satu akun, satu ruang, satu kategori milik ruang itu.
 * - Transfer: dua akun berbeda, tanpa ruang; bukan pemasukan dan bukan pengeluaran.
 * - Pinjaman keluar atau masuk (utang-piutang): satu akun, tanpa ruang dan kategori; hanya menggeser saldo.
 */
data class MoneyTransaction(
    val id: TransactionId,
    val kind: TransactionKind,
    val amount: Money,
    val accountId: AccountId,
    val toAccountId: AccountId? = null,
    val roomId: RoomId? = null,
    val categoryId: CategoryId? = null,
    val incomeSource: String? = null,
    val occurredOn: LocalDate,
    val note: String? = null,
    val origin: TransactionOrigin = TransactionOrigin.MANUAL,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
) {
    init {
        require(amount.isPositive) { "Nominal transaksi harus lebih dari nol" }
        when (kind) {
            TransactionKind.INCOME -> require(toAccountId == null && roomId == null && categoryId == null) {
                "Pemasukan tidak punya akun tujuan, ruang, atau kategori"
            }

            TransactionKind.EXPENSE -> {
                require(toAccountId == null && incomeSource == null) { "Pengeluaran tidak punya akun tujuan atau sumber" }
                require(roomId != null && categoryId != null) { "Pengeluaran wajib punya ruang dan kategori" }
            }

            TransactionKind.TRANSFER -> {
                require(toAccountId != null && toAccountId != accountId) { "Transfer butuh dua akun berbeda" }
                require(roomId == null && categoryId == null && incomeSource == null) { "Transfer tidak punya ruang, kategori, atau sumber" }
            }

            TransactionKind.LOAN_OUT, TransactionKind.LOAN_IN -> require(toAccountId == null && roomId == null && categoryId == null && incomeSource == null) {
                "Pinjaman tidak punya akun tujuan, ruang, kategori, atau sumber"
            }
        }
    }
}

/** Potret alokasi satu pemasukan ke satu ruang, dengan persentase saat pemasukan dicatat. */
data class AllocationEntry(
    val id: String,
    val incomeId: TransactionId,
    val roomId: RoomId,
    val share: BasisPoints,
    val amount: Money,
    val allocatedAtMillis: Long,
)
