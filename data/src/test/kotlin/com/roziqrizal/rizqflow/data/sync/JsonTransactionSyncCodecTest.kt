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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    /**
     * Aturan akun lintas ruang (Tahap 13): anggota ruang bersama hanya menerima NAMA akun sumber. Payload tidak
     * boleh memuat id akun lokal pengirim, saldo, atau bidang lain di luar daftar ini.
     */
    @Test
    fun `payload ke anggota ruang hanya memuat nama akun, bukan id akun lokal atau saldo`() {
        val transaction = expense().copy(accountId = AccountId("acc-lokal-rahasia-42"))

        val json = String(codec.encode(transaction, accountName = "Dompet", categoryName = "Belanja"))
        val fields = Json.parseToJsonElement(json).jsonObject

        assertEquals(
            setOf("id", "amount_minor", "currency", "account_name", "category_name", "occurred_on", "note"),
            fields.keys,
        )
        assertEquals("Dompet", fields.getValue("account_name").jsonPrimitive.content)
        assertFalse(json.contains("acc-lokal-rahasia-42"), "id akun lokal pengirim tidak boleh ikut terkirim")
        assertFalse(json.contains("balance", ignoreCase = true) || json.contains("saldo", ignoreCase = true), "saldo akun tidak boleh ikut terkirim")
    }

    @Test
    fun `payload rusak melempar TransactionSyncPayloadCorrupt`() {
        assertFailsWith<TransactionSyncPayloadCorrupt> {
            codec.decode("bukan json valid".toByteArray())
        }
    }
}
