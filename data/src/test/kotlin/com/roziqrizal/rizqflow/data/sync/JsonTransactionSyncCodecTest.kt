package com.roziqrizal.rizqflow.data.sync

import com.roziqrizal.rizqflow.domain.ledger.MoneyTransaction
import com.roziqrizal.rizqflow.domain.model.AccountId
import com.roziqrizal.rizqflow.domain.model.CategoryId
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.model.TransactionId
import com.roziqrizal.rizqflow.domain.model.TransactionKind
import com.roziqrizal.rizqflow.domain.money.Money
import com.roziqrizal.rizqflow.domain.sync.TransactionSyncPayloadCorrupt
import java.time.LocalDate
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class JsonTransactionSyncCodecTest {
    private val codec = JsonTransactionSyncCodec()

    private fun expense(note: String? = "Belanja mingguan") = MoneyTransaction(
        id = TransactionId("tx-1"),
        kind = TransactionKind.EXPENSE,
        amount = Money.rupiah(75_000),
        accountId = AccountId("acc-1"),
        roomId = RoomId("room-1"),
        categoryId = CategoryId("cat-1"),
        occurredOn = LocalDate.of(2026, 10, 24),
        note = note,
        createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L,
    )

    @Test
    fun `bolak-balik encode decode mengembalikan data yang sama`() {
        val encoded = codec.encode(expense(), accountName = "Dompet", categoryName = "Belanja")
        val decoded = codec.decode(encoded)

        assertEquals(TransactionId("tx-1"), decoded.id)
        assertEquals(Money.rupiah(75_000), decoded.amount)
        assertEquals("Dompet", decoded.accountName)
        assertEquals("Belanja", decoded.categoryName)
        assertEquals(LocalDate.of(2026, 10, 24), decoded.occurredOn)
        assertEquals("Belanja mingguan", decoded.note)
    }

    @Test
    fun `kategori dan catatan null tetap bolak-balik null`() {
        val encoded = codec.encode(expense(note = null), accountName = "Dompet", categoryName = null)
        val decoded = codec.decode(encoded)

        assertNull(decoded.categoryName)
        assertNull(decoded.note)
    }

    @Test
    fun `payload rusak melempar TransactionSyncPayloadCorrupt`() {
        assertFailsWith<TransactionSyncPayloadCorrupt> {
            codec.decode("bukan json valid".toByteArray())
        }
    }
}
