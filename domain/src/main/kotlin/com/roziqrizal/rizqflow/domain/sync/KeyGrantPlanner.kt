package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.model.RoomId

/**
 * Hasil perencanaan distribusi kunci setelah mengeluarkan anggota (key_grant langkah 6). [secrets] = rahasia anggota yang
 * tetap dan bisa menerima [KeyGrant] otomatis; [covered] anggotanya; [needsQr] anggota yang tetap tetapi tidak punya rahasia
 * terikat (bergabung dengan QR lama, atau undangannya belum pernah dipakai lewat `join` berundangan) sehingga harus memindai
 * QR baru dari pemilik.
 */
class KeyGrantPlan(val secrets: List<ByteArray>, val covered: List<RoomMemberInfo>, val needsQr: List<RoomMemberInfo>)

/**
 * Menentukan siapa menerima kunci baru otomatis dan siapa masih butuh QR. Murni: hanya membaca [MemberSecretStore].
 * [remaining] = anggota yang TETAP, tanpa pemilik sendiri dan tanpa yang baru dikeluarkan.
 */
object KeyGrantPlanner {
    fun plan(roomId: RoomId, remaining: List<RoomMemberInfo>, store: MemberSecretStore): KeyGrantPlan {
        val secrets = mutableListOf<ByteArray>()
        val covered = mutableListOf<RoomMemberInfo>()
        val needsQr = mutableListOf<RoomMemberInfo>()
        for (member in remaining) {
            val secret = member.memberId?.let { store.issuedSecretFor(roomId, it) }
            if (secret == null) {
                needsQr += member
            } else {
                secrets += secret
                covered += member
            }
        }
        return KeyGrantPlan(secrets, covered, needsQr)
    }
}
