package com.roziqrizal.rizqflow.data.crypto

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.roziqrizal.rizqflow.domain.sync.AccountKeyProvider
import com.roziqrizal.rizqflow.domain.sync.SyncAead

/**
 * Kunci akun Tahap 13 (server-sync.md): dibuat sekali secara acak (AES-256-GCM lewat Google Tink,
 * "tidak menulis kripto sendiri") dan disimpan terbungkus Android Keystore di SharedPreferences —
 * `AndroidKeysetManager` sendiri yang membuatnya saat pertama dipanggil bila belum ada, lalu memuat
 * ulang keyset yang sama pada pemanggilan berikutnya. Untuk pemakaian sehari-hari hanya primitif
 * [Aead] yang dipakai, tidak pernah byte kunci mentah. [exportAccountKeyForRecovery] sengaja jadi
 * satu-satunya jalan keluar byte keyset-nya, dan hanya dipanggil sesaat untuk dibungkus kode
 * pemulihan sebelum dikirim ke server (lihat [com.roziqrizal.rizqflow.domain.sync.AccountKeyRecoveryCrypto]).
 */
class AndroidKeystoreAccountKeyProvider(private val context: Context) : AccountKeyProvider {

    @Volatile
    private var cachedHandle: KeysetHandle? = null

    @Volatile
    private var cachedAead: SyncAead? = null

    override fun getOrCreateAccountKey(): SyncAead {
        cachedAead?.let { return it }
        synchronized(this) {
            cachedAead?.let { return it }
            val handle = keysetHandle()
            val created = TinkSyncAead(handle.getPrimitive(RegistryConfiguration.get(), Aead::class.java))
            cachedAead = created
            return created
        }
    }

    /** Keyset lengkap (bukan hanya kunci mentah) diserialkan sekali untuk dibungkus [com.roziqrizal.rizqflow.domain.sync.AccountKeyRecoveryCrypto]. */
    override fun exportAccountKeyForRecovery(): ByteArray =
        TinkProtoKeysetFormat.serializeKeyset(keysetHandle(), InsecureSecretKeyAccess.get(), RegistryConfiguration.get())

    private fun keysetHandle(): KeysetHandle {
        cachedHandle?.let { return it }
        synchronized(this) {
            cachedHandle?.let { return it }
            AeadConfig.register()
            val handle = AndroidKeysetManager.Builder()
                .withSharedPref(context, KEYSET_NAME, PREF_FILE_NAME)
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
                .keysetHandle
            cachedHandle = handle
            return handle
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
