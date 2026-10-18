package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.model.RoomId

/**
 * Kunci per ruang yang dibagi (Tahap 13, server-sync.md bagian "Model kunci"): setiap ruang
 * Keluarga yang disinkronkan punya kunci acaknya sendiri, terpisah dari kunci akun
 * ([AccountKeyProvider]) dan dari ruang lain — supaya ruang pribadi (Memberi, Diri, ruang
 * Keluarga yang belum pernah dibagi) tidak ikut terbuka saat satu ruang dibagi ke pasangan, dan
 * membuka satu ruang tidak membuka ruang lain. Kontrak murni di `:domain`, implementasi Keystore
 * sungguhan ada di `:data`, mengikuti pola [AccountKeyProvider].
 */
interface RoomKeyProvider {
    /** Membuat kunci ruang bila belum ada, atau memuat yang sudah tersimpan untuk [roomId]. */
    fun getOrCreateRoomKey(roomId: RoomId): SyncAead

    /**
     * Bentuk kunci ruang yang bisa diekspor, dibungkus [RoomInvite] lalu ditampilkan sebagai QR
     * untuk dipindai pasangan — isinya buram bagi `:domain` (format sungguhan ditentukan
     * implementasi di `:data`), sama seperti [AccountKeyProvider.exportAccountKeyForRecovery].
     */
    fun exportRoomKey(roomId: RoomId): ByteArray

    /**
     * Memasang kunci ruang yang diterima dari [RoomInvite] pasangan (hasil pemindaian QR),
     * menimpa kunci acak yang mungkin sudah dibuat sendiri untuk [roomId] — supaya kedua
     * perangkat memakai kunci ruang yang sama persis, bukan dua kunci berbeda yang tidak
     * bisa membuka ciphertext satu sama lain.
     */
    fun importRoomKey(roomId: RoomId, exportedRoomKey: ByteArray)
}
