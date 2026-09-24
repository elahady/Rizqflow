package com.roziqrizal.rizqflow.domain.ledger

import com.roziqrizal.rizqflow.domain.auth.runSuspend
import com.roziqrizal.rizqflow.domain.model.TransactionKind
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DemoDataTest {

    private fun seeded(today: LocalDate = LocalDate.of(2026, 9, 21)): LedgerFixture {
        val f = LedgerFixture()
        val management = ManagementService(f.store, f.store, f.newId)
        val favorites = FavoriteService(f.store, f.store, f.store, f.ledger, f.newId) { f.now }
        runSuspend { DemoData(f.setup, f.ledger, management, favorites, f.store).seed(today) }
        return f
    }

    private fun denah(f: LedgerFixture, month: YearMonth, today: LocalDate) = runSuspend { DenahLoader(f.store, f.store, f.store).load(month, today) }

    @Test
    fun `data contoh memuat tiga ruang tiga akun dan empat favorit`() {
        val f = seeded()

        assertEquals(listOf("Memberi", "Diri", "Keluarga"), f.store.roomRows.values.map { it.name })
        assertEquals(listOf("Tunai", "Bank Jago", "GoPay"), f.store.accountRows.values.map { it.name })
        // Yang belum pernah dipakai berurutan menurut nama.
        assertEquals(listOf("Kopi", "Parkir", "Bensin", "Jajan"), runSuspend { f.store.all() }.map { it.name })
    }

    @Test
    fun `bulan ini menampilkan ketiga status ruang dan satu butir perhatian`() {
        val today = LocalDate.of(2026, 9, 21)
        val f = seeded(today)

        val ini = denah(f, YearMonth.from(today), today)

        val status = ini.cards.associate { it.room.name to it.status }
        assertEquals(RoomStatus.TERPENUHI, status["Memberi"])
        assertEquals(RoomStatus.BERJALAN, status["Diri"])
        assertEquals(RoomStatus.PERLU_PERHATIAN, status["Keluarga"])
        assertEquals(1, ini.attention.size)
        assertTrue(ini.income.isPositive)
        assertTrue(ini.unallocated.isZero)
    }

    @Test
    fun `bulan lalu punya isi dan dinilai di akhir bulan`() {
        val today = LocalDate.of(2026, 9, 21)
        val f = seeded(today)

        val lalu = denah(f, YearMonth.of(2026, 8), today)

        val status = lalu.cards.associate { it.room.name to it.status }
        assertEquals(RoomStatus.BELUM_TERCAPAI, status["Memberi"])
        assertEquals(RoomStatus.TERPENUHI, status["Diri"])
        assertEquals(RoomStatus.TERPENUHI, status["Keluarga"])
        assertTrue(lalu.attention.isEmpty())
    }

    @Test
    fun `tidak ada transaksi di masa depan walau hari ini awal bulan`() {
        val today = LocalDate.of(2026, 9, 1)
        val f = seeded(today)

        assertTrue(f.store.transactionRows.values.all { it.occurredOn <= today })
        assertTrue(f.store.transactionRows.values.any { it.occurredOn == today })
    }

    @Test
    fun `semua jenis transaksi ada dan saldo akun tidak negatif`() {
        val f = seeded()

        assertEquals(setOf(TransactionKind.INCOME, TransactionKind.EXPENSE, TransactionKind.TRANSFER), f.store.transactionRows.values.map { it.kind }.toSet())
        f.store.accountRows.keys.forEach { assertTrue(!f.balance(it).isNegative, "saldo ${f.store.accountRows.getValue(it).name}") }
    }

    @Test
    fun `favorit yang dipakai berurutan menurut pemakaian`() {
        val f = seeded()

        val daftar = runSuspend { f.store.all() }
        assertEquals(3, daftar.first().useCount)
        assertEquals("Kopi", daftar.first().name)
    }
}
