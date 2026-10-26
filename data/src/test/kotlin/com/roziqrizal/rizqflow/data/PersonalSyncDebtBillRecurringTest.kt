package com.roziqrizal.rizqflow.data

import com.roziqrizal.rizqflow.domain.bill.Bill
import com.roziqrizal.rizqflow.domain.debt.Debt
import com.roziqrizal.rizqflow.domain.debt.DebtDirection
import com.roziqrizal.rizqflow.domain.debt.DebtPayment
import com.roziqrizal.rizqflow.domain.model.AccountId
import com.roziqrizal.rizqflow.domain.model.TransactionId
import com.roziqrizal.rizqflow.domain.model.TransactionKind
import com.roziqrizal.rizqflow.domain.money.Money
import com.roziqrizal.rizqflow.domain.recurring.Frequency
import com.roziqrizal.rizqflow.domain.recurring.RecurringRule
import com.roziqrizal.rizqflow.domain.sync.PersonalSyncResult
import com.roziqrizal.rizqflow.domain.sync.RecoveryStatus
import com.roziqrizal.rizqflow.domain.sync.SyncIssueKind
import com.roziqrizal.rizqflow.domain.sync.SyncScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sinkron cakupan pribadi untuk utang dan pembayaran, tagihan, serta transaksi berulang (Tahap 4
 * sync-desain.md). Memakai mesin [PersonalSyncCoordinator] dan [MemoryPersonalRemote] yang sama
 * dengan [PersonalSyncCoordinatorTest], jadi jalur yang diuji adalah jalur produksi.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PersonalSyncDebtBillRecurringTest {
    private lateinit var a: SyncFixture

    @Before
    fun setUp() {
        a = SyncFixture()
    }

    @After
    fun tearDown() {
        a.close()
    }

    /** Menyiapkan satu utang beserta pelunasannya, satu tagihan, dan satu transaksi berulang di perangkat A. */
    private suspend fun SyncFixture.seedDebtBillRecurring(): TransactionId {
        val accountId: AccountId = accountId()
        val room = local.rooms.activeRooms().first()
        val category = local.rooms.categories(room.id).first()
        val expenseId = expense(10_000)
        local.debts.save(
            Debt(
                id = "debt-1",
                direction = DebtDirection.BORROWED,
                party = "Budi",
                principal = Money.rupiah(500_000),
                accountId = accountId,
                startDate = today,
                dueDate = today.plusMonths(3),
                note = "Cicilan motor",
            ),
        )
        local.debts.savePayment(
            DebtPayment(id = "pay-1", debtId = "debt-1", amount = Money.rupiah(100_000), paidOn = today, transactionId = expenseId),
        )
        local.bills.save(
            Bill(
                id = "bill-1",
                name = "Listrik",
                amount = Money.rupiah(250_000),
                accountId = accountId,
                roomId = room.id,
                categoryId = category.id,
                frequency = Frequency.MONTHLY,
                startDate = today,
                nextDue = today.plusMonths(1),
                paidCount = 2,
            ),
        )
        local.recurring.save(
            RecurringRule(
                id = "rec-1",
                kind = TransactionKind.EXPENSE,
                amount = Money.rupiah(50_000),
                accountId = accountId,
                roomId = room.id,
                categoryId = category.id,
                note = "Langganan",
                frequency = Frequency.WEEKLY,
                startDate = today,
                nextDue = today.plusDays(7),
            ),
        )
        return expenseId
    }

    @Test
    fun `push mengirim utang, pelunasan, tagihan, dan berulang lalu membersihkan rekaman kotor`() = runBlocking {
        a.seedDebtBillRecurring()
        val remote = MemoryPersonalRemote()

        val result = engineFor(a.local, remote, RecoveryStatus.Configured(Instant.now())).syncNow("device-a")

        val success = assertIs<PersonalSyncResult.Success>(result)
        assertTrue(success.pushed > 0)
        val sentTypes = remote.stored.map { it.recordType }.toSet()
        assertTrue(sentTypes.containsAll(setOf("debt", "debt_payment", "bill", "recurring_rule")), "semua tipe baru harus terkirim, terkirim: $sentTypes")
        assertTrue(a.rows().none { it.dirty }, "semua rekaman yang berhasil terkirim harus bersih")
    }

    @Test
    fun `pull dari kursor 0 di perangkat baru menghasilkan utang, pelunasan, tagihan, dan berulang yang sama`() = runBlocking {
        val expenseId = a.seedDebtBillRecurring()
        val remote = MemoryPersonalRemote(shuffleSeed = 7)
        engineFor(a.local, remote, RecoveryStatus.Configured(Instant.now())).syncNow("device-a")

        val b = emptyLedger()
        val result = engineFor(b, remote).syncNow("device-b")

        assertTrue(assertIs<PersonalSyncResult.Success>(result).applied > 0)
        assertTrue(b.syncStore.issues(SyncScope.Personal).isEmpty(), "tidak ada referensi yang gagal walau urutan kedatangan diacak")

        val debt = assertNotNull(b.debts.find("debt-1"))
        assertEquals(DebtDirection.BORROWED, debt.direction)
        assertEquals("Budi", debt.party)
        assertEquals(500_000L, debt.principal.minor)
        assertEquals("Cicilan motor", debt.note)
        assertEquals(a.today.plusMonths(3), debt.dueDate)

        val payment = assertNotNull(b.debts.findPayment("pay-1"))
        assertEquals("debt-1", payment.debtId)
        assertEquals(100_000L, payment.amount.minor)
        assertEquals(expenseId, payment.transactionId)

        val bill = assertNotNull(b.bills.find("bill-1"))
        assertEquals("Listrik", bill.name)
        assertEquals(250_000L, bill.amount.minor)
        assertEquals(Frequency.MONTHLY, bill.frequency)
        assertEquals(2, bill.paidCount)
        assertEquals(a.today.plusMonths(1), bill.nextDue)

        val rule = assertNotNull(b.recurring.find("rec-1"))
        assertEquals(TransactionKind.EXPENSE, rule.kind)
        assertEquals(50_000L, rule.amount.minor)
        assertEquals(Frequency.WEEKLY, rule.frequency)
        assertEquals("Langganan", rule.note)
    }

    @Test
    fun `tagihan yang tiba sebelum akun dan ruangnya ditunda lalu diterapkan setelah rujukannya tiba`() = runBlocking {
        a.seedDebtBillRecurring()
        val remote = MemoryPersonalRemote(pageSize = 1)
        engineFor(a.local, remote, RecoveryStatus.Configured(Instant.now())).syncNow("device-a")
        // Tagihan dipindah ke depan; akun, ruang, dan kategorinya datang di halaman berikutnya.
        val reordered = remote.stored
            .sortedBy { if (it.recordType == "bill") 0 else 1 }
            .mapIndexed { index, entry -> entry.copy(id = index + 1L) }
        remote.stored.clear()
        remote.stored.addAll(reordered)

        val b = emptyLedger()
        engineFor(b, remote).syncNow("device-b")

        assertNotNull(b.bills.find("bill-1"), "tagihan harus diterapkan begitu rujukannya tiba di halaman berikutnya")
        assertTrue(b.syncStore.issues(SyncScope.Personal).isEmpty(), "percobaan ulang di akhir pull harus menuntaskan DEFERRED_MISSING_REF")
    }

    @Test
    fun `referensi yang tidak pernah tiba membuat tagihan tetap tertunda dengan sync_issue`() = runBlocking {
        a.seedDebtBillRecurring()
        val remote = MemoryPersonalRemote()
        engineFor(a.local, remote, RecoveryStatus.Configured(Instant.now())).syncNow("device-a")
        // Akun tidak pernah sampai ke perangkat B (mis. dihapus dari server sebelum diambil).
        remote.stored.removeAll { it.recordType == "account" }

        val b = emptyLedger()
        engineFor(b, remote).syncNow("device-b")

        assertNull(b.bills.find("bill-1"), "tagihan dengan akun yang belum ada tidak boleh diterapkan")
        assertTrue(
            b.syncStore.issues(SyncScope.Personal).any { it.recordType == "bill" && it.kind == SyncIssueKind.DEFERRED_MISSING_REF },
            "tagihan yang tertunda harus tercatat sebagai DEFERRED_MISSING_REF",
        )
    }

    @Test
    fun `tombstone utang, tagihan, dan berulang diterapkan sebagai hapus lokal`() = runBlocking {
        a.seedDebtBillRecurring()
        val remote = MemoryPersonalRemote()
        engineFor(a.local, remote, RecoveryStatus.Configured(Instant.now())).syncNow("device-a")

        val b = emptyLedger()
        engineFor(b, remote).syncNow("device-b")
        assertNotNull(b.debts.find("debt-1"))
        assertNotNull(b.bills.find("bill-1"))
        assertNotNull(b.recurring.find("rec-1"))

        a.local.debts.delete("debt-1") // pelunasan ikut tertombstone di repositori
        a.local.bills.delete("bill-1")
        a.local.recurring.delete("rec-1")
        engineFor(a.local, remote, RecoveryStatus.Configured(Instant.now())).syncNow("device-a")
        engineFor(b, remote).syncNow("device-b")

        assertNull(b.debts.find("debt-1"), "tombstone utang harus menghapus utang di perangkat lain")
        assertNull(b.debts.findPayment("pay-1"), "pelunasan ikut terhapus bersama utangnya")
        assertNull(b.bills.find("bill-1"))
        assertNull(b.recurring.find("rec-1"))
        assertTrue(b.syncStore.issues(SyncScope.Personal).isEmpty())
    }
}
