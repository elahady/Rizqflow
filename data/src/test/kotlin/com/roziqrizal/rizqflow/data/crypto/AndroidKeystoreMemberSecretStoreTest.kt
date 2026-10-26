package com.roziqrizal.rizqflow.data.crypto

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.roziqrizal.rizqflow.domain.model.RoomId
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Penyimpanan lokal `member_secret` Tahap 9 (2026-10-27). Pola tes sama dengan
 * `AndroidKeystoreAccountKeyImportTest`: di Robolectric Keystore asli tidak ada, jadi tes "biasa"
 * (tanpa `masterAeadFor` kustom) membuktikan logika penyimpanan/pembacaan lewat jalur fallback
 * cleartext, BUKAN pembungkusan Android Keystore sungguhan; jalur terbungkus dibuktikan terpisah
 * lewat Aead Tink murni (tanpa Keystore) yang disuntikkan sebagai `masterAeadFor`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidKeystoreMemberSecretStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun newStore(masterAeadFor: ((String) -> Aead)? = null): AndroidKeystoreMemberSecretStore =
        if (masterAeadFor == null) AndroidKeystoreMemberSecretStore(context) else AndroidKeystoreMemberSecretStore(context, masterAeadFor)

    /** Aead Tink murni (tanpa Android Keystore) hanya untuk menguji jalur TERBUNGKUS di JVM biasa. */
    private fun fakeWorkingMasterAead(): Aead {
        AeadConfig.register()
        return KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM")).getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    // --- Anggota: member_secret miliknya sendiri ---

    @Test
    fun `member menyimpan dan membaca kembali member_secret miliknya sendiri`() {
        val store = newStore()
        val roomId = RoomId("ruang-1")
        val secret = ByteArray(32) { it.toByte() }

        store.saveOwnSecret(roomId, secret)

        assertContentEquals(secret, store.readOwnSecret(roomId))
    }

    @Test
    fun `belum pernah join, readOwnSecret null`() {
        assertNull(newStore().readOwnSecret(RoomId("ruang-belum-join")))
    }

    @Test
    fun `join ulang menimpa member_secret lama`() {
        val store = newStore()
        val roomId = RoomId("ruang-1")
        store.saveOwnSecret(roomId, ByteArray(32) { 1 })

        store.saveOwnSecret(roomId, ByteArray(32) { 2 })

        assertContentEquals(ByteArray(32) { 2 }, store.readOwnSecret(roomId))
    }

    @Test
    fun `member_secret ruang berbeda tidak saling bocor`() {
        val store = newStore()
        store.saveOwnSecret(RoomId("ruang-a"), ByteArray(32) { 1 })
        store.saveOwnSecret(RoomId("ruang-b"), ByteArray(32) { 2 })

        assertContentEquals(ByteArray(32) { 1 }, store.readOwnSecret(RoomId("ruang-a")))
        assertContentEquals(ByteArray(32) { 2 }, store.readOwnSecret(RoomId("ruang-b")))
    }

    // --- Pemilik: daftar member_secret yang diterbitkan, dikaitkan belakangan ke memberId ---

    @Test
    fun `secret yang baru diterbitkan belum terkait memberId mana pun`() {
        val store = newStore()
        val roomId = RoomId("ruang-1")
        store.recordIssuedSecret(roomId, ByteArray(32) { 9 })

        assertNull(store.issuedSecretFor(roomId, "anggota-x"))
    }

    @Test
    fun `setelah dikaitkan, issuedSecretFor mengembalikan secret yang sama`() {
        val store = newStore()
        val roomId = RoomId("ruang-1")
        val secret = ByteArray(32) { 7 }
        val id = store.recordIssuedSecret(roomId, secret)

        store.bindIssuedSecretToMember(roomId, id, "anggota-x")

        assertContentEquals(secret, store.issuedSecretFor(roomId, "anggota-x"))
    }

    @Test
    fun `recordIssuedSecret mengembalikan id berbeda tiap pemanggilan`() {
        val store = newStore()
        val roomId = RoomId("ruang-1")

        val idA = store.recordIssuedSecret(roomId, ByteArray(32) { 1 })
        val idB = store.recordIssuedSecret(roomId, ByteArray(32) { 2 })

        assertNotEquals(idA, idB)
    }

    @Test
    fun `beberapa secret diterbitkan dan dikaitkan ke anggota berbeda tidak saling tertukar`() {
        val store = newStore()
        val roomId = RoomId("ruang-1")
        val secretX = ByteArray(32) { 1 }
        val secretY = ByteArray(32) { 2 }
        val idX = store.recordIssuedSecret(roomId, secretX)
        val idY = store.recordIssuedSecret(roomId, secretY)

        store.bindIssuedSecretToMember(roomId, idX, "anggota-x")
        store.bindIssuedSecretToMember(roomId, idY, "anggota-y")

        assertContentEquals(secretX, store.issuedSecretFor(roomId, "anggota-x"))
        assertContentEquals(secretY, store.issuedSecretFor(roomId, "anggota-y"))
    }

    @Test
    fun `memberId yang tidak pernah dikaitkan mengembalikan null`() {
        val store = newStore()
        val roomId = RoomId("ruang-1")
        val id = store.recordIssuedSecret(roomId, ByteArray(32) { 1 })
        store.bindIssuedSecretToMember(roomId, id, "anggota-x")

        assertNull(store.issuedSecretFor(roomId, "anggota-lain"))
    }

    @Test
    fun `daftar secret diterbitkan tidak bocor ke ruang lain`() {
        val store = newStore()
        val id = store.recordIssuedSecret(RoomId("ruang-a"), ByteArray(32) { 5 })
        store.bindIssuedSecretToMember(RoomId("ruang-a"), id, "anggota-x")

        assertNull(store.issuedSecretFor(RoomId("ruang-b"), "anggota-x"))
    }

    // --- Jalur terbungkus Keystore (disimulasikan dengan Aead Tink murni) vs fallback cleartext ---

    @Test
    fun `dengan master key yang berfungsi, blob tersimpan di SharedPreferences bukan cleartext langsung`() {
        // Kunci yang SAMA tiap kali dipanggil (meniru AndroidKeystoreKmsClient.getOrGenerateNewAeadKey
        // yang idempoten per URI) — fakeWorkingMasterAead() sendiri membuat kunci acak BARU setiap
        // dipanggil, jadi harus dibungkus satu kali saja di sini, bukan dipanggil ulang di lambda.
        val fakeMaster = fakeWorkingMasterAead()
        val store = newStore { fakeMaster }
        val roomId = RoomId("ruang-1")
        val secret = ByteArray(32) { 3 }

        store.saveOwnSecret(roomId, secret)

        val raw = context.getSharedPreferences("rizqflow_member_secret_crypto_${roomId.value}", Context.MODE_PRIVATE)
            .getString("own_secret", null)!!
        assertTrue(raw.startsWith("enc:"))
        assertFalse(raw.contains(Base64.getEncoder().encodeToString(secret)), "secret mentah tidak boleh muncul apa adanya di penyimpanan")
        assertContentEquals(secret, store.readOwnSecret(roomId))
    }

    @Test
    fun `GeneralSecurityException dan ProviderException dari Keystore jatuh ke cleartext dengan satu peringatan`() {
        for (failure in listOf<Exception>(GeneralSecurityException("keystore"), ProviderException("keystore"))) {
            val store = newStore { throw failure }
            val roomId = RoomId("ruang-${failure.javaClass.simpleName}")
            val secret = ByteArray(32) { 4 }
            ShadowLog.clear()

            store.saveOwnSecret(roomId, secret)

            assertContentEquals(secret, store.readOwnSecret(roomId))
            val warnings = ShadowLog.getLogs().filter { it.type == Log.WARN && it.tag == "MemberSecretStore" }.map { it.msg }
            assertEquals(1, warnings.size, "untuk ${failure.javaClass.simpleName}: $warnings")
            assertTrue(warnings.single().contains(failure.javaClass.name))
            assertFalse(warnings.single().contains("keystore"), "pesan galat asli tidak ikut dicatat")
        }
    }

    @Test
    fun `fallback cleartext tidak membocorkan member_secret mentah di pesan log`() {
        val store = newStore { throw GeneralSecurityException("keystore") }
        val secret = ByteArray(32) { 6 }
        ShadowLog.clear()

        store.saveOwnSecret(RoomId("ruang-1"), secret)

        val warnings = ShadowLog.getLogs().filter { it.type == Log.WARN && it.tag == "MemberSecretStore" }.map { it.msg }
        assertFalse(warnings.single().contains(Base64.getEncoder().encodeToString(secret)))
        assertFalse(warnings.single().contains(secret.joinToString("") { "%02x".format(it) }))
    }
}
