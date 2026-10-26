package com.roziqrizal.rizqflow.data.crypto

import android.content.Context
import android.util.Log
import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.LegacyKeysetSerialization
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.integration.android.AndroidKeystoreKmsClient
import com.google.crypto.tink.integration.android.SharedPrefKeysetWriter
import com.roziqrizal.rizqflow.domain.auth.AccountStorage
import com.roziqrizal.rizqflow.domain.sync.AccountKeyProvider
import com.roziqrizal.rizqflow.domain.sync.InvalidAccountKeyExport
import com.roziqrizal.rizqflow.domain.sync.SyncAead
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException

/**
 * Kunci akun Tahap 13 (server-sync.md): dibuat sekali secara acak (AES-256-GCM lewat Google Tink,
 * "tidak menulis kripto sendiri") dan disimpan di SharedPreferences, terbungkus master key Android Keystore
 * BILA Keystore bisa dipakai (bila tidak, Tink sendiri jatuh ke keyset cleartext; lihat [writeKeyset] dan catatan
 * di CLAUDE.md: pembungkusan Keystore sungguhan belum terbukti di perangkat) —
 * `AndroidKeysetManager` sendiri yang membuatnya saat pertama dipanggil bila belum ada, lalu memuat
 * ulang keyset yang sama pada pemanggilan berikutnya. Untuk pemakaian sehari-hari hanya primitif
 * [Aead] yang dipakai, tidak pernah byte kunci mentah. [exportAccountKeyForRecovery] sengaja jadi
 * satu-satunya jalan keluar byte keyset-nya, dan hanya dipanggil sesaat untuk dibungkus kode
 * pemulihan sebelum dikirim ke server (lihat [com.roziqrizal.rizqflow.domain.sync.AccountKeyRecoveryCrypto]).
 *
 * [accountId] (opsional) memisahkan keyset, berkas, dan alias Keystore per akun, seperti database dan token
 * server: akun Google lain di ponsel yang sama tidak pernah berbagi atau mencampur kunci akun ini (penting
 * untuk [importAccountKeyFromRecovery], yang menggabungkan/merge ke keyset akun INI saja). Null = nama lama
 * satu-untuk-semua-akun (dipertahankan agar tes lama dan pemanggil tanpa akun tetap jalan).
 *
 * Seluruh akses keyset dikunci pada instans ini ([synchronized]); [SyncAead] yang dikembalikan [getOrCreateAccountKey]
 * SATU instans tetap yang menunjuk ke kunci sekarang, jadi referensi yang sudah dipegang pemanggil ikut memakai
 * kunci baru setelah [importAccountKeyFromRecovery].
 */
