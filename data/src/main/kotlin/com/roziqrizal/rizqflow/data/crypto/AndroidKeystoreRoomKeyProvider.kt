package com.roziqrizal.rizqflow.data.crypto

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.sync.RoomKeyProvider
import com.roziqrizal.rizqflow.domain.sync.SyncAead

/**
 * Kunci per ruang Tahap 13 (server-sync.md): satu keyset AES-256-GCM Tink terpisah per [RoomId],
 * masing-masing dibungkus Android Keystore-nya sendiri di SharedPreferences file-nya sendiri —
 * ruang yang berbeda tidak pernah berbagi kunci, jadi membuka satu ruang tidak membuka ruang lain
 * atau kunci akun ([AndroidKeystoreAccountKeyProvider]). Dibuat acak sekali per ruang saat pertama
 * dipanggil, dimuat ulang sama pada pemanggilan berikutnya untuk [RoomId] yang sama.
 */
class AndroidKeystoreRoomKeyProvider(private val context: Context) : RoomKeyProvider {

    private val cachedAeadByRoom = HashMap<RoomId, SyncAead>()

    override fun getOrCreateRoomKey(roomId: RoomId): SyncAead {
        synchronized(this) {
            cachedAeadByRoom[roomId]?.let { return it }
            AeadConfig.register()
            val handle = AndroidKeysetManager.Builder()
                .withSharedPref(context, keysetName(roomId), prefFileName(roomId))
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri(masterKeyUri(roomId))
                .build()
                .keysetHandle
            val created = TinkSyncAead(handle.getPrimitive(RegistryConfiguration.get(), Aead::class.java))
            cachedAeadByRoom[roomId] = created
            return created
        }
    }

    private fun keysetName(roomId: RoomId) = "rizqflow_room_keyset_${roomId.value}"
    private fun prefFileName(roomId: RoomId) = "rizqflow_room_crypto_${roomId.value}"
    private fun masterKeyUri(roomId: RoomId) = "android-keystore://rizqflow_room_master_key_${roomId.value}"

    private class TinkSyncAead(private val aead: Aead) : SyncAead {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
            aead.encrypt(plaintext, associatedData)

        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray =
            aead.decrypt(ciphertext, associatedData)
    }
}
