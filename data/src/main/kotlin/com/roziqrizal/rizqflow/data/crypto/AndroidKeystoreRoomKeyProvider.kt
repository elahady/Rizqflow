package com.roziqrizal.rizqflow.data.crypto

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.LegacyKeysetSerialization
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadKeyTemplates
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.integration.android.AndroidKeystoreKmsClient
import com.google.crypto.tink.integration.android.SharedPrefKeysetWriter
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.sync.RoomKeyProvider
import com.roziqrizal.rizqflow.domain.sync.SyncAead

/**
 * Kunci per ruang Tahap 13 (server-sync.md): satu keyset AES-256-GCM Tink terpisah per [RoomId],
 * masing-masing dibungkus Android Keystore-nya sendiri di SharedPreferences file-nya sendiri —
 * ruang yang berbeda tidak pernah berbagi kunci, jadi membuka satu ruang tidak membuka ruang lain
 * atau kunci akun ([AndroidKeystoreAccountKeyProvider]). Dibuat acak sekali per ruang saat pertama
 * dipanggil, dimuat ulang sama pada pemanggilan berikutnya untuk [RoomId] yang sama.
 * [exportRoomKey]/[importRoomKey] memindahkan kunci ruang ke perangkat pasangan lewat
 * [com.roziqrizal.rizqflow.domain.sync.RoomInvite] (QR, dipindai langsung saat bertemu).
 * [rotateRoomKey] mengeluarkan anggota: menambah kunci baru ke keyset yang sama tanpa membuang
 * kunci lama, supaya data lama tetap terbaca sementara data baru memakai kunci baru.
 */
class AndroidKeystoreRoomKeyProvider(private val context: Context) : RoomKeyProvider {

    private val cachedHandleByRoom = HashMap<RoomId, KeysetHandle>()
    private val cachedAeadByRoom = HashMap<RoomId, SyncAead>()

    override fun getOrCreateRoomKey(roomId: RoomId): SyncAead {
        synchronized(this) {
            cachedAeadByRoom[roomId]?.let { return it }
            val created = TinkSyncAead(keysetHandle(roomId).getPrimitive(RegistryConfiguration.get(), Aead::class.java))
            cachedAeadByRoom[roomId] = created
            return created
        }
    }

    /** Keyset lengkap diserialkan untuk dibungkus [com.roziqrizal.rizqflow.domain.sync.RoomInvite] sebelum dipindai pasangan lewat QR. */
    override fun exportRoomKey(roomId: RoomId): ByteArray =
        synchronized(this) {
            TinkProtoKeysetFormat.serializeKeyset(keysetHandle(roomId), InsecureSecretKeyAccess.get(), RegistryConfiguration.get())
        }

    /**
     * Menulis ulang keyset tersimpan untuk [roomId] dengan kunci dari [com.roziqrizal.rizqflow.domain.sync.RoomInvite]
     * pasangan lewat [SharedPrefKeysetWriter] — jalur tulis publik Tink yang persis sama formatnya
     * dengan yang dibaca [AndroidKeysetManager], supaya pemanggilan [getOrCreateRoomKey] berikutnya
     * (bahkan dari provider/proses baru) memuat kunci yang baru dipasang ini, bukan kunci acak lama.
     * Dibungkus master key Keystore ruang ini sendiri bila tersedia; kalau tidak (mis. perangkat
     * tanpa Android Keystore yang berfungsi, atau shadow Robolectric di tes) jatuh ke penulisan
     * cleartext — persis sikap [AndroidKeysetManager] sendiri saat Keystore tidak bisa dipakai,
     * bukan kripto tambahan yang ditulis sendiri di sini.
     */
    override fun importRoomKey(roomId: RoomId, exportedRoomKey: ByteArray) {
        synchronized(this) {
            AeadConfig.register()
            val importedHandle =
                TinkProtoKeysetFormat.parseKeyset(exportedRoomKey, InsecureSecretKeyAccess.get(), RegistryConfiguration.get())
            val writer = SharedPrefKeysetWriter(context, keysetName(roomId), prefFileName(roomId))
            try {
                val masterAead = AndroidKeystoreKmsClient.getOrGenerateNewAeadKey(masterKeyUri(roomId))
                LegacyKeysetSerialization.serializeEncryptedKeyset(importedHandle, writer, masterAead, ByteArray(0))
            } catch (e: Exception) {
                LegacyKeysetSerialization.serializeKeyset(importedHandle, writer, InsecureSecretKeyAccess.get())
            }
            cachedHandleByRoom[roomId] = importedHandle
            cachedAeadByRoom[roomId] = TinkSyncAead(importedHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java))
        }
    }

    /**
     * Mengeluarkan anggota (server-sync.md bagian "Mengeluarkan anggota"): menambah kunci AES-256-GCM
     * baru ke keyset ruang ini lewat `AndroidKeysetManager.rotate` Tink dan menjadikannya primary
     * (dipakai untuk enkripsi berikutnya) — bukan mengganti seluruh keyset, jadi kunci lama tetap
     * tersimpan di keyset yang sama dan masih bisa mendekripsi ciphertext lama (`Aead` Tink mencoba
     * semua kunci di keyset berdasarkan id yang tertera di awal ciphertext, bukan cuma primary).
     */
    override fun rotateRoomKey(roomId: RoomId): SyncAead {
        synchronized(this) {
            val handle = keysetManager(roomId).rotate(AeadKeyTemplates.AES256_GCM).keysetHandle
            cachedHandleByRoom[roomId] = handle
            val rotated = TinkSyncAead(handle.getPrimitive(RegistryConfiguration.get(), Aead::class.java))
            cachedAeadByRoom[roomId] = rotated
            return rotated
        }
    }

    /** Dipanggil hanya dari dalam blok `synchronized(this)` milik method publik di atas. */
    private fun keysetHandle(roomId: RoomId): KeysetHandle =
        cachedHandleByRoom[roomId] ?: keysetManager(roomId).keysetHandle.also { cachedHandleByRoom[roomId] = it }

    /** Dipanggil hanya dari dalam blok `synchronized(this)` milik method publik di atas. */
    private fun keysetManager(roomId: RoomId): AndroidKeysetManager {
        AeadConfig.register()
        return AndroidKeysetManager.Builder()
            .withSharedPref(context, keysetName(roomId), prefFileName(roomId))
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(masterKeyUri(roomId))
            .build()
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
