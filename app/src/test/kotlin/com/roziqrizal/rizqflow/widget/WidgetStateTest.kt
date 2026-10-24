package com.roziqrizal.rizqflow.widget

import com.roziqrizal.rizqflow.domain.ledger.SafeToSpend
import com.roziqrizal.rizqflow.domain.money.Money
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WidgetStateTest {

    private fun rupiah(n: Long) = Money.rupiah(n)

    private val safe = SafeToSpend(dailyBudget = rupiah(100_000), spentToday = rupiah(30_000), daysLeft = 10)

    /** Menghitung berapa kali [safe] dipanggil, untuk membuktikan database tidak dibuka sia-sia. */
    private class Probe(val value: SafeToSpend?) {
        var calls = 0
        suspend fun get(): SafeToSpend? {
            calls++
            return value
        }
    }

    private fun resolve(pro: Boolean = true, signedIn: Boolean = true, lock: Boolean = false, probe: Probe) = runBlocking {
        WidgetStateResolver.resolve(pro, signedIn, lock, probe::get)
    }

    @Test
    fun `tanpa Pro hanya mengajak membuka aplikasi dan tidak membaca data`() {
        val probe = Probe(safe)

        assertEquals(WidgetState.NeedsPro, resolve(pro = false, probe = probe))
        assertEquals(0, probe.calls)
    }

    @Test
    fun `Pro tanpa akun masuk atau di mode demo tidak membaca data`() {
        val probe = Probe(safe)

        assertEquals(WidgetState.SignedOut, resolve(signedIn = false, probe = probe))
        assertEquals(0, probe.calls)
    }

    @Test
    fun `kunci aplikasi aktif menyembunyikan nominal dan tidak membaca data`() {
        val probe = Probe(safe)

        assertEquals(WidgetState.Locked, resolve(lock = true, probe = probe))
        assertEquals(0, probe.calls)
    }

    @Test
    fun `urutan keputusan Pro lalu akun lalu kunci`() {
        val probe = Probe(safe)

        // Semua syarat gagal sekaligus: Pro yang menentukan.
        assertEquals(WidgetState.NeedsPro, resolve(pro = false, signedIn = false, lock = true, probe = probe))
        // Pro ada tapi belum masuk dan terkunci: belum masuk yang menentukan.
        assertEquals(WidgetState.SignedOut, resolve(signedIn = false, lock = true, probe = probe))
        assertEquals(0, probe.calls)
    }

    @Test
    fun `tanpa jatah harian menghasilkan NoBudget`() {
        val probe = Probe(null)

        assertEquals(WidgetState.NoBudget, resolve(probe = probe))
        assertEquals(1, probe.calls)
    }

    @Test
    fun `sisa aman dibawa apa adanya`() {
        val state = resolve(probe = Probe(safe))

        assertEquals(WidgetState.Safe(remaining = rupiah(70_000), dailyBudget = rupiah(100_000), isOver = false), state)
    }

    @Test
    fun `jatah terlampaui menjadi nol dan ditandai, tidak pernah negatif`() {
        val over = SafeToSpend(dailyBudget = rupiah(100_000), spentToday = rupiah(150_000), daysLeft = 5)

        val state = resolve(probe = Probe(over)) as WidgetState.Safe

        assertEquals(rupiah(0), state.remaining)
        assertEquals(true, state.isOver)
        assertFalse(state.remaining.isNegative)
    }

    @Test
    fun `tepat habis bukan terlampaui`() {
        val exact = SafeToSpend(dailyBudget = rupiah(100_000), spentToday = rupiah(100_000), daysLeft = 5)

        val state = resolve(probe = Probe(exact)) as WidgetState.Safe

        assertEquals(rupiah(0), state.remaining)
        assertEquals(false, state.isOver)
    }
}
