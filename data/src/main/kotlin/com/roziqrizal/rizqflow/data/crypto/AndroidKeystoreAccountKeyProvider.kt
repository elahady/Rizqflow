package com.roziqrizal.rizqflow.data.crypto

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.roziqrizal.rizqflow.domain.sync.AccountKeyProvider
import com.roziqrizal.rizqflow.domain.sync.SyncAead

/**
 * Kunci akun Tahap 13 (server-sync.md): dibuat sekali secara acak (AES-256-GCM lewat Google Tink,
 * "tidak menulis kripto sendiri") dan disimpan terbungkus Android Keystore di SharedPreferences —
 * `AndroidKeysetManager` sendiri yang membuatnya saat pertama dipanggil bila belum ada, lalu memuat
 * ulang keyset yang sama pada pemanggilan berikutnya. Kunci akun tidak pernah berupa byte mentah
 * yang terlihat kode ini; hanya primitif [Aead] yang dipakai untuk enkripsi dan dekripsi.
 */
class AndroidKeystoreAccountKeyProvider(private val context: Context) : AccountKeyProvider {

    @Volatile
    private var cached: SyncAead? = null

    override fun getOrCreateAccountKey(): SyncAead {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            AeadConfig.register()
            val keysetHandle = AndroidKeysetManager.Builder()
                .withSharedPref(context, KEYSET_NAME, PREF_FILE_NAME)
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
                .keysetHandle
            val created = TinkSyncAead(keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java))
            cached = created
            return created
        }
    }

    private class TinkSyncAead(private val aead: Aead) : SyncAead {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
            aead.encrypt(plaintext, associatedData)

        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray =
            aead.decrypt(ciphertext, associatedData)
    }

    companion object {
        private const val KEYSET_NAME = "rizqflow_account_keyset"
        private const val PREF_FILE_NAME = "rizqflow_account_crypto"
        private const val MASTER_KEY_URI = "android-keystore://rizqflow_account_master_key"
    }
}
