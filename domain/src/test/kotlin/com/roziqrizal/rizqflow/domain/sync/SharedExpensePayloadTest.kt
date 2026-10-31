package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.ledger.MoneyTransaction
import com.roziqrizal.rizqflow.domain.model.AccountId
import com.roziqrizal.rizqflow.domain.model.CategoryId
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.model.TransactionId
import com.roziqrizal.rizqflow.domain.model.TransactionKind
import com.roziqrizal.rizqflow.domain.model.TransactionOrigin
import com.roziqrizal.rizqflow.domain.money.Currency
import com.roziqrizal.rizqflow.domain.money.Money
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** Golden test isi amplop pengeluaran ruang bersama (sync-desain.md bagian C, `shared_expense.v1.json`). */
class SharedExpensePayloadTest {
    private val golden: JsonObject = checkNotNull(javaClass.getResourceAsStream("/sync/golden/shared_expense.v1.json")) { "Berkas golden tidak ada" }
        .use { Json.parseToJsonElement(it.readBytes().toString(Charsets.UTF_8)).jsonObject }

    private fun g(key: String) = golden.getValue(key).jsonPrimitive.content

    private val input = golden.getValue("input").jsonObject

    private fun transaction(categoryAndNote: Boolean = true) = MoneyTransaction(
        id = TransactionId(g("id")),
        kind = TransactionKind.EXPENSE,
        amount = Money(input.getValue("amountMinor").jsonPrimitive.long, Currency.IDR),
        accountId = AccountId("acc-lokal"),
        roomId = RoomId("room-keluarga"),
        categoryId = CategoryId("cat-lokal"),
        occurredOn = LocalDate.ofEpochDay(input.getValue("occurredOnEpochDay").jsonPrimitive.long),
        note = if (categoryAndNote) input.getValue("note").jsonPrimitive.content else null,
        origin = TransactionOrigin.MANUAL,
        createdAtMillis = 1L,
        updatedAtMillis = 1L,
    )

    private fun envelopeJson(data: JsonObject) =
        String(SyncEnvelope.encodeJson(g("type"), g("id"), golden.getValue("version").jsonPrimitive.int, data), Charsets.UTF_8)

    @Test
    fun `isi amplop sama persis dengan vektor emas`() {
        val data = SharedExpensePayload.encode(transaction(), input.getValue("accountName").jsonPrimitive.content, input.getValue("categoryName").jsonPrimitive.content)
        assertEquals(g("plaintextJson"), envelopeJson(data))
    }

    @Test
    fun `kategori dan catatan null dihilangkan dari JSON`() {
        val data = SharedExpensePayload.encode(transaction(categoryAndNote = false), "Dompet", null)
        assertEquals(g("plaintextJsonWithoutOptionals"), envelopeJson(data))
    }

    @Test
    fun `tombstone memakai d kosong`() {
        assertEquals(g("plaintextJsonTombstone"), envelopeJson(JsonObject(emptyMap())))
    }

    @Test
    fun `wireId dan AAD cocok dengan vektor yang dihitung terpisah`() {
        assertEquals(g("wireId"), SyncEnvelope.wireId(SharedRecordType.SHARED_EXPENSE.wire, g("id")))
        val scope = SyncScope.parse(g("scope"))!!
        val changedAt = golden.getValue("changedAtMs").jsonPrimitive.long
        assertEquals(g("aadLive"), SyncEnvelope.aadString(scope.wire, g("wireId"), changedAt, false, g("deviceId")))
        assertEquals(g("aadTombstone"), SyncEnvelope.aadString(scope.wire, g("wireId"), changedAt, true, g("deviceId")))
    }

    @Test
    fun `id kabel versi ruang bersama berbeda dari id kabel versi pribadi transaksi yang sama`() {
        val shared = SyncEnvelope.wireId(SharedRecordType.SHARED_EXPENSE.wire, "tx-1")
        assertEquals(g("sharedWireIdOfTx1"), shared)
        assertEquals(g("personalWireIdOfSameTransaction"), SyncEnvelope.wireId(SyncRecordType.TRANSACTION.wire, "tx-1"))
        assertNotEquals(SyncEnvelope.wireId(SyncRecordType.TRANSACTION.wire, "tx-1"), shared)
    }

    @Test
    fun `decode membaca kembali vektor emas`() {
        val record = SyncEnvelope.decodeJson(g("plaintextJson").toByteArray())
        val decoded = SharedExpensePayload.decode(record.id, record.data)
        assertEquals(TransactionId(g("id")), decoded.id)
        assertEquals(Money(50_000, Currency.IDR), decoded.amount)
        assertEquals("Dompet", decoded.accountName)
        assertEquals("Belanja", decoded.categoryName)
        assertEquals(LocalDate.ofEpochDay(20_758), decoded.occurredOn)
        assertEquals("Sayur pasar", decoded.note)

        val minimal = SyncEnvelope.decodeJson(g("plaintextJsonWithoutOptionals").toByteArray())
        val decodedMinimal = SharedExpensePayload.decode(minimal.id, minimal.data)
        assertNull(decodedMinimal.categoryName)
        assertNull(decodedMinimal.note)
    }

    @Test
    fun `bidang tak dikenal diabaikan`() {
        val record = SyncEnvelope.decodeJson(
            """{"v":1,"type":"shared_expense","id":"t","d":{"amount_minor":1,"currency":"IDR","account_name":"A","occurred_on":1,"baru":true}}""".toByteArray(),
        )
        assertEquals(Money(1), SharedExpensePayload.decode(record.id, record.data).amount)
    }

    @Test
    fun `bidang wajib hilang, salah jenis, atau mata uang tak dikenal ditolak`() {
        val broken = listOf(
            """{"currency":"IDR","account_name":"A","occurred_on":1}""",
            """{"amount_minor":"1","currency":"IDR","account_name":"A","occurred_on":1}""",
            """{"amount_minor":1,"currency":"XYZ","account_name":"A","occurred_on":1}""",
            """{"amount_minor":1,"currency":"IDR","occurred_on":1}""",
            """{"amount_minor":1,"currency":"IDR","account_name":"A"}""",
            """{"amount_minor":1.5,"currency":"IDR","account_name":"A","occurred_on":1}""",
        )
        for (json in broken) {
            val data = Json.parseToJsonElement(json).jsonObject
            assertFailsWith<TransactionSyncPayloadCorrupt>(json) { SharedExpensePayload.decode("t", data) }
        }
    }
}
