package com.roziqrizal.rizqflow.data.remote

import android.content.Context
import com.roziqrizal.rizqflow.domain.sync.ApiTokenStore

/**
 * Token API Sanctum di SharedPreferences biasa, mengikuti pola PurchaseStore/ThemePreference di
 * `:app` — bukan Keystore, karena ini token bisa-dicabut (bukan kunci enkripsi jangka panjang
 * seperti Tahap 13), dan pertahanan sebenarnya ada di server (cabut token), bukan sembunyikan di
 * perangkat (lihat catatan "Konsekuensi yang diterima" di [ApiTokenStore]).
 */
class SharedPrefsApiTokenStore(context: Context) : ApiTokenStore {
    private val prefs = context.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(KEY_TOKEN, null)

    override fun save(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    companion object {
        private const val PREF_FILE_NAME = "rizqflow_api_token"
        private const val KEY_TOKEN = "token"
    }
}
