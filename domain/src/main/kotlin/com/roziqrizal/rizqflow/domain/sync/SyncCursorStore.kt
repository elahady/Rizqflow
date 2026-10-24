package com.roziqrizal.rizqflow.domain.sync

/** Posisi pull terakhir per ruang (Tahap 9). Kontrak murni; implementasi di `:data` (SharedPreferences). */
interface SyncCursorStore {
    fun read(roomId: String): Long

    fun save(roomId: String, cursor: Long)
}
