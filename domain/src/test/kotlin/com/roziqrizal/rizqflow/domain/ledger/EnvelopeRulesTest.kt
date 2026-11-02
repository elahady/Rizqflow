package com.roziqrizal.rizqflow.domain.ledger

import com.roziqrizal.rizqflow.domain.money.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kasus dari peran-anggota.md D.3 yang menjadi tanggung jawab [EnvelopeRules] (bukan `roomMemberBreakdown`/
 * `roomTotals`, yang sudah dites terkunci di langkah 3/5): amplop efektif per bulan, status pakai [RoomStatusRules]
 * yang sudah ada, dan jatah ruang (sum amplop anggota aktif).
 */
class EnvelopeRulesTest {
    private val nov = 202611
    private val des = 202612

    @Test
    fun `amplop efektif memilih from_month terbesar yang tidak melebihi bulan ini`() {
        val entries = listOf(
            EnvelopeEntry("ayu", 202609, 1_000_000),
            EnvelopeEntry("ayu", 202611, 4_000_000),
        )
        assertEquals(1_000_000, EnvelopeRules.effectiveAmount(entries, "ayu", 202610))
        assertEquals(4_000_000, EnvelopeRules.effectiveAmount(entries, "ayu", nov))
        assertEquals(4_000_000, EnvelopeRules.effectiveAmount(entries, "ayu", des)) // berlaku terus sampai diubah
    }

    @Test
    fun `amount 0 menghapus amplop mulai bulan itu`() {
        val entries = listOf(
            EnvelopeEntry("dimas", 202610, 2_000_000),
            EnvelopeEntry("dimas", nov, 0),
        )
        assertEquals(0, EnvelopeRules.effectiveAmount(entries, "dimas", nov))
        val summary = EnvelopeRules.summarize(
            month = nov,
            entries = entries,
            activeMemberIds = setOf("dimas"),
            spentByMember = mapOf("dimas" to Money.rupiah(500_000)),
            totalSpentRoom = Money.rupiah(500_000),
        )
        val dimas = summary.members.single { it.memberId == "dimas" }
        assertFalse(dimas.hasEnvelope)
        assertNull(dimas.progressBp)
        assertEquals(RoomStatus.MENUNGGU, dimas.status)
    }

    @Test
    fun `bulan tanpa amplop sama sekali jatuh sebagai hasEnvelope false`() {
        val summary = EnvelopeRules.summarize(
            month = nov,
            entries = emptyList(),
            activeMemberIds = setOf("ayu"),
            spentByMember = mapOf("ayu" to Money.rupiah(100_000)),
            totalSpentRoom = Money.rupiah(100_000),
        )
        assertFalse(summary.hasAnyEnvelope)
        assertFalse(summary.members.single().hasEnvelope)
    }

    @Test
    fun `lewat Rp 1 tetap dihitung lewat (uang, bukan persen dibulatkan)`() {
        val entries = listOf(EnvelopeEntry("ayu", nov, 1_000_000))
        val summary = EnvelopeRules.summarize(
            month = nov,
            entries = entries,
            activeMemberIds = setOf("ayu"),
            spentByMember = mapOf("ayu" to Money.rupiah(1_000_001)),
            totalSpentRoom = Money.rupiah(1_000_001),
        )
        val ayu = summary.members.single()
        assertTrue(ayu.remaining.isNegative)
        assertEquals(RoomStatus.PERLU_PERHATIAN, ayu.status)
    }

    @Test
    fun `tepat 85 persen sudah perlu perhatian`() {
        val entries = listOf(EnvelopeEntry("ayu", nov, 1_000_000))
        val summary = EnvelopeRules.summarize(
            month = nov,
            entries = entries,
            activeMemberIds = setOf("ayu"),
            spentByMember = mapOf("ayu" to Money.rupiah(850_000)),
            totalSpentRoom = Money.rupiah(850_000),
        )
        assertEquals(RoomStatus.PERLU_PERHATIAN, summary.members.single().status)
    }

    @Test
    fun `anggota yang keluar di tengah bulan tetap tampil tapi tidak ikut total amplop ruang`() {
        val entries = listOf(
            EnvelopeEntry("ayu", nov, 4_000_000),
            EnvelopeEntry("dimas", nov, 2_000_000),
        )
        // Dimas sudah keluar sebelum bulan ini dihitung ulang (tidak lagi di activeMemberIds), tapi catatannya bulan
        // ini (sebelum keluar) tetap terhitung di spentByMember (D.3: "catatannya tetap terhitung... untuk bulan itu").
        val summary = EnvelopeRules.summarize(
            month = nov,
            entries = entries,
            activeMemberIds = setOf("ayu"),
            spentByMember = mapOf("ayu" to Money.rupiah(1_000_000), "dimas" to Money.rupiah(300_000)),
            totalSpentRoom = Money.rupiah(1_300_000),
        )
        assertEquals(Money.rupiah(4_000_000), summary.totalEnvelope) // hanya Ayu yang aktif
        assertEquals(2, summary.members.size)
        val dimas = summary.members.single { it.memberId == "dimas" }
        assertEquals(Money.rupiah(300_000), dimas.spent) // riwayatnya tidak hilang
    }

    @Test
    fun `total amplop ruang menjumlah semua anggota aktif, nominal besar dan tidak bulat`() {
        val entries = listOf(
            EnvelopeEntry("ayu", nov, 25_783_000),
            EnvelopeEntry("dimas", nov, 1_234_567),
        )
        val summary = EnvelopeRules.summarize(
            month = nov,
            entries = entries,
            activeMemberIds = setOf("ayu", "dimas"),
            spentByMember = mapOf("ayu" to Money.rupiah(10_000_000), "dimas" to Money.rupiah(1_000_000)),
            totalSpentRoom = Money.rupiah(11_000_000),
        )
        assertEquals(Money.rupiah(27_017_567), summary.totalEnvelope)
        assertTrue(summary.hasAnyEnvelope)
    }
}