class AndroidKeystoreAccountKeyProvider(
    private val context: Context,
    accountId: String? = null,
    /** Pengambil master key Keystore per URI; bawaan Tink. Hanya diganti di tes untuk meniru Keystore yang gagal. */
    private val masterAeadFor: (String) -> Aead = { AndroidKeystoreKmsClient.getOrGenerateNewAeadKey(it) },
) : AccountKeyProvider {

    private val scope = accountId?.let { "_" + AccountStorage.scopeKey(it) }.orEmpty()
    private val keysetName = KEYSET_NAME + scope
    private val prefFileName = PREF_FILE_NAME + scope
    private val masterKeyUri = MASTER_KEY_URI + scope

    private var cachedHandle: KeysetHandle? = null

    /** Satu instans seumur provider; isinya diganti saat impor. Dibaca tanpa kunci, jadi @Volatile. */
    private val sharedAead = SwappableSyncAead()

    @Volatile
    private var aeadLoaded = false

    override fun getOrCreateAccountKey(): SyncAead {
        if (aeadLoaded) return sharedAead
        synchronized(this) {
            if (!aeadLoaded) {
                sharedAead.swap(keysetHandle().getPrimitive(RegistryConfiguration.get(), Aead::class.java))
                aeadLoaded = true
            }
            return sharedAead
        }
    }

    /** Keyset lengkap (bukan hanya kunci mentah) diserialkan sekali untuk dibungkus [com.roziqrizal.rizqflow.domain.sync.AccountKeyRecoveryCrypto]. */
    override fun exportAccountKeyForRecovery(): ByteArray =
        synchronized(this) {
            TinkProtoKeysetFormat.serializeKeyset(keysetHandle(), InsecureSecretKeyAccess.get(), RegistryConfiguration.get())
        }

    /**
     * Memasang kunci akun pulihan sebagai GABUNGAN (merge), bukan timpa (Tahap 3 sync-desain.md bagian F
     * "Pulihkan menjaga data lama"; pola sama dengan `AndroidKeystoreRoomKeyProvider.rotateRoomKey`):
     * 1. [exportedAccountKey] di-parse DULU dan divalidasi bisa dipakai sebagai AEAD. Rusak: [InvalidAccountKeyExport],
     *    belum ada yang ditulis, kunci lokal dan cache tidak berubah.
     * 2. Bila perangkat ini SUDAH punya kunci akun (cache terisi atau berkas keyset sudah ada di prefs —
     *    diperiksa TANPA membuatnya bila belum ada), keyset gabungan dibangun lewat [mergeKeysets]: semua
     *    kunci lokal dipertahankan apa adanya (id dan status tetap, tidak ada yang primary lagi), semua kunci
     *    hasil pulihan ditambahkan dengan id baru (acak, dijamin tidak bentrok oleh `KeysetHandle.Builder`
     *    Tink sendiri) dan kunci yang primary di sana dijadikan primary gabungan. Bila perangkat ini BELUM
     *    pernah punya kunci akun sama sekali, tidak ada yang digabung — keyset hasil pulihan dipasang
     *    langsung, sama seperti `importRoomKey` untuk ruang yang belum pernah dipakai (tidak ada data lama
     *    untuk dijaga, dan tidak perlu membuat kunci lokal acak hanya untuk langsung digabung).
     * 3. Keyset akhir ditulis lewat [SharedPrefKeysetWriter] (jalur tulis publik Tink, format sama dengan yang
     *    dibaca [AndroidKeysetManager]), dibungkus master key Keystore akun ini bila tersedia. Bila Keystore tidak
     *    bisa dipakai (perangkat dengan Keystore rusak, atau shadow Robolectric di tes) jatuh ke penulisan
     *    CLEARTEXT (dicatat lewat Log.w, nama kelas galat saja), persis sikap [AndroidKeysetManager] sendiri saat Keystore gagal. INI BERARTI: di perangkat
     *    seperti itu kunci akun tersimpan tanpa pembungkus Keystore, sama seperti kunci yang dibuat Tink sendiri
     *    di sana; kode produksi tetap memanggil Keystore asli lebih dulu. Pembungkusan Keystore sungguhan belum
     *    terbukti di sini (Robolectric tidak punya Keystore asli), hanya jalur fallback-nya yang diuji.
     * 4. Cache (handle dan [SyncAead]) diganti SETELAH penulisan sukses; tulis gagal melempar tanpa mengubah cache.
     *    Penulisan prefs memakai satu `commit()`, jadi berkas lama tidak pernah setengah tertimpa.
     *
     * Tidak idempoten terhadap pemanggilan berulang: memanggil ini dua kali menambah kunci hasil pulihan dua
     * kali (dengan id berbeda tiap kali) ke keyset, bukan menggantikan penambahan sebelumnya — tidak salah
     * (dekripsi tetap benar, kunci terakhir tetap jadi primary), hanya keyset tumbuh tanpa perlu. Pembersihan
     * kunci akun yang sudah tidak terpakai bukan cakupan v1.
     */
    override fun importAccountKeyFromRecovery(exportedAccountKey: ByteArray) {
        synchronized(this) {
            AeadConfig.register()
            val importedHandle: KeysetHandle
            try {
                importedHandle = TinkProtoKeysetFormat.parseKeyset(exportedAccountKey, InsecureSecretKeyAccess.get(), RegistryConfiguration.get())
                importedHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
            } catch (e: GeneralSecurityException) {
                throw InvalidAccountKeyExport(e)
            } catch (e: IOException) {
                throw InvalidAccountKeyExport(e)
            } catch (e: IllegalArgumentException) {
                throw InvalidAccountKeyExport(e)
            }

            val finalHandle = if (hasExistingLocalKeyset()) {
                try {
                    mergeKeysets(keysetHandle(), importedHandle)
                } catch (e: GeneralSecurityException) {
                    throw InvalidAccountKeyExport(e)
                }
            } else {
                importedHandle
            }
            val finalAead = finalHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)

            val writer = SharedPrefKeysetWriter(context, keysetName, prefFileName)
            writeKeyset(finalHandle, writer)
            cachedHandle = finalHandle
            sharedAead.swap(finalAead)
            aeadLoaded = true
        }
    }

    /** True bila perangkat ini sudah punya kunci akun (dari cache memori ATAU berkas prefs tersimpan), TANPA membuat yang baru bila belum ada. */
    private fun hasExistingLocalKeyset(): Boolean =
        cachedHandle != null || aeadLoaded || context.getSharedPreferences(prefFileName, Context.MODE_PRIVATE).contains(keysetName)

    /**
     * Menggabungkan [imported] ke [local]: kunci [local] dipertahankan apa adanya (id dan status tetap,
     * tidak ada yang ditandai primary di keyset hasil) supaya ciphertext yang sudah ada tetap terbaca;
     * kunci [imported] ditambahkan dengan ID ASLINYA (Tink mengikat "ID requirement" ke `Key` yang
     * diambil dari keyset yang sudah ada — `withRandomId()` ditolak untuknya dengan
     * `GeneralSecurityException "Wrong ID set for key with ID requirement"`, dibuktikan lewat tes), dan
     * kunci yang primary di [imported] dijadikan primary keyset gabungan.
     *
     * Dua keyset independen bisa (secara statistik nyaris mustahil: ruang id 32-bit) sudah memakai id
     * yang sama persis untuk kunci berbeda; [KeysetHandle.Builder.build] menolaknya (dua entri id sama)
     * dan pemanggil memetakannya jadi [InvalidAccountKeyExport] tanpa menulis apa pun — bukan pembuatan id
     * baru paksa di sini, karena id kunci yang sudah ada tidak bisa diganti tanpa membongkar kunci itu.
     */
    private fun mergeKeysets(local: KeysetHandle, imported: KeysetHandle): KeysetHandle {
        val builder = KeysetHandle.newBuilder()
        for (i in 0 until local.size()) {
            val entry = local.getAt(i)
            builder.addEntry(KeysetHandle.importKey(entry.key).withFixedId(entry.id).setStatus(entry.status))
        }
        for (i in 0 until imported.size()) {
            val entry = imported.getAt(i)
            val builderEntry = KeysetHandle.importKey(entry.key).withFixedId(entry.id).setStatus(entry.status)
            if (entry.isPrimary) builderEntry.makePrimary()
            builder.addEntry(builderEntry)
        }
        return builder.build()
    }

    /**
     * Menulis keyset terbungkus master key Keystore; hanya bila KEYSTORE-nya tidak bisa dipakai jatuh ke penulisan
     * cleartext, persis sikap [AndroidKeysetManager] sendiri (yang menangkap [GeneralSecurityException] dan
     * [ProviderException] di `readOrGenerateNewMasterKey`, dikonfirmasi lewat `javap` Tink 1.24.0). Yang ditangkap
     * sengaja SESEMPIT itu: bug lain (mis. [IllegalStateException]) dan kegagalan MENULIS ([java.io.IOException],
     * disk penuh) bukan alasan membuang pembungkus Keystore, jadi dilempar ke pemanggil (dipetakan jadi gagal
     * pasang kunci tanpa mengubah kunci lokal). Jatuh ke cleartext dicatat lewat [Log.w] dengan NAMA KELAS galat
     * saja: tanpa kunci, tanpa kata, tanpa blob, tanpa pesan galat. Dipanggil hanya dari dalam `synchronized(this)`.
     */
    private fun writeKeyset(handle: KeysetHandle, writer: SharedPrefKeysetWriter) {
        val masterAead = try {
            masterAeadFor(masterKeyUri)
        } catch (e: GeneralSecurityException) {
            return writeCleartext(handle, writer, e)
        } catch (e: ProviderException) {
            return writeCleartext(handle, writer, e)
        }
        try {
            LegacyKeysetSerialization.serializeEncryptedKeyset(handle, writer, masterAead, ByteArray(0))
        } catch (e: GeneralSecurityException) {
            // Kunci Keystore tidak bisa dipakai membungkus (mis. tidak sah lagi): sama dengan Keystore tak tersedia.
            writeCleartext(handle, writer, e)
        }
    }

    private fun writeCleartext(handle: KeysetHandle, writer: SharedPrefKeysetWriter, cause: Exception) {
        // Jujur: kunci akun di perangkat ini tersimpan TANPA pembungkus Android Keystore. Hanya nama kelas galat.
        Log.w(TAG, "Keystore tidak bisa dipakai; keyset kunci akun ditulis tanpa pembungkus Keystore (${cause.javaClass.name})")
        LegacyKeysetSerialization.serializeKeyset(handle, writer, InsecureSecretKeyAccess.get())
    }

    /** Dipanggil hanya dari dalam blok `synchronized(this)` milik method publik di atas. */
    private fun keysetHandle(): KeysetHandle {
        cachedHandle?.let { return it }
        AeadConfig.register()
        val handle = AndroidKeysetManager.Builder()
            .withSharedPref(context, keysetName, prefFileName)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(masterKeyUri)
            .build()
            .keysetHandle
        cachedHandle = handle
        return handle
    }

    /** [SyncAead] tunggal yang meneruskan ke [Aead] sekarang; [swap] mengganti kunci untuk semua pemegang referensi. */
    private class SwappableSyncAead : SyncAead {
        @Volatile
        private var current: Aead? = null

        fun swap(aead: Aead) {
            current = aead
        }

        private fun aead(): Aead = checkNotNull(current) { "Kunci akun belum dimuat" }

        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
            aead().encrypt(plaintext, associatedData)

        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray =
            aead().decrypt(ciphertext, associatedData)
    }

    companion object {
        private const val TAG = "AccountKeyProvider"
        private const val KEYSET_NAME = "rizqflow_account_keyset"
        private const val PREF_FILE_NAME = "rizqflow_account_crypto"
        private const val MASTER_KEY_URI = "android-keystore://rizqflow_account_master_key"
    }
}
