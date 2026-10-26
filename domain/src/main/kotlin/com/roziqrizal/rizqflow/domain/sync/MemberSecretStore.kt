package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.model.RoomId

/**
 * Penyimpanan lokal `member_secret` (Tahap 9, 2026-10-27; sync-desain.md bagian F "Rotasi dan
 * keluarkan anggota ke anggota yang tetap", keputusan pemilik #9 — opsi 3 "rahasia pasangan dari
 * QR"): rahasia [MEMBER_SECRET_LENGTH] byte per [RoomInvite] v3, calon kunci AEAD `key_grant` saat
 * rotasi. Kontrak murni di `:domain`, implementasi Keystore sungguhan di `:data`, mengikuti pola
 * [RoomKeyProvider]/[AccountKeyProvider].
 *
 * **Penerbitan dan pengambilan `key_grant` lewat sync BELUM diimplementasikan di sini** (lihat KDoc
 * [RoomMemberKeyRedistribution] dan roadmap.md Tahap 9) — antarmuka ini HANYA menyimpan rahasianya
 * secara lokal, tidak pernah mengirim apa pun ke jaringan.
 *
 * Dua peran berbeda, bisa dipakai untuk [RoomId] yang sama (pemilik satu ruang bisa jadi anggota
 * biasa di ruang lain):
 *
 * - **Anggota**: menerima SATU [RoomInvite.memberSecret] miliknya sendiri saat bergabung, disimpan
 *   lewat [saveOwnSecret] dan dibaca lagi lewat [readOwnSecret] saat nanti membongkar `key_grant`.
 * - **Pemilik**: menyimpan SETIAP `member_secret` yang pernah dibuatnya lewat [RoomInvite.create]
 *   — satu per undangan yang ditampilkan sebagai QR, lewat [recordIssuedSecret].
 *
 * **Keputusan desain (tidak ada di sync-desain.md, diambil konsisten dengan alur join yang sudah
 * ada, `joinSharedRoom` + `RoomMembershipController::join` di server):** identitas anggota yang
 * akan memindai undangan belum diketahui PEMILIK pada saat undangan dibuat — anggota bergabung
 * lewat pindai QR lalu push ke server, bukan dipilih pemilik lebih dulu di aplikasi. Jadi
 * [recordIssuedSecret] TIDAK menerima `memberId` sama sekali (belum ada saat dipanggil); baris yang
 * tersimpan baru punya `memberId` setelah dikaitkan lewat [bindIssuedSecretToMember] — pemanggil
 * langkah itu (cara pemilik tahu "anggota X memindai undangan mana") BELUM ada/didesain, jadi
 * [bindIssuedSecretToMember] dan [issuedSecretFor] disediakan sebagai primitif penyimpanan saja,
 * belum disambungkan ke alur apa pun.
 */
interface MemberSecretStore {
    /** Simpan `member_secret` milik ANGGOTA ini sendiri untuk [roomId], menimpa yang lama bila ada (join ulang). */
    fun saveOwnSecret(roomId: RoomId, memberSecret: ByteArray)

    /** @return `member_secret` milik anggota ini sendiri untuk [roomId], atau null bila belum pernah join lewat undangan v3. */
    fun readOwnSecret(roomId: RoomId): ByteArray?

    /**
     * PEMILIK: catat satu `member_secret` yang baru diterbitkan untuk [roomId] (dipanggil tepat
     * setelah [RoomInvite.create], sebelum tahu siapa yang akan memindainya).
     *
     * @return id lokal baris ini untuk dipakai [bindIssuedSecretToMember] nanti.
     */
    fun recordIssuedSecret(roomId: RoomId, memberSecret: ByteArray): String

    /** PEMILIK: kaitkan baris [issuedSecretId] (hasil [recordIssuedSecret]) ke [memberId] yang sebenarnya memindainya. */
    fun bindIssuedSecretToMember(roomId: RoomId, issuedSecretId: String, memberId: String)

    /** PEMILIK: `member_secret` yang sudah terkait ke [memberId] (lihat [bindIssuedSecretToMember]); null bila belum/tidak ada. */
    fun issuedSecretFor(roomId: RoomId, memberId: String): ByteArray?
}
