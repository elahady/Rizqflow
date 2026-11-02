package com.roziqrizal.rizqflow.domain.ledger

import com.roziqrizal.rizqflow.domain.model.RoomKind
import com.roziqrizal.rizqflow.domain.money.Money

/**
 * Hitungan amplop bulanan (peran-anggota.md D.3, H langkah 8): jatah dan terpakai PER ANGGOTA di satu ruang
 * bersama pada satu bulan. Memakai ulang [RoomStatusRules.progressBp]/[RoomStatusRules.statusOf] dan ambang
 * [RoomStatusRules.WARN_AT_BP] (85%) — TIDAK ada ambang baru di sini (keputusan I-6).
 *
 * "Terpakai ruang" (termasuk baris `SHARED`) dan "terpakai per anggota" BUKAN logika baru di sini: keduanya sudah
 * ada lewat `TransactionRepository.roomTotals`/[roomMemberBreakdown] (D.3: "query sudah tidak menyaring origin,
 * tinggal tes pengunci"). Objek ini murni menambahkan lapisan "amplop efektif bulan ini" ([effectiveAmount]) di
 * atas angka yang sudah ada, lalu memakai ulang [RoomStatusRules] untuk status/ambangnya.
 */
object EnvelopeRules {
    /** Sama dengan [RoomStatusRules.WARN_AT_BP]; diekspos lagi di sini supaya pemanggil tidak perlu tahu kelas lain. */
    const val WARN_AT_BP = RoomStatusRules.WARN_AT_BP

    /**
     * Amplop efektif anggota [memberId] pada bulan [month] (`yyyymm`, D.2): rekaman dengan `fromMonth` TERBESAR
     * yang `<= month`. `0` bila tidak ada rekaman sama sekali ATAU rekaman terakhir itu sendiri `amount = 0`
     * (D.2: "`amount = 0` = amplop dihapus mulai bulan itu") — dua kasus ini SENGAJA tidak dibedakan di sini,
     * karena bagi [summarize] keduanya berarti sama: anggota ini tidak punya amplop pada [month].
     */
    fun effectiveAmount(entries: List<EnvelopeEntry>, memberId: String, month: Int): Long =
        entries.asSequence()
            .filter { it.memberId == memberId && it.fromMonth <= month }
            .maxByOrNull { it.fromMonth }
            ?.amount ?: 0L

    /** Jatah, terpakai, sisa, dan status amplop satu anggota pada satu bulan. [remaining] boleh negatif ("lewat Rp X"). */
    data class MemberStatus(
        val memberId: String,
        val envelope: Money,
        val spent: Money,
        /** False bila anggota ini tidak punya amplop aktif bulan ini (tidak pernah diatur, atau `amount = 0` sejak sebelum/pada bulan ini). */
        val hasEnvelope: Boolean,
        /** Null bila [hasEnvelope] false (tidak ada yang bisa dibandingkan). Bisa lebih dari 10.000 (100%). */
        val progressBp: Int?,
        val status: RoomStatus,
    ) {
        val remaining: Money get() = envelope - spent
    }

    /** Ringkasan amplop satu ruang bersama pada satu bulan — dasar bagian "Amplop &lt;bulan&gt;" (F) dan baris "Amplopmu" (S11). */
    data class RoomSummary(
        val month: Int,
        val totalEnvelope: Money,
        /** True bila ADA setidaknya satu anggota aktif dengan amplop > 0 bulan ini (D.5: dasar "jatah ruang = total amplop bila ada"). */
        val hasAnyEnvelope: Boolean,
        val totalSpentRoom: Money,
        /** Satu baris per anggota yang MUNCUL di [activeMemberIds] atau [spentByMember] (anggota yang keluar di tengah bulan tetap tampil, D.3). */
        val members: List<MemberStatus>,
    )

    /**
     * [activeMemberIds]: anggota yang ikut DIHITUNG ke [RoomSummary.totalEnvelope] (D.3: "jumlah amplop semua
     * anggota AKTIF pada bulan B") — pemanggil yang menentukan keanggotaan aktifnya (dari cache `shared_member`),
     * [EnvelopeRules] sendiri tidak tahu status anggota. Anggota yang tidak lagi di [activeMemberIds] tapi masih
     * punya baris di [spentByMember] (keluar di TENGAH bulan ini, catatannya tetap terhitung) tetap muncul di
     * [RoomSummary.members] dengan amplop efektifnya sendiri, hanya tidak ikut [RoomSummary.totalEnvelope].
     *
     * [spentByMember]: terpakai per anggota (lihat [roomMemberBreakdown] — "saya" biasanya punya kunci sendiri
     * yang DISEPAKATI pemanggil sama dengan salah satu id di [activeMemberIds], lihat KDoc [SharedMemberCache.isMe]
     * di `:domain/sync`). [totalSpentRoom] dari `TransactionRepository.roomTotals` (sudah termasuk baris `SHARED`).
     */
    fun summarize(
        month: Int,
        entries: List<EnvelopeEntry>,
        activeMemberIds: Set<String>,
        spentByMember: Map<String, Money>,
        totalSpentRoom: Money,
        roomKind: RoomKind = RoomKind.MENCUKUPI,
        monthOver: Boolean = false,
    ): RoomSummary {
        val currency = totalSpentRoom.currency
        val allMemberIds = activeMemberIds + spentByMember.keys
        val members = allMemberIds.map { id ->
            val amount = effectiveAmount(entries, id, month)
            val envelope = Money(amount, currency)
            val spent = spentByMember[id] ?: Money.zero(currency)
            val hasEnvelope = id in activeMemberIds && envelope.isPositive
            val progress = if (hasEnvelope) RoomStatusRules.progressBp(envelope, spent) else null
            val status = if (hasEnvelope) {
                RoomStatusRules.statusOf(roomKind, envelope, spent, monthOver)
            } else {
                RoomStatus.MENUNGGU
            }
            MemberStatus(id, envelope, spent, hasEnvelope, progress, status)
        }.sortedByDescending { it.spent.minor }
        val total = activeMemberIds.sumOf { effectiveAmount(entries, it, month) }
        return RoomSummary(month, Money(total, currency), total > 0, totalSpentRoom, members)
    }
}
