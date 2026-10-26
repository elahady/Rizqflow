package com.roziqrizal.rizqflow.domain.ledger

import com.roziqrizal.rizqflow.domain.auth.runSuspend
import com.roziqrizal.rizqflow.domain.model.AccountId
import com.roziqrizal.rizqflow.domain.model.CategoryId
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.model.TransactionId
import com.roziqrizal.rizqflow.domain.model.TransactionKind
import com.roziqrizal.rizqflow.domain.money.Money
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DuplicateTransactionDetectorTest {

    private val day = LocalDate.of(2026, 10, 26)
    private val now = 10_000_000_000L
    private val minute = 60_000L
    private val dompet = AccountId("dompet")
    private val bank = AccountId("bank")
    private val keluarga = RoomId("keluarga")
    private val diri = RoomId("diri")
    private val makan = CategoryId("makan")
    private val belanja = CategoryId("belanja")

    private val store = InMemoryLedger()
    private val detector = DuplicateTransactionDetector(store, nowMillis = { now })

    private fun expense(
        amount: Long = 25_000,
        account: AccountId = dompet,
        room: RoomId = keluarga,
        category: CategoryId = makan,
        createdAt: Long = now - 5 * minute,
        occurredOn: LocalDate = day,
        id: String = "tx-${store.transactionRows.size + 1}",
    ) = MoneyTransaction(
        id = TransactionId(id),
        kind = TransactionKind.EXPENSE,
        amount = Money.rupiah(amount),
        accountId = account,
        roomId = room,
        categoryId = category,
        occurredOn = occurredOn,
        createdAtMillis = createdAt,
        updatedAtMillis = createdAt,
    )

    private fun probe(
        amount: Long = 25_000,
        account: AccountId = dompet,
        room: RoomId? = keluarga,
        category: CategoryId? = makan,
        occurredOn: LocalDate = day,
    ) = DuplicateTransactionDetector.Probe(
        kind = TransactionKind.EXPENSE,
        amount = Money.rupiah(amount),
        accountId = account,
        roomId = room,
        categoryId = category,
        occurredOn = occurredOn,
    )

    private fun find(probe: DuplicateTransactionDetector.Probe): MoneyTransaction? = runSuspend { detector.findDuplicate(probe) }

    private fun saved(tx: MoneyTransaction) {
        runSuspend { store.save(tx) }
    }

    @Test
    fun `duplikat persis dalam jendela waktu terdeteksi`() {
        val existing = expense(createdAt = now - 5 * minute)
        saved(existing)

        assertEquals(existing.id, find(probe())?.id)
    }

    @Test
    fun `nominal berbeda tidak terdeteksi`() {
        saved(expense(amount = 25_000))

        assertNull(find(probe(amount = 25_001)))
        assertNull(find(probe(amount = 24_999)))
    }

    @Test
    fun `di luar jendela waktu tidak terdeteksi`() {
        saved(expense(createdAt = now - (DuplicateTransactionDetector.DEFAULT_WINDOW_MINUTES + 1) * minute))

        assertNull(find(probe()))
    }

    @Test
    fun `tepat di batas jendela waktu masih terdeteksi`() {
        val existing = expense(createdAt = now - DuplicateTransactionDetector.DEFAULT_WINDOW_MINUTES * minute)
        saved(existing)

        assertEquals(existing.id, find(probe())?.id)
    }

    @Test
    fun `akun berbeda tidak terdeteksi`() {
        saved(expense(account = dompet))

        assertNull(find(probe(account = bank)))
    }

    @Test
    fun `ruang berbeda tidak terdeteksi`() {
        saved(expense(room = keluarga))

        assertNull(find(probe(room = diri)))
    }

    @Test
    fun `kategori berbeda tidak terdeteksi`() {
        saved(expense(category = makan))

        assertNull(find(probe(category = belanja)))
    }

    @Test
    fun `tanggal kejadian jauh tidak ikut dicocokkan`() {
        saved(expense(occurredOn = day.minusDays(5)))

        assertNull(find(probe(occurredOn = day)))
    }

    @Test
    fun `jenis transaksi berbeda tidak terdeteksi`() {
        saved(expense())
        val income = probe().copy(kind = TransactionKind.INCOME, roomId = null, categoryId = null)

        assertNull(find(income))
    }

    @Test
    fun `pemasukan duplikat terdeteksi tanpa ruang dan kategori`() {
        val income = MoneyTransaction(
            id = TransactionId("pemasukan-1"),
            kind = TransactionKind.INCOME,
            amount = Money.rupiah(3_000_000),
            accountId = bank,
            occurredOn = day,
            createdAtMillis = now - minute,
            updatedAtMillis = now - minute,
        )
        saved(income)

        val found = find(DuplicateTransactionDetector.Probe(TransactionKind.INCOME, Money.rupiah(3_000_000), bank, occurredOn = day))

        assertEquals(income.id, found?.id)
    }

    @Test
    fun `transfer duplikat terdeteksi hanya bila pasangan akunnya sama`() {
        val transfer = MoneyTransaction(
            id = TransactionId("transfer-1"),
            kind = TransactionKind.TRANSFER,
            amount = Money.rupiah(100_000),
            accountId = dompet,
            toAccountId = bank,
            occurredOn = day,
            createdAtMillis = now - minute,
            updatedAtMillis = now - minute,
        )
        saved(transfer)
        val sama = DuplicateTransactionDetector.Probe(TransactionKind.TRANSFER, Money.rupiah(100_000), dompet, toAccountId = bank, occurredOn = day)
        val arah = sama.copy(accountId = bank, toAccountId = dompet)

        assertEquals(transfer.id, find(sama)?.id)
        assertNull(find(arah), "arah transfer dibalik adalah transaksi lain")
    }

    @Test
    fun `kandidat di luar satu hari dari tanggal kejadian tidak diambil`() {
        // Dicatat barusan tetapi tanggal kejadiannya tiga hari sebelumnya: tidak ada di jendela kandidat.
        saved(expense(occurredOn = day.minusDays(3)))

        assertNull(find(probe(occurredOn = day.minusDays(3).minusDays(2))))
    }

    @Test
    fun `pencocokan murni menghormati jendela yang diberikan`() {
        val existing = expense(createdAt = now - 2 * minute)

        assertTrue(DuplicateTransactionDetector.matches(probe(), existing, now, windowMillis = 2 * minute))
        assertFalse(DuplicateTransactionDetector.matches(probe(), existing, now, windowMillis = 2 * minute - 1))
    }

    @Test
    fun `bila ada lebih dari satu yang cocok, yang pertama dikembalikan`() {
        val pertama = expense(id = "pertama", createdAt = now - minute)
        saved(pertama)
        saved(expense(id = "kedua", createdAt = now - 2 * minute))

        assertEquals(pertama.id, find(probe())?.id)
    }

    @Test
    fun `isian Catat diubah menjadi probe dengan kolom yang sesuai jenisnya`() {
        val draft = CatatDraft(
            mode = CatatMode.EXPENSE,
            digits = "25000",
            accountId = dompet,
            roomId = keluarga,
            categoryId = makan,
            date = day,
        )

        assertEquals(probe(), draft.toDuplicateProbe())
    }
}
