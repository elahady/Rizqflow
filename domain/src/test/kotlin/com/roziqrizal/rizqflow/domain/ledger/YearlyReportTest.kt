package com.roziqrizal.rizqflow.domain.ledger

import com.roziqrizal.rizqflow.domain.auth.runSuspend
import com.roziqrizal.rizqflow.domain.model.TransactionKind
import com.roziqrizal.rizqflow.domain.money.Money
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YearlyReportLoaderTest {

    private fun rupiah(n: Long) = Money.rupiah(n)

    private class Env(val f: LedgerFixture) {
        val loader = YearlyReportLoader(f.store, f.store)
        val keluarga = f.room("Keluarga")
        val diri = f.room("Diri")
    }

    private fun env() = Env(LedgerFixture().standard())

    private suspend fun Env.expense(amount: Long, room: Room, category: Category, date: LocalDate) {
        f.ledger.recordExpense(NewExpense(rupiah(amount), f.account.id, room.id, category.id, date, null))
    }

    private suspend fun Env.income(amount: Long, date: LocalDate) {
        f.ledger.recordIncome(NewIncome(rupiah(amount), f.account.id, "Gaji", date))
    }

    private fun Env.categoryOf(room: Room): Category = f.store.categoryRows.values.first { it.roomId == room.id && !it.isSystem }

    @Test
    fun `tahun tanpa transaksi menghasilkan dua belas bulan kosong tanpa pembagi nol`() = runSuspend {
        val e = env()

        val report = e.loader.load(2026)

        assertFalse(report.hasData)
        assertEquals(12, report.months.size)
        assertEquals((1..12).map { YearMonth.of(2026, it) }, report.months.map { it.month })
        assertTrue(report.months.none { it.hasData })
        assertNull(report.income.percentBp)
        assertTrue(report.roomSpend.isEmpty())
        assertTrue(report.topCategories.isEmpty())
        assertTrue(report.activeExpenseMonths.isEmpty())
    }

    @Test
    fun `total tahun dan rincian per bulan dijumlahkan benar`() = runSuspend {
        val e = env()
        val kategori = e.categoryOf(e.keluarga)
        e.income(5_000_000, LocalDate.of(2026, 1, 5))
        e.income(6_000_000, LocalDate.of(2026, 3, 5))
        e.expense(300_000, e.keluarga, kategori, LocalDate.of(2026, 1, 10))
        e.expense(200_000, e.keluarga, kategori, LocalDate.of(2026, 1, 20))
        e.expense(700_000, e.keluarga, kategori, LocalDate.of(2026, 3, 8))

        val report = e.loader.load(2026)

        assertEquals(rupiah(11_000_000), report.income.current)
        assertEquals(rupiah(1_200_000), report.expense.current)
        assertEquals(rupiah(9_800_000), report.net.current)
        assertEquals(rupiah(500_000), report.months[0].expense)
        assertEquals(rupiah(5_000_000), report.months[0].income)
        assertEquals(rupiah(0), report.months[1].expense)
        assertEquals(rupiah(700_000), report.months[2].expense)
        assertEquals(rupiah(4_500_000), report.months[0].net) // 5.000.000 - 500.000
        assertEquals(rupiah(5_300_000), report.months[2].net) // 6.000.000 - 700.000
        assertEquals(listOf(YearMonth.of(2026, 1), YearMonth.of(2026, 3)), report.activeExpenseMonths.map { it.month })
    }

    @Test
    fun `bulan terakhir dan pertama tahun masuk batas hari`() = runSuspend {
        val e = env()
        val kategori = e.categoryOf(e.keluarga)
        e.expense(100_000, e.keluarga, kategori, LocalDate.of(2026, 1, 1))
        e.expense(200_000, e.keluarga, kategori, LocalDate.of(2026, 12, 31))
        e.expense(900_000, e.keluarga, kategori, LocalDate.of(2025, 12, 31))
        e.expense(800_000, e.keluarga, kategori, LocalDate.of(2027, 1, 1))

        val report = e.loader.load(2026)

        assertEquals(rupiah(300_000), report.expense.current)
        assertEquals(rupiah(100_000), report.months[0].expense)
        assertEquals(rupiah(200_000), report.months[11].expense)
        // Tahun lalu hanya 2025: 31 Des 2025 masuk pembanding, 1 Jan 2027 tidak masuk mana pun.
        assertEquals(rupiah(900_000), report.expense.previous)
    }

    @Test
    fun `perbandingan tahun lalu menghitung selisih dan persen`() = runSuspend {
        val e = env()
        val kategori = e.categoryOf(e.keluarga)
        e.income(10_000_000, LocalDate.of(2025, 6, 1))
        e.expense(4_000_000, e.keluarga, kategori, LocalDate.of(2025, 6, 10))
        e.income(12_000_000, LocalDate.of(2026, 6, 1))
        e.expense(6_000_000, e.keluarga, kategori, LocalDate.of(2026, 6, 10))

        val report = e.loader.load(2026)

        assertEquals(rupiah(10_000_000), report.income.previous)
        assertEquals(2_000, report.income.percentBp) // 2.000.000 / 10.000.000 = 20%
        assertEquals(5_000, report.expense.percentBp) // 2.000.000 / 4.000.000 = 50%
    }

    @Test
    fun `tahun lalu kosong tidak punya dasar persentase`() = runSuspend {
        val e = env()
        e.expense(250_000, e.keluarga, e.categoryOf(e.keluarga), LocalDate.of(2026, 5, 5))

        val report = e.loader.load(2026)

        assertEquals(rupiah(0), report.expense.previous)
        assertNull(report.expense.percentBp)
    }

    @Test
    fun `pengeluaran per ruang dan kategori setahun diurutkan terbesar dulu`() = runSuspend {
        val e = env()
        val kategoriKeluarga = e.categoryOf(e.keluarga)
        val kategoriDiri = e.categoryOf(e.diri)
        e.expense(200_000, e.keluarga, kategoriKeluarga, LocalDate.of(2026, 2, 3))
        e.expense(300_000, e.keluarga, kategoriKeluarga, LocalDate.of(2026, 9, 4))
        e.expense(150_000, e.diri, kategoriDiri, LocalDate.of(2026, 11, 5))

        val report = e.loader.load(2026)

        assertEquals(listOf(e.keluarga.id, e.diri.id), report.roomSpend.map { it.room.id })
        assertEquals(rupiah(500_000), report.roomSpend[0].amount)
        assertEquals(kategoriKeluarga.id, report.topCategories.first().category.id)
        assertEquals(rupiah(500_000), report.topCategories.first().amount)
    }

    @Test
    fun `kategori tahunan dibatasi sepuluh`() = runSuspend {
        val e = env()
        val categories = (1..12).map { i ->
            val category = Category(
                com.roziqrizal.rizqflow.domain.model.CategoryId("cat-y$i"),
                e.keluarga.id,
                "Kategori $i",
                sortOrder = 100 + i,
            )
            e.f.store.categoryRows[category.id] = category
            category
        }
        categories.forEachIndexed { index, category -> e.expense((12 - index) * 10_000L, e.keluarga, category, LocalDate.of(2026, 4, 1 + index)) }

        val report = e.loader.load(2026)

        assertEquals(YearlyReport.MAX_CATEGORIES, report.topCategories.size)
        assertEquals(rupiah(120_000), report.topCategories.first().amount)
    }

    @Test
    fun `transfer dan pinjaman tidak dihitung`() = runSuspend {
        val e = env()
        val other = e.f.addAccount("Tabungan", opening = 1_000_000)
        e.f.ledger.recordTransfer(NewTransfer(rupiah(200_000), e.f.account.id, other.id, LocalDate.of(2026, 3, 5)))
        e.f.ledger.recordLoanMovement(NewLoanMovement(TransactionKind.LOAN_OUT, rupiah(150_000), e.f.account.id, LocalDate.of(2026, 3, 6)))
        e.expense(50_000, e.keluarga, e.categoryOf(e.keluarga), LocalDate.of(2026, 3, 7))

        val report = e.loader.load(2026)

        assertEquals(rupiah(50_000), report.expense.current)
        assertEquals(rupiah(0), report.income.current)
    }

    @Test
    fun `ruang yang diarsipkan tetap terhitung di laporan tahunan`() = runSuspend {
        val e = env()
        val kategori = e.categoryOf(e.keluarga)
        e.expense(400_000, e.keluarga, kategori, LocalDate.of(2026, 8, 5))
        e.f.store.roomRows[e.keluarga.id] = e.keluarga.copy(archived = true)

        val report = e.loader.load(2026)

        assertTrue(report.roomSpend.single().room.archived)
        assertEquals(rupiah(400_000), report.roomSpend.single().amount)
    }
}
