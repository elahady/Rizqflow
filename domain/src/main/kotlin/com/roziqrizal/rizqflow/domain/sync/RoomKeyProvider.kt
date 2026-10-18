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
}
