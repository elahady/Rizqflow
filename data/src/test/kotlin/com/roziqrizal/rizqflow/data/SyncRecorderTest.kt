package com.roziqrizal.rizqflow.data

import com.roziqrizal.rizqflow.domain.allocation.AllocationMode
import com.roziqrizal.rizqflow.domain.allocation.BasisPoints
import com.roziqrizal.rizqflow.domain.bill.Bill
import com.roziqrizal.rizqflow.domain.debt.DebtDirection
import com.roziqrizal.rizqflow.domain.debt.DebtDraft
import com.roziqrizal.rizqflow.domain.debt.DebtService
import com.roziqrizal.rizqflow.domain.ledger.Account
import com.roziqrizal.rizqflow.domain.ledger.LedgerResult
import com.roziqrizal.rizqflow.domain.ledger.MoneyTransaction
import com.roziqrizal.rizqflow.domain.ledger.NewLoanMovement
import com.roziqrizal.rizqflow.domain.ledger.NewTransfer
import com.roziqrizal.rizqflow.domain.ledger.QuickFavorite
import com.roziqrizal.rizqflow.domain.model.AccountId
import com.roziqrizal.rizqflow.domain.model.AccountKind
import com.roziqrizal.rizqflow.domain.model.GoldPriceSource
import com.roziqrizal.rizqflow.domain.model.TransactionId
import com.roziqrizal.rizqflow.domain.model.TransactionKind
import com.roziqrizal.rizqflow.domain.model.TransactionOrigin
import com.roziqrizal.rizqflow.domain.model.WealthKind
import com.roziqrizal.rizqflow.domain.money.Money
import com.roziqrizal.rizqflow.domain.recurring.Frequency
import com.roziqrizal.rizqflow.domain.recurring.RecurringRule
import com.roziqrizal.rizqflow.domain.role.DcaPlan
import com.roziqrizal.rizqflow.domain.role.TraderProfile
import com.roziqrizal.rizqflow.domain.sync.SyncRecordIds
import com.roziqrizal.rizqflow.domain.sync.SyncRecordType
import com.roziqrizal.rizqflow.domain.sync.withSyncApplying
import com.roziqrizal.rizqflow.domain.zakat.GoldPrice
import com.roziqrizal.rizqflow.domain.zakat.WealthCheck
import com.roziqrizal.rizqflow.domain.zakat.WealthItem
import com.roziqrizal.rizqflow.domain.zakat.ZakatPayment
import com.roziqrizal.rizqflow.domain.zakat.ZakatProfile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Recorder (Tahap 1 sync-desain.md): setiap jalur tulis `Local*Repository` menandai rekaman kotor di transaksi
 * yang sama, penghapusan (termasuk cascade) menghasilkan tombstone, dan konteks `applying` tidak menandai apa pun.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncRecorderTest {
    private lateinit var fx: SyncFixture

    @Before
    fun setUp() {
        fx = SyncFixture()
    }

    @After
    fun tearDown() = fx.close()

    /** Satu jalur tulis nyata per jenis rekaman; mengembalikan id rekaman yang diharapkan ditandai kotor. */
    private fun ex(block: suspend (SyncFixture) -> String): suspend (SyncFixture) -> String = block

    private val exercisers: Map<SyncRecordType, suspend (SyncFixture) -> String> = mapOf(
        SyncRecordType.ROOM to ex { f -> f.local.rooms.saveRooms(listOf(f.roomIds().first().copy(name = "Diganti"))); f.roomIds().first().id.value },
        SyncRecordType.ACCOUNT to ex { f ->
            val account = f.local.accounts.activeAccounts().first()
            f.local.accounts.save(account.copy(name = "Baru"))
            account.id.value
        },
        SyncRecordType.CATEGORY to ex { f ->
            val room = f.roomIds().first()
            val category = f.local.rooms.categories(room.id).first()
            f.local.rooms.saveCategory(category.copy(name = "Ganti nama"))
            category.id.value
        },
        SyncRecordType.ALLOCATION_CONFIG to ex { f -> f.local.rooms.setAllocationMode(AllocationMode.WATERFALL); SyncRecordIds.ALLOCATION_CONFIG },
        SyncRecordType.TRANSACTION to ex { f -> f.expense().value },
        SyncRecordType.DEBT to ex { f -> debtService(f).create(lendDraft(f)).let { (it as LedgerResult.Success).value.id } },
        SyncRecordType.DEBT_PAYMENT to ex { f ->
            val debt = (debtService(f).create(lendDraft(f)) as LedgerResult.Success).value
            f.clearSync()
            val repayment = (debtService(f).repay(debt.id, Money.rupiah(10_000), f.today, f.accountId()) as LedgerResult.Success).value
            repayment.payment.id
        },
        SyncRecordType.BILL to ex { f ->
            val room = f.roomIds().first()
            f.local.bills.save(
                Bill("bill-1", "Listrik", Money.rupiah(300_000), f.accountId(), room.id, f.local.rooms.categories(room.id).first().id, null, Frequency.MONTHLY, f.today, f.today.plusDays(3)),
            )
            "bill-1"
        },
        SyncRecordType.RECURRING_RULE to ex { f ->
            f.local.recurring.save(RecurringRule("rr-1", TransactionKind.INCOME, Money.rupiah(100_000), f.accountId(), incomeSource = "Gaji", frequency = Frequency.MONTHLY, startDate = f.today, nextDue = f.today))
            "rr-1"
        },
        SyncRecordType.ZAKAT_PROFILE to ex { f -> f.local.zakat.saveProfile(ZakatProfile("zp-1", "Utama")); "zp-1" },
        SyncRecordType.WEALTH_ITEM to ex { f ->
            f.local.zakat.saveProfile(ZakatProfile("zp-1", "Utama"))
            f.clearSync()
            f.local.zakat.replaceItems("zp-1", listOf(WealthItem("wi-1", "zp-1", WealthKind.CASH_SAVINGS, "Tabungan", Money.rupiah(1_000_000), null, 1L)))
            "wi-1"
        },
        SyncRecordType.WEALTH_CHECK to ex { f ->
            f.local.zakat.saveProfile(ZakatProfile("zp-1", "Utama"))
            f.clearSync()
            f.local.zakat.saveCheck(WealthCheck("wc-1", "zp-1", f.today, Money.rupiah(1_000_000), Money.rupiah(900_000)))
            "wc-1"
        },
        SyncRecordType.ZAKAT_PAYMENT to ex { f ->
            f.local.zakat.saveProfile(ZakatProfile("zp-1", "Utama"))
            val tx = f.expense()
            f.clearSync()
            f.local.zakat.savePayment(ZakatPayment("zpay-1", "zp-1", f.today, tx))
            "zpay-1"
        },
        SyncRecordType.GOLD_PRICE to ex { f -> f.local.zakat.saveGoldPrice(GoldPrice(f.today, Money.rupiah(1_200_000), GoldPriceSource.MANUAL)); SyncRecordIds.goldPrice(f.today.toEpochDay()) },
        SyncRecordType.TRADER_PROFILE to ex { f ->
            val room = f.roomIds().last()
            f.local.roles.saveTrader(TraderProfile(room.id, Money.rupiah(5_000_000), BasisPoints(100)))
            room.id.value
        },
        SyncRecordType.DCA_PLAN to ex { f ->
            val room = f.roomIds().last()
            f.local.roles.saveDca(DcaPlan(room.id, Money.rupiah(100_000), 5, f.accountId(), f.local.rooms.categories(room.id).first().id))
            room.id.value
        },
        SyncRecordType.QUICK_FAVORITE to ex { f ->
            val room = f.roomIds().first()
            f.local.favorites.save(QuickFavorite("fav-1", "Kopi", Money.rupiah(20_000), room.id, f.local.rooms.categories(room.id).first().id, f.accountId()))
            "fav-1"
        },
        SyncRecordType.DAY_CHECK to ex { f -> f.local.settings.markDayChecked(f.today); SyncRecordIds.dayCheck(f.today.toEpochDay()) },
    )

    private fun debtService(f: SyncFixture) = DebtService(f.local.debts, f.local.accounts, f.local.transactions, f.ledger, f.newId)

    private suspend fun lendDraft(f: SyncFixture) = DebtDraft(DebtDirection.LENT, "Budi", Money.rupiah(200_000), f.today, accountId = f.accountId())

    @Test
    fun `setiap jenis rekaman punya jalur tulis yang menandainya kotor`() = runBlocking {
        // Tes ini gagal bila ada SyncRecordType baru tanpa pelatih di atas.
        assertEquals(SyncRecordType.entries.toSet(), exercisers.keys, "SyncRecordType tanpa jalur tulis yang diuji")
        for (type in SyncRecordType.entries) {
            val f = SyncFixture()
            try {
                f.clearSync()
                val id = exercisers.getValue(type)(f)
                val row = assertNotNull(f.row(type, id), "jalur tulis $type tidak menandai $id; baris: ${f.rows().map { it.type + ":" + it.id }}")
                assertTrue(row.dirty, "$type harus kotor")
                assertFalse(row.deleted, "$type bukan tombstone")
                assertEquals("a", row.scope)
                assertNull(row.deviceId, "versi lokal tidak menyimpan id perangkat")
            } finally {
                f.close()
            }
        }
    }

    @Test
    fun `onboarding menandai ruang, kategori, akun, dan konfigurasi alokasi`() {
        assertEquals(3, fx.ids(SyncRecordType.ROOM).size)
        assertTrue(fx.ids(SyncRecordType.CATEGORY).isNotEmpty())
        assertEquals(1, fx.ids(SyncRecordType.ACCOUNT).size)
        assertEquals(setOf(SyncRecordIds.ALLOCATION_CONFIG), fx.ids(SyncRecordType.ALLOCATION_CONFIG))
        assertTrue(fx.rows().all { it.dirty && !it.deleted })
    }

    @Test
    fun `pemasukan beserta potret alokasinya satu rekaman transaksi saja`() = runBlocking {
        fx.clearSync()
        val id = fx.income()
        assertEquals(setOf(id.value), fx.ids(SyncRecordType.TRANSACTION))
        assertEquals(1, fx.rows().size, "allocation_entry tertanam di pemasukan, tidak punya rekaman sendiri")
    }

    @Test
    fun `semua jenis transaksi dicatat sebagai rekaman transaksi`() = runBlocking {
        fx.clearSync()
        val income = fx.income()
        val expense = fx.expense()
        fx.local.accounts.save(Account(AccountId("acc-2"), "BCA", AccountKind.BANK, Money.rupiah(0)))
        val transfer = fx.ledger.recordTransfer(NewTransfer(Money.rupiah(1_000), fx.accountId(), AccountId("acc-2"), fx.today))
        val loan = fx.ledger.recordLoanMovement(NewLoanMovement(TransactionKind.LOAN_OUT, Money.rupiah(1_000), fx.accountId(), fx.today))
        assertEquals(
            setOf(income.value, expense.value, (transfer as LedgerResult.Success).value.id.value, (loan as LedgerResult.Success).value.id.value),
            fx.ids(SyncRecordType.TRANSACTION),
        )
    }

    @Test
    fun `mengubah transaksi menaikkan cap dan tetap kotor`() = runBlocking {
        val id = fx.expense()
        val before = fx.row(SyncRecordType.TRANSACTION, id.value)!!
        fx.markAllClean()
        fx.nowMillis += 5_000
        val stored = fx.local.transactions.find(id)!!
        fx.local.transactions.update(stored.copy(note = "diubah", updatedAtMillis = fx.nowMillis))
        val after = fx.row(SyncRecordType.TRANSACTION, id.value)!!
        assertTrue(after.dirty)
        assertTrue(after.changedAt > before.changedAt)
    }

    @Test
    fun `mengubah nominal pemasukan menghitung ulang potret dan menandai transaksi kotor`() = runBlocking {
        val id = fx.income()
        fx.markAllClean()
        fx.nowMillis += 1_000
        fx.ledger.editIncomeAmount(id, Money.rupiah(2_000_000))
        assertTrue(fx.row(SyncRecordType.TRANSACTION, id.value)!!.dirty)
    }

    @Test
    fun `update transaksi yang tidak ada tidak membuat rekaman hantu`() = runBlocking {
        val id = fx.expense()
        val stored = fx.local.transactions.find(id)!!
        fx.local.transactions.delete(id)
        fx.clearSync()
        fx.local.transactions.update(stored)
        assertTrue(fx.rows().isEmpty())
    }

    // ---- penghapusan dan cascade

    @Test
    fun `hapus transaksi menulis tombstone yang kotor dengan cap lebih besar`() = runBlocking {
        val id = fx.expense()
        val live = fx.row(SyncRecordType.TRANSACTION, id.value)!!
        fx.markAllClean()
        fx.local.transactions.delete(id)
        val tomb = fx.row(SyncRecordType.TRANSACTION, id.value)!!
        assertTrue(tomb.deleted)
        assertTrue(tomb.dirty)
        assertTrue(tomb.changedAt > live.changedAt, "tombstone harus mengalahkan versi yang sudah dikirim")
    }

    @Test
    fun `hapus transaksi yang tidak ada tidak menulis tombstone`() = runBlocking {
        fx.clearSync()
        fx.local.transactions.delete(TransactionId("tidak-ada"))
        assertTrue(fx.rows().isEmpty())
    }

    @Test
    fun `hapus utang menghasilkan tombstone untuk utang dan tiap pelunasan yang ikut terhapus cascade`() = runBlocking {
        val service = debtService(fx)
        val debt = (service.create(lendDraft(fx)) as LedgerResult.Success).value
        val p1 = (service.repay(debt.id, Money.rupiah(10_000), fx.today, fx.accountId()) as LedgerResult.Success).value.payment.id
        val p2 = (service.repay(debt.id, Money.rupiah(20_000), fx.today, null) as LedgerResult.Success).value.payment.id
        fx.markAllClean()

        fx.local.debts.delete(debt.id)

        assertTrue(fx.row(SyncRecordType.DEBT, debt.id)!!.deleted)
        assertTrue(fx.row(SyncRecordType.DEBT_PAYMENT, p1)!!.deleted)
        assertTrue(fx.row(SyncRecordType.DEBT_PAYMENT, p2)!!.deleted)
        assertTrue(listOf(SyncRecordType.DEBT to debt.id, SyncRecordType.DEBT_PAYMENT to p1, SyncRecordType.DEBT_PAYMENT to p2).all { (t, i) -> fx.row(t, i)!!.dirty })
        assertTrue(fx.local.debts.allPayments().isEmpty())
    }

    @Test
    fun `layanan hapus utang menghapus pelunasan, transaksi saldonya, dan semuanya bertombstone`() = runBlocking {
        val service = debtService(fx)
        val debt = (service.create(lendDraft(fx)) as LedgerResult.Success).value
        val payment = (service.repay(debt.id, Money.rupiah(10_000), fx.today, fx.accountId()) as LedgerResult.Success).value.payment
        fx.markAllClean()

        service.delete(debt.id)

        assertTrue(fx.row(SyncRecordType.DEBT, debt.id)!!.deleted)
        assertTrue(fx.row(SyncRecordType.DEBT_PAYMENT, payment.id)!!.deleted)
        assertTrue(fx.row(SyncRecordType.TRANSACTION, debt.initialTransactionId!!.value)!!.deleted)
        assertTrue(fx.row(SyncRecordType.TRANSACTION, payment.transactionId!!.value)!!.deleted)
    }

    @Test
    fun `hapus satu pelunasan menulis tombstone pelunasan itu saja`() = runBlocking {
        val service = debtService(fx)
        val debt = (service.create(lendDraft(fx)) as LedgerResult.Success).value
        val payment = (service.repay(debt.id, Money.rupiah(10_000), fx.today, null) as LedgerResult.Success).value.payment
        fx.markAllClean()
        fx.local.debts.deletePayment(payment.id)
        assertTrue(fx.row(SyncRecordType.DEBT_PAYMENT, payment.id)!!.deleted)
        assertFalse(fx.row(SyncRecordType.DEBT, debt.id)!!.dirty)
    }

    @Test
    fun `menghapus transaksi yang dirujuk utang dan pelunasan menandai keduanya kotor karena kunci asing SET NULL`() = runBlocking {
        val service = debtService(fx)
        val debt = (service.create(lendDraft(fx)) as LedgerResult.Success).value
        val payment = (service.repay(debt.id, Money.rupiah(10_000), fx.today, fx.accountId()) as LedgerResult.Success).value.payment
        fx.markAllClean()

        // Menghapus langsung lewat repositori transaksi (bukan lewat DebtService): kunci asing mengosongkan rujukan di utang/pelunasan.
        fx.local.transactions.delete(debt.initialTransactionId!!)
        fx.local.transactions.delete(payment.transactionId!!)

        assertNull(fx.local.debts.find(debt.id)!!.initialTransactionId)
        assertTrue(fx.row(SyncRecordType.DEBT, debt.id)!!.dirty, "utang berubah lewat SET NULL, harus ikut terkirim")
        assertTrue(fx.row(SyncRecordType.DEBT_PAYMENT, payment.id)!!.dirty)
        assertFalse(fx.row(SyncRecordType.DEBT, debt.id)!!.deleted)
    }

    @Test
    fun `hapus tagihan, aturan berulang, favorit, dan peran menulis tombstone`() = runBlocking {
        exercisers.getValue(SyncRecordType.BILL)(fx)
        exercisers.getValue(SyncRecordType.RECURRING_RULE)(fx)
        exercisers.getValue(SyncRecordType.QUICK_FAVORITE)(fx)
        val traderRoom = exercisers.getValue(SyncRecordType.TRADER_PROFILE)(fx)
        fx.markAllClean()

        fx.local.bills.delete("bill-1")
        fx.local.recurring.delete("rr-1")
        fx.local.favorites.delete("fav-1")
        fx.local.roles.remove(com.roziqrizal.rizqflow.domain.model.RoomId(traderRoom))

        for ((type, id) in listOf(
            SyncRecordType.BILL to "bill-1",
            SyncRecordType.RECURRING_RULE to "rr-1",
            SyncRecordType.QUICK_FAVORITE to "fav-1",
            SyncRecordType.TRADER_PROFILE to traderRoom,
        )) {
            val row = fx.row(type, id)!!
            assertTrue(row.deleted && row.dirty, "$type $id harus tombstone kotor")
        }
        // dca_plan tidak ada di ruang itu: tidak ada tombstone hantu.
        assertNull(fx.row(SyncRecordType.DCA_PLAN, traderRoom))
    }

    @Test
    fun `mengganti daftar harta menghapus baris yang hilang dan hanya menandai baris yang berubah`() = runBlocking {
        fx.local.zakat.saveProfile(ZakatProfile("zp-1", "Utama"))
        val a = WealthItem("wi-a", "zp-1", WealthKind.CASH_SAVINGS, "Tabungan", Money.rupiah(1_000_000), null, 1L)
        val b = WealthItem("wi-b", "zp-1", WealthKind.OTHER, "Lain", Money.rupiah(500_000), null, 1L)
        fx.local.zakat.replaceItems("zp-1", listOf(a, b))
        fx.markAllClean()

        fx.local.zakat.replaceItems("zp-1", listOf(a, WealthItem("wi-c", "zp-1", WealthKind.OTHER, "Baru", Money.rupiah(1), null, 2L)))

        assertFalse(fx.row(SyncRecordType.WEALTH_ITEM, "wi-a")!!.dirty, "a tidak berubah")
        assertTrue(fx.row(SyncRecordType.WEALTH_ITEM, "wi-b")!!.let { it.deleted && it.dirty })
        assertTrue(fx.row(SyncRecordType.WEALTH_ITEM, "wi-c")!!.let { !it.deleted && it.dirty })
    }

    @Test
    fun `pemeriksaan harta di hari yang sama mengganti pemeriksaan lama dan menulis tombstone yang lama`() = runBlocking {
        fx.local.zakat.saveProfile(ZakatProfile("zp-1", "Utama"))
        fx.local.zakat.saveCheck(WealthCheck("wc-old", "zp-1", fx.today, Money.rupiah(1), Money.rupiah(1)))
        fx.markAllClean()

        fx.local.zakat.saveCheck(WealthCheck("wc-new", "zp-1", fx.today, Money.rupiah(2), Money.rupiah(1)))

        assertTrue(fx.row(SyncRecordType.WEALTH_CHECK, "wc-old")!!.let { it.deleted && it.dirty })
        assertTrue(fx.row(SyncRecordType.WEALTH_CHECK, "wc-new")!!.let { !it.deleted && it.dirty })
    }

    @Test
    fun `harga emas AUTO tidak disinkronkan tetapi AUTO yang menimpa MANUAL menghapusnya`() = runBlocking {
        fx.clearSync()
        val day = LocalDate.of(2026, 10, 1)
        fx.local.zakat.saveGoldPrice(GoldPrice(day, Money.rupiah(1_000), GoldPriceSource.AUTO))
        assertTrue(fx.rows().isEmpty(), "AUTO per perangkat")

        val other = LocalDate.of(2026, 10, 2)
        fx.local.zakat.saveGoldPrice(GoldPrice(other, Money.rupiah(1_000), GoldPriceSource.MANUAL))
        assertTrue(fx.row(SyncRecordType.GOLD_PRICE, SyncRecordIds.goldPrice(other.toEpochDay()))!!.let { !it.deleted && it.dirty })
        fx.local.zakat.saveGoldPrice(GoldPrice(other, Money.rupiah(1_100), GoldPriceSource.AUTO))
        assertTrue(fx.row(SyncRecordType.GOLD_PRICE, SyncRecordIds.goldPrice(other.toEpochDay()))!!.deleted)
    }

    // ---- hal yang tidak boleh dicatat

    @Test
    fun `pengaturan perangkat tidak ikut tetapi mode alokasi dan hari yang ditandai ikut`() = runBlocking {
        fx.clearSync()
        fx.local.settings.put("reminder_enabled", "1")
        fx.local.settings.put("bill_reminder_notified_x", "1:APPROACHING")
        assertTrue(fx.rows().isEmpty())

        fx.local.settings.put("allocation_mode", "WATERFALL")
        assertEquals(setOf(SyncRecordIds.ALLOCATION_CONFIG), fx.ids(SyncRecordType.ALLOCATION_CONFIG))

        fx.clearSync()
        fx.local.settings.markDayChecked(fx.today)
        val first = fx.row(SyncRecordType.DAY_CHECK, SyncRecordIds.dayCheck(fx.today.toEpochDay()))!!
        fx.markAllClean()
        fx.local.settings.markDayChecked(fx.today)
        assertFalse(fx.row(SyncRecordType.DAY_CHECK, SyncRecordIds.dayCheck(fx.today.toEpochDay()))!!.dirty, "menandai ulang hari yang sama bukan perubahan")
        assertEquals(first.changedAt, fx.row(SyncRecordType.DAY_CHECK, SyncRecordIds.dayCheck(fx.today.toEpochDay()))!!.changedAt)
    }

    @Test
    fun `pemakaian favorit saja tidak menandai kotor tetapi mengubah isinya menandai`() = runBlocking {
        val id = exercisers.getValue(SyncRecordType.QUICK_FAVORITE)(fx)
        fx.markAllClean()
        val fav = fx.local.favorites.find(id)!!

        fx.local.favorites.save(fav.copy(useCount = fav.useCount + 1, lastUsedAtMillis = 123L))
        assertFalse(fx.row(SyncRecordType.QUICK_FAVORITE, id)!!.dirty, "use_count dan last_used_at tidak ikut sync")

        fx.local.favorites.save(fav.copy(name = "Kopi susu"))
        assertTrue(fx.row(SyncRecordType.QUICK_FAVORITE, id)!!.dirty)
    }

    @Test
    fun `transaksi origin SHARED bukan milik cakupan pribadi dan tidak dicatat`() = runBlocking {
        val room = fx.roomIds().first()
        val shared = MoneyTransaction(
            id = TransactionId("shared-1"),
            kind = TransactionKind.EXPENSE,
            amount = Money.rupiah(5_000),
            accountId = fx.accountId(),
            roomId = room.id,
            categoryId = fx.local.rooms.categories(room.id).first().id,
            occurredOn = fx.today,
            origin = TransactionOrigin.SHARED,
            createdAtMillis = 1,
            updatedAtMillis = 1,
        )
        fx.clearSync()
        fx.local.transactions.save(shared)
        assertTrue(fx.rows().isEmpty())
        fx.local.transactions.delete(shared.id)
        assertTrue(fx.rows().isEmpty(), "menghapus baris SHARED juga bukan tombstone pribadi")
    }

    @Test
    fun `hasil pull SyncEngine lama lewat applyFromSync tidak dicatat`() = runBlocking {
        val room = fx.roomIds().first()
        val tx = MoneyTransaction(
            id = TransactionId("partner-1"), kind = TransactionKind.EXPENSE, amount = Money.rupiah(1_000), accountId = fx.accountId(),
            roomId = room.id, categoryId = fx.local.rooms.categories(room.id).first().id, occurredOn = fx.today,
            origin = TransactionOrigin.IMPORT, createdAtMillis = 5, updatedAtMillis = 5,
        )
        fx.clearSync()
        fx.local.transactions.applyFromSync(tx.id, tx, 5L, deleted = false)
        assertTrue(fx.rows().isEmpty())
    }

    @Test
    fun `seluruh jejak recorder ada di cakupan pribadi dan tidak pernah di cakupan ruang`() = runBlocking {
        val scopes = mutableSetOf<String>()
        for (type in SyncRecordType.entries) {
            exercisers.getValue(type)(fx)
            scopes += fx.rows().map { it.scope }
        }
        assertEquals(setOf("a"), scopes)
    }

    // ---- konteks applying

    @Test
    fun `penulisan di konteks applying tidak menandai apa pun`() = runBlocking {
        fx.clearSync()
        withSyncApplying {
            val account = fx.local.accounts.activeAccounts().first()
            fx.local.accounts.save(account.copy(name = "Dari pull"))
            fx.local.rooms.setAllocationMode(AllocationMode.WATERFALL)
            fx.expense()
            fx.local.transactions.delete(fx.expense())
        }
        assertTrue(fx.rows().isEmpty(), "applying tidak boleh menandai kotor: ${fx.rows()}")
        // Di luar applying kembali mencatat.
        fx.local.accounts.save(fx.local.accounts.activeAccounts().first().copy(name = "Lokal"))
        assertEquals(1, fx.rows().size)
    }

    // ---- cap dan atomisitas

    @Test
    fun `dua edit berturut-turut pada jam yang tidak bergerak tetap menaikkan cap satu per satu`() = runBlocking {
        val id = fx.expense()
        val first = fx.row(SyncRecordType.TRANSACTION, id.value)!!.changedAt
        val stored = fx.local.transactions.find(id)!!
        fx.local.transactions.update(stored.copy(note = "a"))
        val second = fx.row(SyncRecordType.TRANSACTION, id.value)!!.changedAt
        fx.local.transactions.update(stored.copy(note = "b"))
        val third = fx.row(SyncRecordType.TRANSACTION, id.value)!!.changedAt
        assertTrue(second > first && third > second, "$first $second $third")
    }

    @Test
    fun `cap melewati versi yang diterima dari perangkat berjam maju walau jam lokal tertinggal`() = runBlocking {
        val id = fx.expense()
        // Versi jauh di masa depan yang diterima lewat pull (lantai untuk edit berikutnya).
        fx.db.openHelper.writableDatabase.execSQL("UPDATE sync_record SET changed_at = 9000000000, dirty = 0 WHERE record_id = '${id.value}'")
        fx.local.transactions.update(fx.local.transactions.find(id)!!.copy(note = "setelah melihat versi jauh"))
        assertEquals(9_000_000_001L, fx.row(SyncRecordType.TRANSACTION, id.value)!!.changedAt)
    }

    @Test
    fun `penulisan yang gagal membatalkan penanda kotornya sekaligus`() = runBlocking {
        fx.clearSync()
        val room = fx.roomIds().first()
        val bad = MoneyTransaction(
            id = TransactionId("bad-1"), kind = TransactionKind.EXPENSE, amount = Money.rupiah(1_000),
            accountId = com.roziqrizal.rizqflow.domain.model.AccountId("akun-tidak-ada"), roomId = room.id,
            categoryId = fx.local.rooms.categories(room.id).first().id, occurredOn = fx.today,
            createdAtMillis = 1, updatedAtMillis = 1,
        )
        assertFailsWith<Exception> { fx.local.transactions.save(bad) }
        assertNull(fx.row(SyncRecordType.TRANSACTION, "bad-1"), "kunci asing gagal: tidak boleh ada penanda yatim")
        assertTrue(fx.rows().isEmpty())

        // Duplikat id: baris pertama utuh, rekamannya tidak berubah.
        val id = fx.expense()
        val before = fx.row(SyncRecordType.TRANSACTION, id.value)
        assertFailsWith<Exception> { fx.local.transactions.save(fx.local.transactions.find(id)!!) }
        assertEquals(before, fx.row(SyncRecordType.TRANSACTION, id.value))
    }

    @Test
    fun `hapus transaksi yang dirujuk pembayaran zakat gagal RESTRICT dan tidak meninggalkan tombstone`() = runBlocking {
        fx.local.zakat.saveProfile(ZakatProfile("zp-1", "Utama"))
        val tx = fx.expense()
        fx.local.zakat.savePayment(ZakatPayment("zpay-1", "zp-1", fx.today, tx))
        fx.markAllClean()

        assertFailsWith<Exception> { fx.local.transactions.delete(tx) }

        assertNotNull(fx.local.transactions.find(tx))
        assertFalse(fx.row(SyncRecordType.TRANSACTION, tx.value)!!.deleted)
        assertFalse(fx.row(SyncRecordType.TRANSACTION, tx.value)!!.dirty)
    }
}

