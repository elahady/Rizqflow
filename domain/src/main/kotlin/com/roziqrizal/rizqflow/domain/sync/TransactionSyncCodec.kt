package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.ledger.MoneyTransaction
import com.roziqrizal.rizqflow.domain.model.TransactionId
import com.roziqrizal.rizqflow.domain.money.Money
import java.time.LocalDate

/**
 * Mengubah [MoneyTransaction] jadi bytes untuk dienkripsi lewat [SyncAead] dan sebaliknya (Tahap 9).
 * Kontrak murni tanpa kotlinx.serialization — `:domain` tetap bebas dependensi JSON; implementasi
 * sungguhan ada di `:data`. Akun dan kategori dikirim sebagai **nama**, bukan id — id lokal tidak
 * konsisten antar perangkat anggota, sama prinsipnya dengan impor CSV: sisi penerima mencocokkan
 * by nama, dan baris yang akunnya tak dikenal dilewati dengan alasan (bukan ditebak).
 */
interface TransactionSyncCodec {
    /** Hanya transaksi EXPENSE yang disync di putaran ini — [transaction.roomId] selalu ada. */
    fun encode(transaction: MoneyTransaction, accountName: String, categoryName: String?): ByteArray

    /** @throws TransactionSyncPayloadCorrupt bila bytes bukan hasil [encode] atau rusak. */
    fun decode(bytes: ByteArray): DecodedSyncTransaction
}

class TransactionSyncPayloadCorrupt : Exception("Data sync transaksi rusak atau tidak valid")

data class DecodedSyncTransaction(
    val id: TransactionId,
    val amount: Money,
    val accountName: String,
    val categoryName: String?,
    val occurredOn: LocalDate,
    val note: String?,
)
