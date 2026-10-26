package com.roziqrizal.rizqflow.data.crypto

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.roziqrizal.rizqflow.domain.sync.AccountKeyRecoveryCrypto
import com.roziqrizal.rizqflow.domain.sync.InvalidAccountKeyExport
import com.roziqrizal.rizqflow.domain.sync.RecoveryCode
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Impor kunci akun hasil pemulihan: GABUNG (merge), bukan timpa, sejak Tahap 3 sync-desain.md (begitu sinkron
 * entitas akun ada, kunci lokal yang mungkin sudah mengenkripsi data cakupan pribadi di perangkat ini wajib
 * tetap terbaca). Dua provider dengan `accountId` berbeda memakai berkas pref dan alias Keystore terpisah,
 * jadi meniru dua perangkat. PENTING: di Robolectric Keystore asli tidak ada dan Tink jatuh ke penyimpanan
 * keyset cleartext, jadi tes ini membuktikan logika impor, cache, dan persistensi, BUKAN pembungkusan Android
 * Keystore sungguhan.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidKeystoreAccountKeyImportTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun deviceA(): AndroidKeystoreAccountKeyProvider = AndroidKeystoreAccountKeyProvider(context, accountId = "perangkat-a")
    private fun deviceB(): AndroidKeystoreAccountKeyProvider = AndroidKeystoreAccountKeyProvider(context, accountId = "perangkat-b")

    @Test
    fun `ekspor dari A diimpor ke B baru, B membaca ciphertext A dan sebaliknya`() {
        val a = deviceA()
        val aKey = a.getOrCreateAccountKey()
        val b = deviceB()

        b.importAccountKeyFromRecovery(a.exportAccountKeyForRecovery())

        val fromA = aKey.encrypt("saldo dari A".toByteArray(), "ctx".toByteArray())
        assertContentEquals("saldo dari A".toByteArray(), b.getOrCreateAccountKey().decrypt(fromA, "ctx".toByteArray()))
        val fromB = b.getOrCreateAccountKey().encrypt("balasan dari B".toByteArray())
        assertContentEquals("balasan dari B".toByteArray(), aKey.decrypt(fromB))
    }

    @Test
    fun `tanpa impor kunci dua perangkat berbeda dan tidak saling membaca`() {
        val fromA = deviceA().getOrCreateAccountKey().encrypt("rahasia".toByteArray())

        assertFailsWith<Exception> { deviceB().getOrCreateAccountKey().decrypt(fromA) }
    }

    @Test
    fun `aead yang sudah dipegang pemanggil sebelum impor ikut memakai kunci pulihan, kunci lama tetap bisa dekripsi data lama`() {
        val a = deviceA()
        val fromA = a.getOrCreateAccountKey().encrypt("dari kunci pulihan".toByteArray())
        val b = deviceB()
        val heldBeforeImport = b.getOrCreateAccountKey()
        val encryptedWithOldKey = heldBeforeImport.encrypt("kunci lama".toByteArray())

        b.importAccountKeyFromRecovery(a.exportAccountKeyForRecovery())

        assertSame(heldBeforeImport, b.getOrCreateAccountKey(), "instans di-cache harus tetap satu")
        // Kunci baru (pulihan) jadi primary: dipakai untuk enkripsi berikutnya dan bisa membaca data dari A.
        assertContentEquals("dari kunci pulihan".toByteArray(), heldBeforeImport.decrypt(fromA))
        // Gabung (merge), bukan timpa: kunci lama B tetap ada di keyset yang sama, data lama B tetap terbaca.
        assertContentEquals("kunci lama".toByteArray(), heldBeforeImport.decrypt(encryptedWithOldKey))
    }

    @Test
    fun `impor sebelum kunci pernah dibuat tetap memasang kunci pulihan`() {
        val a = deviceA()
        val fromA = a.getOrCreateAccountKey().encrypt("data".toByteArray())
        val freshB = deviceB()

        freshB.importAccountKeyFromRecovery(a.exportAccountKeyForRecovery())

        assertContentEquals("data".toByteArray(), freshB.getOrCreateAccountKey().decrypt(fromA))
    }

    @Test
    fun `kunci pulihan tersimpan dan dimuat ulang oleh provider baru di proses lain`() {
        val a = deviceA()
        val fromA = a.getOrCreateAccountKey().encrypt("tahan lama".toByteArray())
        deviceB().apply { getOrCreateAccountKey() }.importAccountKeyFromRecovery(a.exportAccountKeyForRecovery())

        val reloadedB = deviceB()

        assertContentEquals("tahan lama".toByteArray(), reloadedB.getOrCreateAccountKey().decrypt(fromA))
    }

    @Test
    fun `ekspor setelah impor sama dengan ekspor sumber`() {
        val a = deviceA()
        a.getOrCreateAccountKey()
        val b = deviceB()

        b.importAccountKeyFromRecovery(a.exportAccountKeyForRecovery())

        assertContentEquals(a.exportAccountKeyForRecovery(), b.exportAccountKeyForRecovery())
    }

    @Test
    fun `impor data rusak ditolak dan kunci yang ada tetap utuh, juga setelah dimuat ulang`() {
        val b = deviceB()
        val ciphertext = b.getOrCreateAccountKey().encrypt("jangan rusak".toByteArray())
        val garbage = listOf(
            ByteArray(0),
            ByteArray(1) { 0x7f },
            ByteArray(64) { it.toByte() },
            "bukan keyset tink".toByteArray(),
            b.exportAccountKeyForRecovery().copyOf(10),
        )

        for (bad in garbage) {
            assertFailsWith<InvalidAccountKeyExport>("untuk ${bad.size} byte") { b.importAccountKeyFromRecovery(bad) }
        }

        assertContentEquals("jangan rusak".toByteArray(), b.getOrCreateAccountKey().decrypt(ciphertext))
        assertContentEquals("jangan rusak".toByteArray(), deviceB().getOrCreateAccountKey().decrypt(ciphertext))
    }

    @Test
    fun `akun berbeda tidak berbagi kunci dan impor satu akun tidak menyentuh akun lain`() {
        val a = deviceA()
        val aCipher = a.getOrCreateAccountKey().encrypt("milik A".toByteArray())
        val b = deviceB()
        val bCipher = b.getOrCreateAccountKey().encrypt("milik B".toByteArray())
        val c = AndroidKeystoreAccountKeyProvider(context, accountId = "perangkat-c")

        c.importAccountKeyFromRecovery(a.exportAccountKeyForRecovery())

        assertContentEquals("milik A".toByteArray(), a.getOrCreateAccountKey().decrypt(aCipher))
        assertContentEquals("milik B".toByteArray(), deviceB().getOrCreateAccountKey().decrypt(bCipher))
    }

    @Test
    fun `provider tanpa accountId memakai nama lama dan terpisah dari provider per akun`() {
        val legacy = AndroidKeystoreAccountKeyProvider(context)
        val legacyCipher = legacy.getOrCreateAccountKey().encrypt("lama".toByteArray())

        assertFailsWith<Exception> { deviceA().getOrCreateAccountKey().decrypt(legacyCipher) }
        assertContentEquals("lama".toByteArray(), AndroidKeystoreAccountKeyProvider(context).getOrCreateAccountKey().decrypt(legacyCipher))
    }

    @Test
    fun `gabung kunci baru jadi primary untuk enkripsi baru, ciphertext baru tidak terbaca kunci lama saja`() {
        val a = deviceA()
        val b = deviceB()
        val oldAead = b.getOrCreateAccountKey() // B sudah pernah punya kunci sebelum pulihkan.
        val oldOnlyHandle = TinkProtoKeysetFormat.parseKeyset(b.exportAccountKeyForRecovery(), InsecureSecretKeyAccess.get(), RegistryConfiguration.get())
        val oldOnlyAead = oldOnlyHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)

        b.importAccountKeyFromRecovery(a.exportAccountKeyForRecovery())

        val newCiphertext = b.getOrCreateAccountKey().encrypt("catatan baru setelah pulihkan".toByteArray())
        // Kunci baru (primary gabungan) bisa dibaca kunci A (yang jadi primary) maupun B sendiri setelah gabung.
        assertContentEquals("catatan baru setelah pulihkan".toByteArray(), a.getOrCreateAccountKey().decrypt(newCiphertext))
        // Kunci lama B SAJA (sebelum gabung, disalin di atas) tidak bisa membaca ciphertext baru: primary sudah pindah ke A.
        assertFailsWith<Exception>("kunci lama sendirian bukan lagi primary") { oldOnlyAead.decrypt(newCiphertext, ByteArray(0)) }
    }

    @Test
    fun `gabung dua kali berturut-turut tetap menjaga seluruh generasi kunci tetap terbaca`() {
        val a = deviceA()
        val other = AndroidKeystoreAccountKeyProvider(context, accountId = "perangkat-lain")
        val b = deviceB()
        val fromBAwal = b.getOrCreateAccountKey().encrypt("generasi awal B".toByteArray())
        val fromA = a.getOrCreateAccountKey().encrypt("generasi A".toByteArray())
        val fromOther = other.getOrCreateAccountKey().encrypt("generasi lain".toByteArray())

        b.importAccountKeyFromRecovery(a.exportAccountKeyForRecovery())
        b.importAccountKeyFromRecovery(other.exportAccountKeyForRecovery())

        val current = b.getOrCreateAccountKey()
        assertContentEquals("generasi awal B".toByteArray(), current.decrypt(fromBAwal), "generasi B paling awal tetap terbaca")
        assertContentEquals("generasi A".toByteArray(), current.decrypt(fromA), "generasi A (gabungan pertama) tetap terbaca")
        assertContentEquals("generasi lain".toByteArray(), current.decrypt(fromOther), "generasi terakhir (primary sekarang) terbaca")
    }

    @Test
    fun `impor dua kali berturut-turut memakai kunci terakhir`() {
        val a = deviceA()
        a.getOrCreateAccountKey()
        val other = AndroidKeystoreAccountKeyProvider(context, accountId = "sumber-lain")
        val fromOther = other.getOrCreateAccountKey().encrypt("lain".toByteArray())
        val b = deviceB()

        b.importAccountKeyFromRecovery(a.exportAccountKeyForRecovery())
        b.importAccountKeyFromRecovery(other.exportAccountKeyForRecovery())

        assertContentEquals("lain".toByteArray(), b.getOrCreateAccountKey().decrypt(fromOther))
        assertContentEquals("lain".toByteArray(), deviceB().getOrCreateAccountKey().decrypt(fromOther))
    }

    @Test
    fun `pulihkan lewat bungkusan kode pemulihan ujung ke ujung dengan Tink sungguhan`() {
        val a = deviceA()
        val fromA = a.getOrCreateAccountKey().encrypt("dompet".toByteArray())
        val code = RecoveryCode.generate()
        val wrapped = AccountKeyRecoveryCrypto.wrap(a.exportAccountKeyForRecovery(), code)
        val b = deviceB()

        b.importAccountKeyFromRecovery(AccountKeyRecoveryCrypto.unwrap(wrapped, RecoveryCode.parse(code.words)))

        assertContentEquals("dompet".toByteArray(), b.getOrCreateAccountKey().decrypt(fromA))
    }

    // ---- jatuh ke cleartext: sempit dan tidak diam-diam ----

    private fun warnings(): List<String> =
        ShadowLog.getLogs().filter { it.type == Log.WARN && it.tag == "AccountKeyProvider" }.map { it.msg }

    @Test
    fun `jatuh ke cleartext karena Keystore tidak tersedia meninggalkan jejak Log w tanpa data apa pun`() {
        val a = deviceA()
        a.getOrCreateAccountKey()
        val exported = a.exportAccountKeyForRecovery()
        ShadowLog.clear()

        // Di Robolectric Keystore asli tidak ada, jadi jalur fallback ini pasti terpakai.
        deviceB().importAccountKeyFromRecovery(exported)

        val messages = warnings()
        assertEquals(1, messages.size, "tepat satu peringatan: $messages")
        assertTrue(messages.single().contains("tanpa pembungkus Keystore"))
        assertTrue(messages.single().contains("java.security."), "nama kelas galat: ${messages.single()}")
        // Tanpa data: bukan kunci mentah, bukan base64-nya, bukan heksanya.
        val message = messages.single()
        assertFalse(message.contains(String(exported, Charsets.ISO_8859_1)))
        assertFalse(message.contains(Base64.getEncoder().encodeToString(exported)))
        assertFalse(message.contains(exported.joinToString("") { "%02x".format(it) }))
    }

    @Test
    fun `GeneralSecurityException dan ProviderException dari Keystore jatuh ke cleartext dengan peringatan`() {
        for (failure in listOf<Exception>(GeneralSecurityException("keystore"), ProviderException("keystore"))) {
            val a = deviceA()
            val fromA = a.getOrCreateAccountKey().encrypt("data".toByteArray())
            val b = AndroidKeystoreAccountKeyProvider(context, accountId = "tes-${failure.javaClass.simpleName}", masterAeadFor = { throw failure })
            ShadowLog.clear()

            b.importAccountKeyFromRecovery(a.exportAccountKeyForRecovery())

            assertContentEquals("data".toByteArray(), b.getOrCreateAccountKey().decrypt(fromA))
            assertEquals(1, warnings().size, "untuk ${failure.javaClass.simpleName}")
            assertTrue(warnings().single().contains(failure.javaClass.name))
            assertFalse(warnings().single().contains("keystore"), "pesan galat tidak ikut dicatat")
        }
    }

    @Test
    fun `kunci Keystore yang gagal membungkus saat menulis juga jatuh ke cleartext dengan peringatan`() {
        val broken = object : Aead {
            override fun encrypt(plaintext: ByteArray?, associatedData: ByteArray?): ByteArray = throw GeneralSecurityException("kunci tidak sah")
            override fun decrypt(ciphertext: ByteArray?, associatedData: ByteArray?): ByteArray = throw GeneralSecurityException("kunci tidak sah")
        }
        val a = deviceA()
        val fromA = a.getOrCreateAccountKey().encrypt("data".toByteArray())
        val b = AndroidKeystoreAccountKeyProvider(context, accountId = "tes-aead-rusak", masterAeadFor = { broken })
        ShadowLog.clear()

        b.importAccountKeyFromRecovery(a.exportAccountKeyForRecovery())

        assertContentEquals("data".toByteArray(), b.getOrCreateAccountKey().decrypt(fromA))
        assertEquals(1, warnings().size)
    }

    @Test
    fun `galat lain dari Keystore bukan alasan jatuh ke cleartext, jadi dilempar tanpa menulis apa pun dan kunci lokal utuh`() {
        val a = deviceA()
        a.getOrCreateAccountKey()
        val b = AndroidKeystoreAccountKeyProvider(context, accountId = "tes-bug", masterAeadFor = { throw IllegalStateException("bug") })
        val ownCipher = b.getOrCreateAccountKey().encrypt("milik sendiri".toByteArray())
        ShadowLog.clear()

        assertFailsWith<IllegalStateException> { b.importAccountKeyFromRecovery(a.exportAccountKeyForRecovery()) }

        assertEquals(emptyList(), warnings(), "tidak ada jatuh diam-diam ke cleartext")
        assertContentEquals("milik sendiri".toByteArray(), b.getOrCreateAccountKey().decrypt(ownCipher))
        assertContentEquals(
            "milik sendiri".toByteArray(),
            AndroidKeystoreAccountKeyProvider(context, accountId = "tes-bug").getOrCreateAccountKey().decrypt(ownCipher),
            "berkas keyset yang lama tidak tertimpa",
        )
    }
}
