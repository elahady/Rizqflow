package com.roziqrizal.rizqflow.domain.ledger

import com.roziqrizal.rizqflow.domain.model.AccountId
import com.roziqrizal.rizqflow.domain.model.CategoryId
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.model.TransactionKind
import com.roziqrizal.rizqflow.domain.money.Money
import java.time.LocalDate
import kotlin.math.abs

/**
 * Deteksi kemungkinan transaksi ganda ("Mungkin sudah dicatat") saat pengguna menyimpan transaksi
 * baru secara manual, misalnya karena mencatat dua kali tanpa sadar.
 *
 * Hanya membaca dan tidak pernah menghalangi simpan: keputusan tetap di tangan pengguna. Sebuah
 * transaksi dianggap kemungkinan ganda bila jenis, nominal (bilangan bulat satuan terkecil), akun,
 * ruang, dan kategori-nya sama persis, serta dicatat dalam [windowMillis] dari sekarang. Waktu
 * pencatatan ([MoneyTransaction.createdAtMillis]) yang dipakai, bukan tanggal kejadian, karena yang
 * ingin dideteksi adalah pencatatan yang berulang.
 *
 * Tidak tahu apa pun soal Android. Dipanggil dari lapisan UI tepat sebelum memanggil fungsi simpan
 * yang sudah ada; simpan itu sendiri tidak diubah.
 */
class DuplicateTransactionDetector(
    private val transactions: TransactionRepository,
    private val nowMillis: () -> Long,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
) {

    /** Transaksi yang hendak disimpan, sebelum punya id. Dibuat dari [CatatDraft] lewat [toDuplicateProbe]. */
    data class Probe(
        val kind: TransactionKind,
        val amount: Money,
        val accountId: AccountId,
        val toAccountId: AccountId? = null,
        val roomId: RoomId? = null,
        val categoryId: CategoryId? = null,
        val occurredOn: LocalDate,
    )

    /**
     * Transaksi lain yang kemungkinan sama dengan [probe], atau null. Kandidat diambil satu hari di
     * kiri dan kanan tanggal kejadian; pencocokan waktu dilakukan oleh [matches].
     */
    suspend fun findDuplicate(probe: Probe): MoneyTransaction? {
        val now = nowMillis()
        val candidates = transactions.between(probe.occurredOn.minusDays(1), probe.occurredOn.plusDays(1))
        return candidates.firstOrNull { matches(probe, it, now, windowMillis) }
    }

    companion object {
        /**
         * Jendela pencatatan ganda. Dipilih 30 menit: cukup untuk menangkap catatan yang diulang
         * dalam satu sesi, tanpa menandai pembelian serupa yang memang dicatat terpisah di hari yang sama.
         * Nilai ini masih perlu dikonfirmasi pemilik.
         */
        const val DEFAULT_WINDOW_MINUTES: Long = 30L
        const val DEFAULT_WINDOW_MILLIS: Long = DEFAULT_WINDOW_MINUTES * 60_000L

        /**
         * Pencocokan murni, dipisah dari repositori supaya mudah diuji. Batas jendela ikut dihitung
         * (selisih tepat [windowMillis] masih terdeteksi).
         */
        fun matches(
            probe: Probe,
            existing: MoneyTransaction,
            nowMillis: Long,
            windowMillis: Long = DEFAULT_WINDOW_MILLIS,
        ): Boolean {
            if (existing.kind != probe.kind) return false
            if (existing.amount != probe.amount) return false
            if (existing.accountId != probe.accountId) return false
            if (existing.toAccountId != probe.toAccountId) return false
            if (existing.roomId != probe.roomId) return false
            if (existing.categoryId != probe.categoryId) return false
            return abs(nowMillis - existing.createdAtMillis) <= windowMillis
        }
    }
}

/**
 * Isian Catat yang akan disimpan sebagai [DuplicateTransactionDetector.Probe]. Pemanggil harus sudah
 * memastikan [CatatDraft.issue] bernilai null, seperti saat tombol simpan aktif; akun wajib ada.
 */
fun CatatDraft.toDuplicateProbe(): DuplicateTransactionDetector.Probe {
    val kind = when (mode) {
        CatatMode.INCOME -> TransactionKind.INCOME
        CatatMode.EXPENSE -> TransactionKind.EXPENSE
        CatatMode.TRANSFER -> TransactionKind.TRANSFER
    }
    return when (mode) {
        CatatMode.INCOME -> DuplicateTransactionDetector.Probe(kind, amount, requireNotNull(accountId), occurredOn = date)
        CatatMode.EXPENSE -> DuplicateTransactionDetector.Probe(
            kind, amount, requireNotNull(accountId), roomId = roomId, categoryId = categoryId, occurredOn = date,
        )

        CatatMode.TRANSFER -> DuplicateTransactionDetector.Probe(
            kind, amount, requireNotNull(accountId), toAccountId = toAccountId, occurredOn = date,
        )
    }
}
