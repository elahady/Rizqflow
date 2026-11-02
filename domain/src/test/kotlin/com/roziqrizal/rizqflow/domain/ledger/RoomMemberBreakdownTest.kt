package com.roziqrizal.rizqflow.domain.ledger

import com.roziqrizal.rizqflow.domain.model.AccountId
import com.roziqrizal.rizqflow.domain.model.AccountKind
import com.roziqrizal.rizqflow.domain.model.CategoryId
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.model.TransactionId
import com.roziqrizal.rizqflow.domain.model.TransactionKind
import com.roziqrizal.rizqflow.domain.money.Money
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** [roomMemberBreakdown] (peran-anggota.md D.3, H langkah 5): murni turunan transaksi, golden tanpa mock. */
class RoomMemberBreakdownTest {
    private val room = RoomId("r1")
    private val category = CategoryId("c1")
    private val today = LocalDate.of(2026, 11, 2)

    private fun account(id: String, sharedAuthor: String? = null) = Account(
        id = AccountId(id),
        name = "Akun $id",
        kind = AccountKind.CASH,
        openingBalance = Money.zero(),
        sharedFrom = if (sharedAuthor != null || id.startsWith("v")) room else null,
        sharedAuthor = sharedAuthor,
    )

    private fun expense(accountId: String, amount: Long, id: String = "t-$accountId-$amount") = MoneyTransaction(
        id = TransactionId(id),
        kind = TransactionKind.EXPENSE,
        amount = Money.rupiah(amount),
        accountId = AccountId(accountId),
        roomId = room,
        categoryId = category,
        occurredOn = today,
        createdAtMillis = 1,
        updatedAtMillis = 1,
    )

    @Test
    fun `baris saya sendiri dan baris anggota lain jadi dua baris terpisah`() {
        val me = account("saya")
        val ayu = account("v-ayu", sharedAuthor = "ayu")
        val accounts = listOf(me, ayu).associateBy { it.id }
        val expenses = listOf(expense("saya", 100_000), expense("v-ayu", 40_000))

        val result = roomMemberBreakdown(expenses, accounts)

        assertEquals(listOf(MemberSpend(null, Money.rupiah(100_000)), MemberSpend("ayu", Money.rupiah(40_000))), result)
    }

    @Test
    fun `dua akun virtual nama berbeda milik anggota yang sama digabung jadi satu baris`() {
        val tunai = account("v-tunai", sharedAuthor = "ayu")
        val bca = account("v-bca", sharedAuthor = "ayu")
        val accounts = listOf(tunai, bca).associateBy { it.id }
        val expenses = listOf(expense("v-tunai", 30_000), expense("v-bca", 20_000))

        val result = roomMemberBreakdown(expenses, accounts)

        assertEquals(listOf(MemberSpend("ayu", Money.rupiah(50_000))), result)
    }

    @Test
    fun `akun virtual pra-migrasi 12 tanpa sharedAuthor digabung sebagai anggota tidak dikenal`() {
        val unhealed = account("v-lama", sharedAuthor = null) // sharedFrom terisi lewat id berawalan v, sharedAuthor null
        val accounts = mapOf(unhealed.id to unhealed)
        val expenses = listOf(expense("v-lama", 15_000))

        val result = roomMemberBreakdown(expenses, accounts)

        assertEquals(listOf(MemberSpend("", Money.rupiah(15_000))), result)
    }

    @Test
    fun `ruang yang belum dibagi hanya menghasilkan satu baris saya`() {
        val me = account("saya")
        val result = roomMemberBreakdown(listOf(expense("saya", 70_000)), mapOf(me.id to me))

        assertEquals(listOf(MemberSpend(null, Money.rupiah(70_000))), result)
    }

    @Test
    fun `diurutkan saya dulu lalu anggota lain menurun terpakai`() {
        val me = account("saya")
        val ayu = account("v-ayu", sharedAuthor = "ayu")
        val budi = account("v-budi", sharedAuthor = "budi")
        val accounts = listOf(me, ayu, budi).associateBy { it.id }
        val expenses = listOf(expense("saya", 10_000), expense("v-ayu", 5_000), expense("v-budi", 50_000))

        val result = roomMemberBreakdown(expenses, accounts)

        assertEquals(listOf(null, "budi", "ayu"), result.map { it.author })
    }

    @Test
    fun `transaksi dengan akun yang tidak ditemukan di accountsById jatuh ke baris saya, tidak dilempar error`() {
        val result = roomMemberBreakdown(listOf(expense("tidak-ada", 10_000)), emptyMap())

        assertEquals(listOf(MemberSpend(null, Money.rupiah(10_000))), result)
    }
}
