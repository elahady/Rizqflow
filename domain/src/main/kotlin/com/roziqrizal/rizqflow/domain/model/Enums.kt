package com.roziqrizal.rizqflow.domain.model

/** Jenis akun: tempat uang berada. */
enum class AccountKind { CASH, BANK, EWALLET }

/**
 * Tipe ruang; menentukan arti "terpenuhi" (docs/konsep.md). Menunaikan dan Menumbuhkan
 * terpenuhi saat penggunaan mencapai jatah; Mencukupi masuk Perlu perhatian di 85% jatah.
 */
enum class RoomKind { MENUNAIKAN, MENUMBUHKAN, MENCUKUPI }

/**
 * Jenis transaksi. Jumlah selalu positif; jenis menentukan arah uang. [LOAN_OUT] dan [LOAN_IN]
 * (utang-piutang, Tahap 10) hanya menggeser saldo satu akun: keluar dari akun atau masuk ke akun
 * tanpa menjadi pengeluaran (tidak memakan jatah ruang) atau pemasukan (tidak dialirkan ke ruang).
 */
enum class TransactionKind { INCOME, EXPENSE, TRANSFER, LOAN_OUT, LOAN_IN }

/**
 * Dari mana transaksi berasal. [SHARED] (Tahap 1 sync-desain.md, USULAN) menandai pengeluaran anggota
 * lain di ruang bersama yang diterima lewat sinkron; baru dipakai Tahap 2, disimpan sebagai teks
 * (aditif, tanpa migrasi kolom). Baris `SHARED` bukan milik cakupan pribadi dan tidak dicatat sebagai
 * perubahan lokal.
 */
enum class TransactionOrigin { MANUAL, QUICK, REPLY, CORRECTION, DRAFT, IMPORT, RECURRING, BILL, LOAN, SHARED }

/** Jenis baris di profil harta zakat; [DEDUCTION] adalah pengurang (utang jangka pendek). */
enum class WealthKind { GOLD, CASH_SAVINGS, INVESTMENT, RECEIVABLE, OTHER, DEDUCTION }

/** Asal harga emas. */
enum class GoldPriceSource { MANUAL, AUTO }
