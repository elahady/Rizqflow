package com.roziqrizal.rizqflow.data.sync

import android.content.Context
import com.roziqrizal.rizqflow.domain.sync.AutoSyncSettings

/**
 * Pengaturan sync otomatis per PERANGKAT (bukan per akun: berbayar atau tidaknya jaringan adalah soal ponsel ini).
 * Pola sama dengan [SharedPrefsSyncCursorStore].
 *
 * [wifiOnly] bawaan AKTIF: usulan Claude (belum dikonfirmasi pemilik) memilih default paling hemat kuota; yang
 * tertunda hanya tersalinnya data ke server, bukan datanya.
 */
class SharedPrefsAutoSyncSettings(context: Context) : AutoSyncSettings {
    private val prefs = context.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)

    override var wifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, DEFAULT_WIFI_ONLY)
        set(value) {
            prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()
        }

    companion object {
        const val DEFAULT_WIFI_ONLY = true
        private const val PREF_FILE_NAME = "rizqflow_auto_sync"
        private const val KEY_WIFI_ONLY = "wifi_only"
    }
}
