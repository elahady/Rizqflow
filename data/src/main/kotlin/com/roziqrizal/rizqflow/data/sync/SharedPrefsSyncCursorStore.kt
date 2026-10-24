package com.roziqrizal.rizqflow.data.sync

import android.content.Context
import com.roziqrizal.rizqflow.domain.sync.SyncCursorStore

/** Posisi pull terakhir per ruang (Tahap 9), pola sama dengan `SharedPrefsApiTokenStore`. */
class SharedPrefsSyncCursorStore(context: Context) : SyncCursorStore {
    private val prefs = context.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)

    override fun read(roomId: String): Long = prefs.getLong(key(roomId), 0L)

    override fun save(roomId: String, cursor: Long) {
        prefs.edit().putLong(key(roomId), cursor).apply()
    }

    private fun key(roomId: String) = "cursor_$roomId"

    companion object {
        private const val PREF_FILE_NAME = "rizqflow_sync_cursor"
    }
}
