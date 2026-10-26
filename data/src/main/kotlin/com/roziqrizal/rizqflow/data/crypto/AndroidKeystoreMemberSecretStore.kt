package com.roziqrizal.rizqflow.data.crypto

import android.content.Context
import android.util.Log
import com.google.crypto.tink.Aead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeystoreKmsClient
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.sync.MemberSecretStore
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

@Serializable
private data class IssuedSecretRecord(val id: String, val memberId: String?, val secretBase64: String)

/**
 * Penyimpanan `member_secret` Tahap 9 (2026-10-27, lihat KDoc [MemberSecretStore]): rahasia 32 byte
 * per anggota disimpan di SharedPreferences, terbungkus master key Android Keystore BILA Keystore
 * bisa dipakai (sama sikap dengan [AndroidKeystoreAccountKeyProvider]/[AndroidKeystoreRoomKeyProvider]
 * — bila tidak, jatuh ke penulisan cleartext, dicatat lewat [Log.w] dengan nama kelas galat saja,
 * tanpa kunci atau isi). Berbeda dari dua kelas itu: yang dibungkus di sini BUKAN keyset Tink, hanya
 * larik byte buram (`member_secret` itu sendiri, dan daftar JSON kecil untuk sisi pemilik) — jadi
 * dipakai langsung [Aead] Keystore dari [AndroidKeystoreKmsClient.getOrGenerateNewAeadKey], tanpa
 * [com.google.crypto.tink.integration.android.AndroidKeysetManager] (tidak ada rotasi/merge untuk
 * data ini, satu kunci master Keystore per ruang sudah cukup).
 *
 * Satu berkas SharedPreferences dan satu master key per [RoomId] (pola sama dengan
 * [AndroidKeystoreRoomKeyProvider]) — ruang lain tidak pernah berbagi kunci pembungkus ini.
 */
class AndroidKeystoreMemberSecretStore(
    private val context: Context,
    /** Pengambil master key Keystore per URI; bawaan Tink. Hanya diganti di tes untuk meniru Keystore yang gagal. */
    private val masterAeadFor: (String) -> Aead = { AndroidKeystoreKmsClient.getOrGenerateNewAeadKey(it) },
) : MemberSecretStore {

    override fun saveOwnSecret(roomId: RoomId, memberSecret: ByteArray) {
        writeBlob(roomId, KEY_OWN_SECRET, AAD_OWN, memberSecret)
    }

    override fun readOwnSecret(roomId: RoomId): ByteArray? = readBlob(roomId, KEY_OWN_SECRET, AAD_OWN)

    override fun recordIssuedSecret(roomId: RoomId, memberSecret: ByteArray): String {
        val id = UUID.randomUUID().toString()
        val records = readIssuedRecords(roomId).toMutableList()
        records.add(IssuedSecretRecord(id = id, memberId = null, secretBase64 = Base64.getEncoder().encodeToString(memberSecret)))
        writeIssuedRecords(roomId, records)
        return id
    }

    override fun bindIssuedSecretToMember(roomId: RoomId, issuedSecretId: String, memberId: String) {
        val records = readIssuedRecords(roomId).map { if (it.id == issuedSecretId) it.copy(memberId = memberId) else it }
        writeIssuedRecords(roomId, records)
    }

    override fun issuedSecretFor(roomId: RoomId, memberId: String): ByteArray? =
        readIssuedRecords(roomId).lastOrNull { it.memberId == memberId }?.let { Base64.getDecoder().decode(it.secretBase64) }

    private fun readIssuedRecords(roomId: RoomId): List<IssuedSecretRecord> {
        val bytes = readBlob(roomId, KEY_ISSUED_SECRETS, AAD_ISSUED) ?: return emptyList()
        return try {
            json.decodeFromString(ListSerializer(IssuedSecretRecord.serializer()), String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeIssuedRecords(roomId: RoomId, records: List<IssuedSecretRecord>) {
        val encoded = json.encodeToString(ListSerializer(IssuedSecretRecord.serializer()), records)
        writeBlob(roomId, KEY_ISSUED_SECRETS, AAD_ISSUED, encoded.toByteArray(Charsets.UTF_8))
    }

    /** Tulis [plaintext] terbungkus master key Keystore ruang ini; jatuh ke cleartext bila Keystore tidak bisa dipakai. */
    private fun writeBlob(roomId: RoomId, key: String, aad: ByteArray, plaintext: ByteArray) {
        val prefs = context.getSharedPreferences(prefFileName(roomId), Context.MODE_PRIVATE)
        val masterAead = masterAead(roomId)
        val stored = if (masterAead != null) {
            PREFIX_ENCRYPTED + Base64.getEncoder().encodeToString(masterAead.encrypt(plaintext, aad))
        } else {
            PREFIX_CLEARTEXT + Base64.getEncoder().encodeToString(plaintext)
        }
        prefs.edit().putString(key, stored).commit()
    }

    private fun readBlob(roomId: RoomId, key: String, aad: ByteArray): ByteArray? {
        val prefs = context.getSharedPreferences(prefFileName(roomId), Context.MODE_PRIVATE)
        val stored = prefs.getString(key, null) ?: return null
        return try {
            when {
                stored.startsWith(PREFIX_ENCRYPTED) -> {
                    val masterAead = masterAead(roomId) ?: return null
                    masterAead.decrypt(Base64.getDecoder().decode(stored.removePrefix(PREFIX_ENCRYPTED)), aad)
                }
                stored.startsWith(PREFIX_CLEARTEXT) -> Base64.getDecoder().decode(stored.removePrefix(PREFIX_CLEARTEXT))
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun masterAead(roomId: RoomId): Aead? {
        AeadConfig.register()
        return try {
            masterAeadFor(masterKeyUri(roomId))
        } catch (e: GeneralSecurityException) {
            logCleartextFallback(e); null
        } catch (e: ProviderException) {
            logCleartextFallback(e); null
        }
    }

    private fun logCleartextFallback(cause: Exception) {
        // Jujur: member_secret ruang ini tersimpan TANPA pembungkus Android Keystore. Hanya nama kelas galat.
        Log.w(TAG, "Keystore tidak bisa dipakai; member_secret ditulis tanpa pembungkus Keystore (${cause.javaClass.name})")
    }

    private fun prefFileName(roomId: RoomId) = PREF_FILE_NAME + "_" + roomId.value
    private fun masterKeyUri(roomId: RoomId) = MASTER_KEY_URI + "_" + roomId.value

    companion object {
        private const val TAG = "MemberSecretStore"
        private const val PREF_FILE_NAME = "rizqflow_member_secret_crypto"
        private const val MASTER_KEY_URI = "android-keystore://rizqflow_member_secret_master_key"
        private const val KEY_OWN_SECRET = "own_secret"
        private const val KEY_ISSUED_SECRETS = "issued_secrets"
        private const val PREFIX_ENCRYPTED = "enc:"
        private const val PREFIX_CLEARTEXT = "raw:"
        private val AAD_OWN = "rizqflow-member-secret/own".toByteArray(Charsets.UTF_8)
        private val AAD_ISSUED = "rizqflow-member-secret/issued".toByteArray(Charsets.UTF_8)
    }
}
