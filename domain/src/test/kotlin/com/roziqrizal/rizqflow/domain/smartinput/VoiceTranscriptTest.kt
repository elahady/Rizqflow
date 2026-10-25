package com.roziqrizal.rizqflow.domain.smartinput

import com.roziqrizal.rizqflow.domain.ledger.Account
import com.roziqrizal.rizqflow.domain.model.AccountId
import com.roziqrizal.rizqflow.domain.model.AccountKind
import com.roziqrizal.rizqflow.domain.model.TransactionKind
import com.roziqrizal.rizqflow.domain.money.Money
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tes tanpa perangkat untuk jalur input suara (Tahap 11): dari kandidat pengenal ke [SmartInputParser].
 * Mikrofon dan pengenal suara sungguhan tidak diuji di sini; itu belum teruji manual di perangkat.
 */
class VoiceTranscriptTest {

    @Test
    fun `memilih kandidat pertama yang tidak kosong dan merapikan spasinya`() {
        val best = VoiceTranscript.best(listOf("", "   ", "  gojek   23rb dari gopay ", "gojek 23 rb"))

        assertEquals("gojek 23rb dari gopay", best)
    }

    @Test
    fun `daftar kosong atau null tidak menghasilkan teks`() {
        assertNull(VoiceTranscript.best(emptyList()))
        assertNull(VoiceTranscript.best(null))
        assertNull(VoiceTranscript.best(listOf(null, " \t ")))
    }

    @Test
    fun `teks suara diuraikan parser yang sama dengan ketikan manual`() {
        val gopay = Account(AccountId("a-gopay"), "GoPay", AccountKind.EWALLET, Money.rupiah(0), sortOrder = 0)
        val context = SmartInputContext(
            accounts = listOf(gopay),
            rooms = emptyList(),
            categories = emptyMap(),
            history = emptyList(),
            today = LocalDate.of(2026, 10, 25),
        )
        val spoken = VoiceTranscript.best(listOf("gojek 23rb dari gopay"))!!

        val entry = SmartInputParser.parse(spoken, context)!!

        assertEquals(TransactionKind.EXPENSE, entry.kind)
        assertEquals(Money.rupiah(23_000), entry.amount)
        assertEquals(gopay.id, entry.accountId)
        assertEquals("gojek", entry.note)
    }

    @Test
    fun `kalimat suara tanpa nominal tidak menghasilkan isian`() {
        val context = SmartInputContext(emptyList(), emptyList(), emptyMap(), emptyList(), LocalDate.of(2026, 10, 25))
        val spoken = VoiceTranscript.best(listOf("makan siang enak sekali"))!!

        assertNull(SmartInputParser.parse(spoken, context))
    }
}
