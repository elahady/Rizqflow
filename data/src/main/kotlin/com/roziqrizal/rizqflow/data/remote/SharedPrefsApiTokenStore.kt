package com.roziqrizal.rizqflow.data.remote

import android.content.Context
import com.roziqrizal.rizqflow.domain.auth.AccountStorage
import com.roziqrizal.rizqflow.domain.sync.ApiTokenStore

/**
 * Token API Sanctum di SharedPreferences biasa, mengikuti pola PurchaseStore/ThemePreference di
 * `:app` — bukan Keystore, karena ini token bisa-dicabut (bukan kunci enkripsi jangka panjang
 * seperti Tahap 13), dan pertahanan sebenarnya ada di server (cabut token), bukan sembunyikan di
 * perangkat (lihat catatan "Konsekuensi yang diterima" di [ApiTokenStore]).
 *
 * Satu berkas per akun ([accountId]), bukan satu berkas untuk seluruh aplikasi: token adalah milik
 * akun server yang masuk, jadi akun Google lain di perangkat yang sama tidak pernah membaca token
 * akun sebelumnya (yang tidak dibersihkan saat Keluar). Tanpa ini, klaim pembelian, sync, dan Hapus
 * akun milik akun baru bisa jalan atas nama akun lama. Nama berkas memakai hash pengenal akun, sama
 * dengan nama database akun (lihat [AccountStorage]).
 */
class SharedPrefsApiTokenStore(context: Context, accountId: String) : ApiTokenStore {
    private val prefs = context.getSharedPreferences(
        PREF_FILE_PREFIX + AccountStorage.scopeKey(accountId),
        Context.MODE_PRIVATE,
    )

    init {
        // Berkas lama satu-untuk-semua-akun (versi sebelum token dipisah per akun): pemiliknya tidak
        // diketahui, jadi tidak dipakai dan dibuang. Akibatnya akun yang sudah masuk lebih dulu
        // memperoleh token server baru pada masuk dengan Google berikutnya.
        context.getSharedPreferences(LEGACY_PREF_FILE_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }

    override fun read(): String? = prefs.getString(KEY_TOKEN, null)

    override fun save(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    companion object {
        private const val PREF_FILE_PREFIX = "rizqflow_api_token_"
        private const val LEGACY_PREF_FILE_NAME = "rizqflow_api_token"
        private const val KEY_TOKEN = "token"
    }
}
