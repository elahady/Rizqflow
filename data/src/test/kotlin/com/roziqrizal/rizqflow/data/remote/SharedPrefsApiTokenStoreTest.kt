package com.roziqrizal.rizqflow.data.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedPrefsApiTokenStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun newStore(accountId: String = "akun-a") = SharedPrefsApiTokenStore(context, accountId)

    @Test
    fun `belum pernah disimpan mengembalikan null`() {
        assertNull(newStore().read())
    }

    @Test
    fun `token yang disimpan terbaca lagi oleh store baru untuk akun dan konteks yang sama`() {
        newStore().save("token-abc", null)

        assertEquals("token-abc", newStore().read())
    }

    @Test
    fun `clear menghapus token tersimpan`() {
        val store = newStore()
        store.save("token-abc", null)

        store.clear()

        assertNull(store.read())
    }

    @Test
    fun `save menimpa token lama`() {
        val store = newStore()
        store.save("token-lama", null)

        store.save("token-baru", null)

        assertEquals("token-baru", store.read())
    }

    // --- Token milik satu akun, bukan satu untuk seluruh aplikasi ---

    @Test
    fun `akun lain di perangkat yang sama tidak membaca token akun sebelumnya`() {
        newStore("akun-a").save("token-a", null)

        // Akun A keluar tanpa membersihkan token; akun B masuk di perangkat yang sama.
        assertNull(newStore("akun-b").read())
    }

    @Test
    fun `tiap akun menyimpan tokennya sendiri`() {
        newStore("akun-a").save("token-a", null)
        newStore("akun-b").save("token-b", null)

        assertEquals("token-a", newStore("akun-a").read())
        assertEquals("token-b", newStore("akun-b").read())
    }

    @Test
    fun `clear akun satu tidak menghapus token akun lain`() {
        newStore("akun-a").save("token-a", null)
        newStore("akun-b").save("token-b", null)

        newStore("akun-a").clear()

        assertNull(newStore("akun-a").read())
        assertEquals("token-b", newStore("akun-b").read())
    }

    @Test
    fun `token berkas lama satu-untuk-semua-akun tidak dipakai dan dibuang`() {
        // Versi sebelumnya menyimpan satu token untuk seluruh aplikasi; pemiliknya tak diketahui.
        val legacy = context.getSharedPreferences("rizqflow_api_token", Context.MODE_PRIVATE)
        legacy.edit().putString("token", "token-lama-entah-milik-siapa").commit()

        assertNull(newStore("akun-a").read())
        assertNull(newStore("akun-b").read())
        assertNull(legacy.getString("token", null))
    }

    // --- Masa berlaku token (Tahap 12) ---

    private val expiry = Instant.parse("2027-01-22T10:00:00Z")

    @Test
    fun `masa berlaku disimpan bersama token dan terbaca lagi oleh store baru`() {
        newStore().save("token-abc", expiry)

        val reopened = newStore()
        assertEquals("token-abc", reopened.read())
        assertEquals(expiry, reopened.expiresAt())
    }

    @Test
    fun `token tanpa masa berlaku terbaca dan masa berlakunya belum diketahui`() {
        newStore().save("token-abc", null)

        val reopened = newStore()
        assertEquals("token-abc", reopened.read())
        assertNull(reopened.expiresAt())
    }

    @Test
    fun `token lama dari sebelum ada masa berlaku tetap terbaca dengan masa berlaku null`() {
        // Berkas versi sebelumnya hanya punya kunci "token" (tanpa kunci masa berlaku).
        val file = "rizqflow_api_token_" + com.roziqrizal.rizqflow.domain.auth.AccountStorage.scopeKey("akun-a")
        context.getSharedPreferences(file, Context.MODE_PRIVATE).edit().putString("token", "token-versi-lama").commit()

        val store = newStore("akun-a")

        assertEquals("token-versi-lama", store.read())
        assertNull(store.expiresAt())
    }

    @Test
    fun `menyimpan token baru tanpa tanggal membuang tanggal token sebelumnya`() {
        val store = newStore()
        store.save("token-lama", expiry)

        store.save("token-baru", null)

        assertEquals("token-baru", store.read())
        assertNull(store.expiresAt(), "tanggal token lama tidak boleh menempel pada token baru")
    }

    @Test
    fun `menyimpan token baru dengan tanggal menimpa tanggal lama`() {
        val store = newStore()
        store.save("token-lama", expiry)

        val later = expiry.plusSeconds(90L * 24 * 3600)
        store.save("token-baru", later)

        assertEquals(later, store.expiresAt())
    }

    @Test
    fun `clear menghapus token sekaligus masa berlakunya`() {
        val store = newStore()
        store.save("token-abc", expiry)

        store.clear()

        assertNull(store.read())
        assertNull(store.expiresAt())
        assertNull(newStore().expiresAt())
    }

    @Test
    fun `masa berlaku disimpan per akun, tidak bocor ke akun lain`() {
        newStore("akun-a").save("token-a", expiry)
        newStore("akun-b").save("token-b", null)

        assertEquals(expiry, newStore("akun-a").expiresAt())
        assertNull(newStore("akun-b").expiresAt())
        assertNull(newStore("akun-c").expiresAt())
    }

    // --- Waktu terima token (jeda minimum putar, Tahap 12) ---

    @Test
    fun `waktu terima disimpan bersama token dan terbaca lagi oleh store baru`() {
        val savedAt = Instant.parse("2026-12-01T08:30:00Z")
        newStore().save("token-abc", expiry, savedAt)

        val reopened = newStore()
        assertEquals(savedAt, reopened.lastSavedAt())
        assertEquals(expiry, reopened.expiresAt())
    }

    @Test
    fun `menyimpan tanpa waktu terima membuang waktu token sebelumnya`() {
        val store = newStore()
        store.save("token-lama", expiry, Instant.parse("2026-12-01T00:00:00Z"))

        store.save("token-baru", expiry)

        assertNull(store.lastSavedAt(), "waktu terima token lama tidak boleh menempel pada token baru")
    }

    @Test
    fun `clear menghapus waktu terima dan tanpa token waktu terima null`() {
        val store = newStore()
        store.save("token-abc", expiry, Instant.parse("2026-12-01T00:00:00Z"))

        store.clear()

        assertNull(store.lastSavedAt())
        assertNull(newStore().lastSavedAt())
    }

    // --- Pernah memegang token (Hapus akun saat sesi berakhir) ---

    @Test
    fun `akun yang belum pernah menyimpan token tidak dianggap pernah punya`() {
        assertEquals(false, newStore().hasEverHeldToken())
    }

    @Test
    fun `setelah token dibersihkan perangkat tetap ingat pernah memegangnya`() {
        newStore().save("token-abc", expiry)

        newStore().clear()

        assertNull(newStore().read())
        assertEquals(true, newStore().hasEverHeldToken(), "akun server-nya masih ada walau tokennya hilang")
    }

    @Test
    fun `catatan pernah punya tidak bocor ke akun lain`() {
        newStore("akun-a").save("token-a", expiry)
        newStore("akun-a").clear()

        assertEquals(false, newStore("akun-b").hasEverHeldToken())
    }

    @Test
    fun `token dari berkas versi lama tanpa catatan tetap dihitung pernah punya dan tertulis saat dibersihkan`() {
        val file = "rizqflow_api_token_" + com.roziqrizal.rizqflow.domain.auth.AccountStorage.scopeKey("akun-a")
        context.getSharedPreferences(file, Context.MODE_PRIVATE).edit().putString("token", "token-versi-lama").commit()

        val store = newStore("akun-a")
        assertEquals(true, store.hasEverHeldToken())

        store.clear()
        assertEquals(true, newStore("akun-a").hasEverHeldToken(), "clear pada berkas lama menuliskan catatannya")
    }

    @Test
    fun `forgetAccount menghapus token dan catatan pernah punya sekaligus`() {
        val store = newStore()
        store.save("token-abc", expiry, Instant.parse("2026-12-01T00:00:00Z"))

        store.forgetAccount()

        assertNull(newStore().read())
        assertNull(newStore().expiresAt())
        assertNull(newStore().lastSavedAt())
        assertEquals(false, newStore().hasEverHeldToken())
    }

    @Test
    fun `pengenal akun kosong ditolak`() {
        assertFailsWith<IllegalArgumentException> { newStore("") }
        assertFailsWith<IllegalArgumentException> { newStore("   ") }
    }

    @Test
    fun `save melaporkan penulisan ke disk berhasil, dan clear sudah tertulis saat kembali`() {
        val store = newStore()

        assertEquals(true, store.save("token-abc", expiry), "hasil true = tertulis ke penyimpanan tetap")

        store.clear()
        // Dibaca lewat objek baru (bukan cache store lama): keluar akun tidak boleh meninggalkan token di disk.
        assertNull(newStore().read())
        assertNull(newStore().expiresAt())
    }
}
