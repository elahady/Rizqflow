package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.model.RoomId

/**
 * Keluarkan anggota ruang (Tahap 9, roadmap.md "Keluarkan anggota: kirim keyset baru ke anggota
 * yang tetap"; server-sync.md bagian "Model kunci" baris "Mengeluarkan anggota"). Rotasi kunci itu
 * sendiri sudah ada sejak Tahap 13 ([RoomKeyProvider.rotateRoomKey], teruji di
 * `AndroidKeystoreRoomKeyProviderTest`); yang belum ada dan ditambahkan di sini HANYA jalur
 * MENYIAPKAN kunci yang sudah dirotasi itu untuk dibagikan ulang ke anggota yang TETAP — lewat
 * kanal yang SAMA dipakai saat mengundang pasangan pertama kali ([RoomInvite] + QR, "bertemu
 * langsung dan memindai QR untuk memverifikasi kunci, tidak bergantung pada server" —
 * server-sync.md bagian "Mengundang pasangan"). Tidak ada primitif kripto baru di sini sama sekali.
 *
 * Anggota yang dikeluarkan sengaja TIDAK PERNAH menerima [RoomInvite] hasil fungsi ini (mereka
 * tidak diajak memindai QR baru), jadi tidak pernah punya kunci untuk transaksi berikutnya —
 * sementara data lama yang sudah mereka tarik sebelum dikeluarkan tetap bisa mereka baca, sesuai
 * tabel "Model kunci" di server-sync.md ("Data lama yang sudah mereka miliki tetap bisa mereka
 * baca dan ini dijelaskan ke pengguna"). UI yang memanggil ini WAJIB menjelaskan itu dengan jujur,
 * bukan berpura-pura datanya ikut terhapus atau anggota itu langsung kehilangan akses ke riwayat.
 *
 * **Di luar cakupan — keputusan pemilik belum diambil (sync-desain.md bagian J, #1 dan #9):**
 * - SIAPA yang boleh memicu rotasi ini (model kepemilikan ruang) dan bagaimana anggota yang
 *   dikeluarkan benar-benar dicabut dari `room_members` di server — server saat ini tidak punya
 *   kolom peran/pemilik maupun endpoint hapus anggota (lihat sync-desain.md bagian G/0.7).
 *   Fungsi ini TIDAK mengubah keanggotaan di server sama sekali, hanya kunci lokal.
 * - Saluran distribusi otomatis lewat server (`member_secret` dari QR + rekaman `key_grant`,
 *   opsi 3 di sync-desain.md bagian F) BELUM diimplementasikan karena belum dikonfirmasi pemilik;
 *   "QR ulang" di sini adalah opsi cadangan yang sudah disetujui sebagai bagian dari mekanisme
 *   undangan Tahap 13 yang sudah ada, bukan desain baru.
 */
class RoomMemberKeyRedistribution(private val roomKeys: RoomKeyProvider) {
    /**
     * Rotasi kunci ruang [roomId] untuk data berikutnya, lalu kembalikan [RoomInvite] berisi kunci
     * yang SUDAH dirotasi — siap ditampilkan sebagai QR baru untuk dipindai ulang oleh anggota yang
     * tetap, dengan format dan alur pemindaian yang sama persis dengan undangan pertama kali.
     */
    fun rotateAndPrepareInvite(roomId: RoomId, roomName: String): RoomInvite {
        roomKeys.rotateRoomKey(roomId)
        return RoomInvite(roomId, roomName, roomKeys.exportRoomKey(roomId))
    }
}
