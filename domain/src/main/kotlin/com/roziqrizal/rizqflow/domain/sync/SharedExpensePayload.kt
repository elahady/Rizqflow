package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.ledger.MoneyTransaction
import com.roziqrizal.rizqflow.domain.model.TransactionId
import com.roziqrizal.rizqflow.domain.money.Currency
import com.roziqrizal.rizqflow.domain.money.Money
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.LocalDate

/**
 * Tipe rekaman di cakupan RUANG BERSAMA (`r:<roomId>`, sync-desain.md bagian A). Terpisah dari [SyncRecordType]
 * karena tipe di sini bukan tabel lokal yang dicatat recorder cakupan pribadi, melainkan proyeksi turunan.
 *
 * Nama tipe ikut menentukan [SyncEnvelope.wireId], jadi `shared_expense` (bukan `transaction`) membuat id kabel
 * versi ruang bersama berbeda dari id kabel versi pribadi transaksi yang sama: server tidak bisa menautkan
 * keduanya, dan kunci idempoten push (`device_id`, `record_id`, `changed_at_ms`, `deleted`) tidak pernah
 * menganggap baris ruang bersama sebagai duplikat baris pribadi.
 */
enum class SharedRecordType(val wire: String) {
    SHARED_EXPENSE("shared_expense"),
    ;

    companion object {
        fun fromWire(wire: String): SharedRecordType? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * Isi `d` amplop [SyncEnvelope] untuk pengeluaran di ruang bersama (tipe [SharedRecordType.SHARED_EXPENSE],
 * versi [VERSION]). Identitas R2 (sync-desain.md bagian B): akun dan kategori dibawa sebagai NAMA, bukan id lokal;
 * id transaksi ada di bidang `id` amplop, bukan di sini.
 *
 * Bentuk JSON (digembok `src/test/resources/sync/golden/shared_expense.v1.json`):
 * `{"amount_minor":50000,"currency":"IDR","account_name":"Dompet","category_name":"Belanja","occurred_on":20750,"note":"..."}`;
 * `category_name` dan `note` dihilangkan bila null, `occurred_on` = epoch day. Bidang baru hanya boleh opsional di
 * versi yang sama; bidang tak dikenal diabaikan.
 */
object SharedExpensePayload {
    const val VERSION = 1

    fun encode(transaction: MoneyTransaction, accountName: String, categoryName: String?): JsonObject = buildJsonObject {
        put("amount_minor", transaction.amount.minor)
        put("currency", transaction.amount.currency.code)
        put("account_name", accountName)
        categoryName?.let { put("category_name", it) }
        put("occurred_on", transaction.occurredOn.toEpochDay())
        transaction.note?.let { put("note", it) }
    }

    /** @throws TransactionSyncPayloadCorrupt bila bidang wajib hilang, salah jenis, atau mata uangnya tidak dikenal. */
    fun decode(id: String, data: JsonObject): DecodedSyncTransaction {
        val amountMinor = data.number("amount_minor")
        val currencyCode = data.text("currency")
        val accountName = data.text("account_name")
        val occurredOn = data.number("occurred_on")
        val currency = Currency.entries.firstOrNull { it.code == currencyCode }
        if (amountMinor == null || currency == null || accountName == null || occurredOn == null || id.isEmpty()) {
            throw TransactionSyncPayloadCorrupt()
        }
        val date = try {
            LocalDate.ofEpochDay(occurredOn)
        } catch (e: Exception) {
            throw TransactionSyncPayloadCorrupt()
        }
        return DecodedSyncTransaction(
            id = TransactionId(id),
            amount = Money(amountMinor, currency),
            accountName = accountName,
            categoryName = data.text("category_name"),
            occurredOn = date,
            note = data.text("note"),
        )
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
}
