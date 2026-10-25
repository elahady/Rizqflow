package com.roziqrizal.rizqflow.data.sync

import android.content.Context
import android.content.SharedPreferences
import com.roziqrizal.rizqflow.domain.auth.AccountStorage
import com.roziqrizal.rizqflow.domain.sync.RecoveryStatus
import com.roziqrizal.rizqflow.domain.sync.RecoveryStatusStore
import java.time.Instant

/**
 * Penanda NON-RAHASIA "kode pemulihan sudah diatur" per akun (Tahap 13) di SharedPreferences biasa. Hanya
 * status, waktu, dan `updated_at` salinan di server (teks dari server); kode 24 kata, kata apa pun, dan kunci tidak pernah lewat sini (kontraknya
 * [RecoveryStatusStore] tidak punya tempat untuk itu). Satu berkas per akun, dinamai dengan hash pengenal
 * akun seperti `SharedPrefsApiTokenStore`.
 */
class SharedPrefsRecoveryStatusStore(context: Context, accountId: String) : RecoveryStatusStore {
    private val prefs = context.getSharedPreferences(PREF_FILE_PREFIX + AccountStorage.scopeKey(accountId), Context.MODE_PRIVATE)

    override fun read(): RecoveryStatus = when (prefs.getString(KEY_STATE, null)) {
        STATE_CONFIGURED -> RecoveryStatus.Configured(
            Instant.ofEpochMilli(prefs.getLong(KEY_CONFIGURED_AT_MS, 0L)),
            prefs.getString(KEY_SERVER_UPDATED_AT, null),
        )
        STATE_PENDING -> RecoveryStatus.PendingConfirmation(prefs.getString(KEY_SERVER_UPDATED_AT, null))
        else -> RecoveryStatus.NotConfigured
    }

    // commit() sinkron: penanda ditulis tepat sebelum unggahan dikirim dan sebelum kata ditampilkan, tidak boleh
    // hilang bila proses langsung mati. Karena sinkron (menyentuh disk), panggil dari thread latar.
    override fun markPending(serverUpdatedAt: String?) {
        prefs.edit()
            .putString(KEY_STATE, STATE_PENDING)
            .remove(KEY_CONFIGURED_AT_MS)
            .putOrRemove(KEY_SERVER_UPDATED_AT, serverUpdatedAt)
            .commit()
    }

    override fun markConfigured(at: Instant, serverUpdatedAt: String?) {
        prefs.edit()
            .putString(KEY_STATE, STATE_CONFIGURED)
            .putLong(KEY_CONFIGURED_AT_MS, at.toEpochMilli())
            .putOrRemove(KEY_SERVER_UPDATED_AT, serverUpdatedAt)
            .commit()
    }

    override fun clear() {
        prefs.edit().clear().commit()
    }

    private fun SharedPreferences.Editor.putOrRemove(key: String, value: String?): SharedPreferences.Editor =
        if (value == null) remove(key) else putString(key, value)

    companion object {
        private const val PREF_FILE_PREFIX = "rizqflow_recovery_status_"
        private const val KEY_STATE = "state"
        private const val KEY_CONFIGURED_AT_MS = "configured_at_ms"
        private const val KEY_SERVER_UPDATED_AT = "server_updated_at"
        private const val STATE_PENDING = "pending"
        private const val STATE_CONFIGURED = "configured"
    }
}
