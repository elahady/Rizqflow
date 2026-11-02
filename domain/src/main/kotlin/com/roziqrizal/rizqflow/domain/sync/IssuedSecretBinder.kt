package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.model.RoomId

/**
 * Mengikat rahasia undangan yang diterbitkan pemilik ke anggota yang memakainya (key_grant langkah 2).
 * Server mencatat `invite_hash` undangan yang dipakai tiap anggota (`GET members` memuatnya bersama
 * `member_id`); pemilik menurunkan hash yang sama dari rahasia yang ia simpan ([MemberSecretKdf.inviteHash])
 * dan mengikatnya lewat [MemberSecretStore.bindIssuedSecretToMember]. Setelah terikat, [MemberSecretStore.issuedSecretFor]
 * bisa menjawab "rahasia milik anggota X" saat kunci ruang dirotasi dan `key_grant` perlu dibungkus untuknya.
 *
 * Murni dan idempoten: aman dipanggil setiap daftar anggota dimuat; rahasia yang sudah terikat tidak diubah.
 */
class IssuedSecretBinder(private val store: MemberSecretStore) {
    /** @return jumlah rahasia yang baru terikat pada panggilan ini. */
    fun bind(roomId: RoomId, members: List<RoomMemberInfo>): Int {
        val membersByHash = members
            .filter { it.inviteHash != null && it.memberId != null }
            .associate { it.inviteHash!!.lowercase() to it.memberId!! }
        if (membersByHash.isEmpty()) return 0

        var bound = 0
        for (issued in store.issuedSecrets(roomId)) {
            if (issued.memberId != null) continue
            val memberId = membersByHash[MemberSecretKdf.inviteHash(issued.secret)] ?: continue
            store.bindIssuedSecretToMember(roomId, issued.id, memberId)
            bound++
        }
        return bound
    }
}
