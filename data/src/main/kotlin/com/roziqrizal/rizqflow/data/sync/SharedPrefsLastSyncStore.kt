package com.roziqrizal.rizqflow.data.sync

import android.content.Context
import com.roziqrizal.rizqflow.domain.auth.AccountStorage
import com.roziqrizal.rizqflow.domain.sync.LastSyncStore

/**
 * Waktu sinkron berhasil terakhir, satu berkas per akun (ruang kerja) seperti [SharedPrefsRecoveryStatusStore], jadi akun
 * lain di perangkat yang sama tidak saling menimpa. Hanya satu angka jam dinding untuk TAMPILAN ("Terakhir sinkron ..."):
 * tidak memuat data keuangan, kunci, atau isi apa pun. Bukan jam yang dipakai menentukan urutan data.
 *
 * Tidak pernah melempar: nilai bertipe lain atau tidak masuk akal dibaca sebagai "belum pernah". Kebijakan nilai dari
 * masa depan (jam dimundurkan) ada di `sanitizeLastSync`, dipakai pemanggil.
 */
class SharedPrefsLastSyncStore(context: Context, accountId: String) : LastSyncStore {
    private val prefs = context.getSharedPreferences(PREF_FILE_PREFIX + AccountStorage.scopeKey(accountId), Context.MODE_PRIVATE)

    override fun read(): Long? = try {
        if (prefs.contains(KEY_LAST_SYNCED_AT_MS)) prefs.getLong(KEY_LAST_SYNCED_AT_MS, 0L).takeIf { it > 0L } else null
    } catch (_: ClassCastException) {
        null
    }

    override fun write(epochMs: Long) {
        prefs.edit().putLong(KEY_LAST_SYNCED_AT_MS, epochMs).apply()
    }

    companion object {
        private const val PREF_FILE_PREFIX = "rizqflow_auto_sync_last_"
        private const val KEY_LAST_SYNCED_AT_MS = "last_synced_at_ms"
    }
}
