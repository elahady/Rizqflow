package com.roziqrizal.rizqflow.domain.ledger

import com.roziqrizal.rizqflow.domain.auth.runSuspend
import com.roziqrizal.rizqflow.domain.model.TransactionId
import com.roziqrizal.rizqflow.domain.model.TransactionKind
import com.roziqrizal.rizqflow.domain.model.TransactionOrigin
import com.roziqrizal.rizqflow.domain.money.Money
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CsvExporterTest {

    private fun rupiah(n: Long) = Money.rupiah(n)

    @Test
    fun `header lalu satu baris per transaksi terurut menurut tanggal`() {
        val f = LedgerFixture().standard()
        f.income(1_000_000)
        runSuspend {
            f.ledger.recordExpense(NewExpense(rupiah(50_000), f.account.id, f.room("Diri").id, f.category("Diri", "Investasi").id, f.today.minusDays(1)))
        }

        val csv = CsvExporter.export(f.store.transactionRows.values.toList(), f.store.accountRows.values.toList(), f.store.roomRows.values.toList(), f.store.categoryRows.values.toList())
        val lines = csv.split("\r\n")

        assertEquals(CsvExporter.HEADER.joinToString(","), lines.first())
        assertEquals(3, lines.size) // header + 2 transaksi
        assertTrue(lines[1].startsWith((f.today.minusDays(1)).toString() + ",Pengeluaran,Dompet,,Diri,Investasi,50000,,"))
        assertTrue(lines[2].startsWith(f.today.toString() + ",Pemasukan,Dompet,,,,1000000,,Gaji"))
    }

    @Test
    fun `transfer menulis akun asal dan akun tujuan`() {
        val f = LedgerFixture().standard()
        val bank = f.addAccount("Bank")
        runSuspend { f.ledger.recordTransfer(NewTransfer(rupiah(200_000), f.account.id, bank.id, f.today)) }

        val csv = CsvExporter.export(f.store.transactionRows.values.toList(), f.store.accountRows.values.toList(), f.store.roomRows.values.toList(), f.store.categoryRows.values.toList())
        val line = csv.split("\r\n")[1]

        assertEquals("${f.today},Transfer,Dompet,Bank,,,200000,,", line)
    }

    @Test
    fun `bidang berisi koma atau kutip dibungkus dan dilarikan`() {
        val f = LedgerFixture().standard()
        runSuspend {
            f.ledger.recordExpense(NewExpense(rupiah(10_000), f.account.id, f.room("Diri").id, f.category("Diri", "Investasi").id, f.today, note = "beli, katanya \"murah\""))
        }

        val csv = CsvExporter.export(f.store.transactionRows.values.toList(), f.store.accountRows.values.toList(), f.store.roomRows.values.toList(), f.store.categoryRows.values.toList())
        val line = csv.split("\r\n")[1]

        assertTrue(line.contains("\"beli, katanya \"\"murah\"\"\""))
    }

    @Test
    fun `ekspor pribadi melewati pengeluaran anggota lain di ruang bersama`() {
        val f = LedgerFixture().standard()
        val diri = f.room("Diri")
        val virtual = runSuspend { f.store.findOrCreateVirtualMember(diri.id, "ayu", "Tunai") }
        val shared = MoneyTransaction(
            id = TransactionId("shared-1"),
            kind = TransactionKind.EXPENSE,
            amount = rupiah(40_000),
            accountId = virtual.id,
            roomId = diri.id,
            categoryId = f.category("Diri", "Investasi").id,
            occurredOn = f.today,
            note = "dari ayu",
            origin = TransactionOrigin.SHARED,
            createdAtMillis = 1,
            updatedAtMillis = 1,
        )
        runSuspend { f.store.save(shared) }
        f.income(1_000_000)

        val all = f.store.transactionRows.values.toList()
        assertEquals(2, all.size)
        val csv = CsvExporter.export(all, f.store.accountRows.values.toList(), f.store.roomRows.values.toList(), f.store.categoryRows.values.toList())

        assertEquals(2, csv.split("\r\n").size, "header + 1 baris pribadi")
        assertTrue("dari ayu" !in csv)
        assertTrue(!CsvExporter.isExportable(shared))
    }

    @Test
    fun `tanpa transaksi hanya berisi header`() {
        assertEquals(CsvExporter.HEADER.joinToString(","), CsvExporter.export(emptyList(), emptyList(), emptyList(), emptyList()))
    }
}
